#!/usr/bin/env python3
"""Network-free tests for scripts/build_indb_food_index.py."""

from __future__ import annotations

import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import openpyxl

SCRIPT = Path(__file__).with_name("build_indb_food_index.py")
SPEC = importlib.util.spec_from_file_location("build_indb_food_index", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
indb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(indb)


HEADERS = [
    "food_code",
    "food_name",
    "primarysource",
    "energy_kcal",
    "protein_g",
    "carb_g",
    "fat_g",
    "freesugar_g",
    "fibre_g",
    "sfa_mg",
    "mufa_mg",
    "pufa_mg",
    "cholesterol_mg",
    "sodium_mg",
    "potassium_mg",
    "magnesium_mg",
    "calcium_mg",
    "iron_mg",
    "zinc_mg",
    "phosphorus_mg",
    "vita_ug",
    "vitc_mg",
    "vitd2_ug",
    "vitd3_ug",
    "vitb6_mg",
    "vitb3_mg",
    "servings_unit",
    "unit_serving_energy_kcal",
    "unit_serving_protein_g",
    "unit_serving_carb_g",
    "unit_serving_fat_g",
]


def row(
    *,
    code: str,
    name: str,
    kcal: float = 100.0,
    protein: float = 5.0,
    carbs: float = 15.0,
    fat: float = 2.0,
    serving_unit: str = "katori",
    serving_grams: float | None = 150.0,
) -> list[object]:
    values: dict[str, object] = {
        "food_code": code,
        "food_name": name,
        "primarysource": "bfp_manual",
        "energy_kcal": kcal,
        "protein_g": protein,
        "carb_g": carbs,
        "fat_g": fat,
        "freesugar_g": 1.5,
        "fibre_g": 3.0,
        "sfa_mg": 600.0,
        "mufa_mg": 800.0,
        "pufa_mg": 400.0,
        "cholesterol_mg": 4.0,
        "sodium_mg": 210.0,
        "potassium_mg": 330.0,
        "magnesium_mg": 35.0,
        "calcium_mg": 42.0,
        "iron_mg": 1.8,
        "zinc_mg": 0.9,
        "phosphorus_mg": 100.0,
        "vita_ug": 30.0,
        "vitc_mg": 4.0,
        "vitd2_ug": 0.2,
        "vitd3_ug": 0.3,
        "vitb6_mg": 0.1,
        "vitb3_mg": 0.7,
        "servings_unit": serving_unit,
    }
    if serving_grams is not None:
        factor = serving_grams / 100.0
        values.update(
            {
                "unit_serving_energy_kcal": kcal * factor,
                "unit_serving_protein_g": protein * factor,
                "unit_serving_carb_g": carbs * factor,
                "unit_serving_fat_g": fat * factor,
            }
        )
    return [values.get(header) for header in HEADERS]


def write_workbook(path: Path, rows: list[list[object]]) -> None:
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.append(HEADERS)
    for values in rows:
        sheet.append(values)
    workbook.save(path)
    workbook.close()


class IndbImporterTest(unittest.TestCase):
    def test_import_preserves_aliases_nutrients_and_derived_serving(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            workbook = temp_path / "Anuvaad_INDB_2024.11.xlsx"
            write_workbook(
                workbook,
                [row(code="BFP001", name="Hot tea (Garam Chai)", serving_grams=150.0)],
            )
            output = temp_path / "indb.sqlite"

            count = indb.build(
                workbook_path=workbook,
                output=output,
                dataset_version="2024.11-test",
                license_name="TEST LICENCE",
                attribution="Test attribution",
            )
            self.assertEqual(1, count)

            with sqlite3.connect(output) as conn:
                food = conn.execute(
                    """
                    SELECT source_food_id, canonical_name, calories, protein, carbs, fat,
                           micronutrients_json
                    FROM foods
                    """
                ).fetchone()
                self.assertEqual(("BFP001", "Hot tea", 100.0, 5.0, 15.0, 2.0), food[:6])
                micros = json.loads(food[6])
                self.assertEqual({"amount": 1.5, "unit": "g"}, micros["freeSugar"])
                self.assertNotIn("sugar", micros)
                self.assertEqual({"amount": 0.6, "unit": "g"}, micros["saturatedFat"])
                self.assertEqual({"amount": 0.5, "unit": "ug"}, micros["vitaminD"])

                aliases = {
                    value[0]
                    for value in conn.execute("SELECT name FROM aliases")
                }
                self.assertIn("Garam Chai", aliases)
                self.assertIn("Hot tea (Garam Chai)", aliases)

                portion = conn.execute(
                    "SELECT amount, unit, grams, is_default, is_derived FROM portions"
                ).fetchone()
                self.assertEqual((1.0, "katori", 150.0, 1, 1), portion)

            manifest = json.loads(
                output.with_suffix(".manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual("INDB", manifest["source"])
            self.assertEqual("TEST LICENCE", manifest["license"])
            self.assertEqual("Test attribution", manifest["attribution"])

    def test_serving_mass_requires_multiple_agreeing_ratios(self) -> None:
        columns = {name: index for index, name in enumerate(HEADERS)}
        good = tuple(
            row(code="A", name="Dal", serving_grams=180.0)
        )
        self.assertAlmostEqual(
            180.0,
            indb.derived_serving_grams(good, columns) or 0.0,
            places=2,
        )

        disagreeing = list(row(code="B", name="Dal", serving_grams=180.0))
        disagreeing[columns["unit_serving_fat_g"]] = 20.0
        self.assertIsNone(indb.derived_serving_grams(tuple(disagreeing), columns))

        single_ratio = list(row(code="C", name="Dal", serving_grams=None))
        single_ratio[columns["unit_serving_energy_kcal"]] = 150.0
        self.assertIsNone(indb.derived_serving_grams(tuple(single_ratio), columns))

    def test_missing_macro_is_skipped_instead_of_imputed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            workbook = temp_path / "indb.xlsx"
            incomplete = list(row(code="X", name="Incomplete dish"))
            incomplete[HEADERS.index("fat_g")] = None
            write_workbook(workbook, [incomplete])
            output = temp_path / "indb.sqlite"

            count = indb.build(
                workbook,
                output,
                "test",
                "TEST LICENCE",
                "Test attribution",
            )
            self.assertEqual(0, count)
            with sqlite3.connect(output) as conn:
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM foods").fetchone()[0])

    def test_common_serving_units_are_normalized_without_inventing_weights(self) -> None:
        self.assertEqual("tbsp", indb.normalize_serving_unit("tablespoons"))
        self.assertEqual("piece", indb.normalize_serving_unit("pieces"))
        self.assertEqual("katori", indb.normalize_serving_unit("katoris"))
        self.assertEqual("small ladle", indb.normalize_serving_unit("Small   Ladle"))


if __name__ == "__main__":
    unittest.main()
