#!/usr/bin/env python3
"""Offline tests for scripts/build_usda_food_index.py.

These tests create miniature FoodData Central CSV directories from scratch. They never download
USDA data, so CI can verify importer semantics, schema and manifest integrity deterministically.
"""

from __future__ import annotations

import csv
import hashlib
import importlib.util
import json
import sqlite3
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("build_usda_food_index.py")
SPEC = importlib.util.spec_from_file_location("build_usda_food_index", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
usda = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(usda)


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_json_archive(path: Path, payload: dict[str, object]) -> Path:
    json_name = path.with_suffix("").name + ".json"
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(json_name, json.dumps(payload))
    return path


def foundation_json_food(fdc_id: int = 321358) -> dict[str, object]:
    return {
        "fdcId": fdc_id,
        "dataType": "Foundation",
        "description": "Hummus, commercial",
        "foodNutrients": [
            {"nutrient": {"id": 1003, "unitName": "g"}, "amount": 7.35},
            {"nutrient": {"id": 1004, "unitName": "g"}, "amount": 17.1},
            {"nutrient": {"id": 1005, "unitName": "g"}, "amount": 14.9},
            {"nutrient": {"id": 2047, "unitName": "kcal"}, "amount": 230.0},
            {"nutrient": {"id": 2048, "unitName": "kcal"}, "amount": 229.0},
            {"nutrient": {"id": 1093, "unitName": "mg"}, "amount": 438.0},
            {"nutrient": {"id": 1114, "unitName": "UG"}, "amount": 1.0},
        ],
        "foodPortions": [
            {
                "amount": 2,
                "measureUnit": {"name": "tablespoon", "abbreviation": "tbsp"},
                "modifier": "",
                "gramWeight": 33.9,
            }
        ],
    }


def survey_json_food(fdc_id: int = 2705384) -> dict[str, object]:
    return {
        "fdcId": fdc_id,
        "dataType": "Survey (FNDDS)",
        "description": "Milk, NFS",
        "foodNutrients": [
            {"nutrient": {"id": 1003, "unitName": "g"}, "amount": 3.33},
            {"nutrient": {"id": 1004, "unitName": "g"}, "amount": 2.14},
            {"nutrient": {"id": 1005, "unitName": "g"}, "amount": 4.83},
            {"nutrient": {"id": 1008, "unitName": "kcal"}, "amount": 52.0},
        ],
        "foodPortions": [
            {
                "measureUnit": {"name": "undetermined", "abbreviation": "undetermined"},
                "modifier": "90000",
                "gramWeight": 0,
                "portionDescription": "Quantity not specified",
            },
            {
                "measureUnit": {"name": "undetermined", "abbreviation": "undetermined"},
                "modifier": "30000",
                "gramWeight": 30.5,
                "portionDescription": "1 fl oz",
            },
        ],
    }


def make_fdc_dir(
    root: Path,
    *,
    fdc_id: int,
    description: str,
    data_type: str = "foundation_food",
    include_fat: bool = True,
    cup_grams: float | None = None,
) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    write_csv(
        root / "food.csv",
        ["fdc_id", "data_type", "description"],
        [{"fdc_id": fdc_id, "data_type": data_type, "description": description}],
    )

    # Deliberately expose legacy nutrient_nbr values. The importer must still prefer the modern
    # nutrient_id (1008/1003/1005/1004), which is what FoodData Central food_nutrient.csv uses.
    write_csv(
        root / "nutrient.csv",
        ["id", "nutrient_nbr"],
        [
            {"id": 1008, "nutrient_nbr": 208},
            {"id": 1003, "nutrient_nbr": 203},
            {"id": 1005, "nutrient_nbr": 205},
            {"id": 1004, "nutrient_nbr": 204},
            {"id": 1093, "nutrient_nbr": 307},
        ],
    )
    nutrient_rows = [
        {"fdc_id": fdc_id, "nutrient_id": 1008, "amount": 130.0},
        {"fdc_id": fdc_id, "nutrient_id": 1003, "amount": 2.7},
        {"fdc_id": fdc_id, "nutrient_id": 1005, "amount": 28.2},
        {"fdc_id": fdc_id, "nutrient_id": 1093, "amount": 5.0},
    ]
    if include_fat:
        nutrient_rows.append({"fdc_id": fdc_id, "nutrient_id": 1004, "amount": 0.3})
    write_csv(
        root / "food_nutrient.csv",
        ["fdc_id", "nutrient_id", "amount"],
        nutrient_rows,
    )

    if cup_grams is not None:
        write_csv(
            root / "food_portion.csv",
            ["fdc_id", "amount", "gram_weight", "seq_num", "portion_description", "modifier"],
            [
                {
                    "fdc_id": fdc_id,
                    "amount": 1,
                    "gram_weight": cup_grams,
                    "seq_num": 1,
                    "portion_description": "1 cup",
                    "modifier": "cup",
                }
            ],
        )
    return root


class UsdaImporterTest(unittest.TestCase):
    def test_fixture_build_has_schema_manifest_and_matching_sha(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixture.sqlite"
            count = usda.build_fixture(output)
            self.assertEqual(2, count)
            self.assertTrue(output.is_file())

            with sqlite3.connect(output) as conn:
                self.assertEqual(2, conn.execute("SELECT COUNT(*) FROM foods").fetchone()[0])
                self.assertEqual(2, conn.execute("SELECT COUNT(*) FROM portions").fetchone()[0])
                row = conn.execute(
                    "SELECT calories, protein, carbs, fat FROM foods WHERE source_food_id='900000001'"
                ).fetchone()
                self.assertEqual((100.0, 1.0, 24.0, 0.5), row)

            manifest = json.loads(output.with_suffix(".manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("USDA", manifest["source"])
            self.assertEqual("synthetic-fixture-do-not-ship", manifest["datasetVersion"])
            self.assertEqual(2, manifest["recordCount"])
            self.assertEqual(
                hashlib.sha256(output.read_bytes()).hexdigest(),
                manifest["sha256"],
            )

    def test_modern_nutrient_ids_win_over_legacy_nutrient_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = make_fdc_dir(
                Path(temp) / "foundation",
                fdc_id=123,
                description="Rice, white, cooked",
                cup_grams=158.0,
            )
            output = Path(temp) / "usda.sqlite"
            count = usda.build_from_csv_roots([root], output, "test-release")
            self.assertEqual(1, count)

            with sqlite3.connect(output) as conn:
                row = conn.execute(
                    "SELECT calories, protein, carbs, fat, micronutrients_json FROM foods"
                ).fetchone()
                self.assertEqual((130.0, 2.7, 28.2, 0.3), row[:4])
                micros = json.loads(row[4])
                self.assertEqual({"amount": 5.0, "unit": "mg"}, micros["sodium"])
                portion = conn.execute(
                    "SELECT amount, unit, grams, is_default FROM portions"
                ).fetchone()
                self.assertEqual((1.0, "cup", 158.0, 1), portion)

    def test_incomplete_macro_row_is_rejected_not_repaired(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = make_fdc_dir(
                Path(temp) / "foundation",
                fdc_id=456,
                description="Incomplete fixture food",
                include_fat=False,
            )
            output = Path(temp) / "usda.sqlite"
            count = usda.build_from_csv_roots([root], output, "test-release")
            self.assertEqual(0, count)
            with sqlite3.connect(output) as conn:
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM foods").fetchone()[0])

    def test_multiple_archives_merge_without_branded_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            foundation = make_fdc_dir(
                temp_path / "foundation",
                fdc_id=1001,
                description="Bananas, raw",
                data_type="foundation_food",
            )
            survey = make_fdc_dir(
                temp_path / "survey",
                fdc_id=1002,
                description="Rice porridge, cooked",
                data_type="survey_fndds_food",
            )
            branded = make_fdc_dir(
                temp_path / "branded",
                fdc_id=1003,
                description="Some branded product",
                data_type="branded_food",
            )
            output = temp_path / "usda.sqlite"
            count = usda.build_from_csv_roots(
                [foundation, survey, branded],
                output,
                "foundation-test+fndds-test",
            )
            self.assertEqual(2, count)
            with sqlite3.connect(output) as conn:
                names = [row[0] for row in conn.execute(
                    "SELECT canonical_name FROM foods ORDER BY source_food_id"
                )]
                self.assertEqual(["Bananas, raw", "Rice porridge, cooked"], names)
                aliases = [row[0] for row in conn.execute(
                    "SELECT name FROM aliases WHERE source_food_id='1001' ORDER BY name"
                )]
                self.assertIn("Banana", aliases)

    def test_nonfinite_macro_rows_are_rejected(self) -> None:
        for invalid in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as temp:
                root = make_fdc_dir(Path(temp) / "source", fdc_id=1, description="Synthetic food")
                path = root / "food_nutrient.csv"
                path.write_text(path.read_text().replace("130.0", invalid))
                output = Path(temp) / "out.sqlite"
                self.assertEqual(0, usda.build_from_csv_roots([root], output, "test"))

    def test_legacy_nutrient_numbers_are_a_separate_fallback_namespace(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = make_fdc_dir(Path(temp), fdc_id=1, description="Synthetic food")
            path = root / "food_nutrient.csv"
            text = path.read_text()
            for modern, legacy in ((1008, 208), (1003, 203), (1005, 205), (1004, 204), (1093, 307)):
                text = text.replace(str(modern), str(legacy))
            path.write_text(text)
            values = usda.read_nutrients(root, usda.read_foods(root))[1]
            self.assertEqual((130.0, "kcal"), values["calories"])
            self.assertEqual((5.0, "mg"), values["sodium"])

    def test_multiple_portions_do_not_invent_a_default_from_sequence_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = make_fdc_dir(Path(temp), fdc_id=1, description="Synthetic food")
            write_csv(root / "food_portion.csv",
                ["fdc_id", "amount", "gram_weight", "seq_num", "portion_description"],
                [{"fdc_id": 1, "amount": 1, "gram_weight": grams, "seq_num": seq,
                  "portion_description": label}
                 for grams, seq, label in ((100, 1, "1 cup"), (150, 2, "1 cup"), (float("inf"), 3, "1 piece"))])
            portions = usda.read_portions(root, usda.read_foods(root))[1]
            self.assertEqual(2, len(portions))
            self.assertFalse(any(p["is_default"] for p in portions))

    def test_official_json_shapes_build_without_csv_expansion(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            foundation = write_json_archive(
                temp_path / "foundation.zip",
                {"FoundationFoods": [foundation_json_food()]},
            )
            survey = write_json_archive(
                temp_path / "survey.zip",
                {"SurveyFoods": [survey_json_food()]},
            )
            output = temp_path / "usda.sqlite"

            count = usda.build_from_inputs(
                [foundation, survey],
                output,
                "foundation-json-test+fndds-json-test",
            )
            self.assertEqual(2, count)

            with sqlite3.connect(output) as conn:
                hummus = conn.execute(
                    """
                    SELECT calories, protein, carbs, fat, micronutrients_json
                    FROM foods WHERE source_food_id='321358'
                    """
                ).fetchone()
                # Published Foundation 2048 energy outranks 2047 when 1008 is absent.
                self.assertEqual((229.0, 7.35, 14.9, 17.1), hummus[:4])
                hummus_micros = json.loads(hummus[4])
                self.assertEqual({"amount": 438.0, "unit": "mg"}, hummus_micros["sodium"])
                self.assertEqual({"amount": 1.0, "unit": "ug"}, hummus_micros["vitaminD"])

                foundation_portion = conn.execute(
                    """
                    SELECT amount, unit, grams, is_default
                    FROM portions WHERE source_food_id='321358'
                    """
                ).fetchone()
                self.assertEqual((2.0, "tbsp", 33.9, 1), foundation_portion)

                survey_portion = conn.execute(
                    """
                    SELECT amount, unit, grams, is_default
                    FROM portions WHERE source_food_id='2705384'
                    """
                ).fetchone()
                self.assertEqual((1.0, "fl oz", 30.5, 1), survey_portion)

    def test_json_energy_1008_outranks_foundation_alternatives(self) -> None:
        item = foundation_json_food()
        item["foodNutrients"].append(
            {"nutrient": {"id": 1008, "unitName": "kcal"}, "amount": 231.0}
        )
        nutrients = usda._json_nutrients(item)
        self.assertEqual((231.0, "kcal"), nutrients["calories"])

    def test_json_multiple_portions_preserve_ambiguity(self) -> None:
        item = survey_json_food()
        item["foodPortions"].append(
            {
                "measureUnit": {"name": "undetermined", "abbreviation": "undetermined"},
                "gramWeight": 244.0,
                "portionDescription": "1 cup",
            }
        )
        portions = usda._json_portions(item)
        self.assertEqual(2, len(portions))
        self.assertFalse(any(portion["is_default"] for portion in portions))
        self.assertEqual({"fl oz", "cup"}, {portion["unit"] for portion in portions})

    def test_duplicate_ids_across_json_inputs_fail_instead_of_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            first = write_json_archive(
                temp_path / "one.zip",
                {"FoundationFoods": [foundation_json_food(42)]},
            )
            duplicate = survey_json_food(42)
            second = write_json_archive(
                temp_path / "two.zip",
                {"SurveyFoods": [duplicate]},
            )
            with self.assertRaises(SystemExit):
                usda.build_from_inputs([first, second], temp_path / "out.sqlite", "test")

    def test_unknown_portion_wording_does_not_become_a_fake_known_unit(self) -> None:
        self.assertIsNone(usda.portion_unit("1 unspecified scoop"))
        self.assertEqual("cup", usda.portion_unit("1 cup, cooked"))
        self.assertEqual("piece", usda.portion_unit("2 pieces"))


if __name__ == "__main__":
    unittest.main()
