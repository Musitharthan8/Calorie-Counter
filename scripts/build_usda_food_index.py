#!/usr/bin/env python3
"""Build a compact USDA FoodData Central Foundation + FNDDS nutrition index.

This script intentionally has no third-party Python dependencies and does not download data.
Give it either an extracted FoodData Central CSV directory or the official bulk CSV ZIP.

Examples:
  python scripts/build_usda_food_index.py \
      --input ~/Downloads/FoodData_Central_foundation_food_csv_2026-04-30.zip \
              ~/Downloads/FoodData_Central_survey_food_csv_2024-10-31.zip \
      --dataset-version foundation-2026-04+fndds-2021-2023

  # Tiny synthetic database for importer smoke-testing. Never ship it as production data.
  python scripts/build_usda_food_index.py --fixture --output build/usda-fixture.sqlite

Production output defaults to:
  android/app/src/main/assets/nutrition/usda/usda_foods.sqlite
  android/app/src/main/assets/nutrition/usda/usda_foods.manifest.json

Policy:
- Foundation + survey/FNDDS foods only. Branded foods are left to Open Food Facts.
- Nutrition is stored per 100 g.
- Calories/protein/carbs/fat must all be present and non-negative.
- Missing nutrients remain missing. This script does NOT derive calories with Atwater factors.
- Portion grams are imported only when USDA explicitly supplies a positive gram_weight.
- USDA FoodData Central data are distributed under CC0 1.0/public domain terms; attribution is
  retained in the sidecar manifest even though CC0 does not require it.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shutil
import sqlite3
import tempfile
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

REPO = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = (
    REPO
    / "android"
    / "app"
    / "src"
    / "main"
    / "assets"
    / "nutrition"
    / "usda"
    / "usda_foods.sqlite"
)

SOURCE_URL = "https://fdc.nal.usda.gov/"
SOURCE_NAME = "USDA FoodData Central"
SOURCE_KIND = "USDA"
LICENSE = "CC0 1.0"
ATTRIBUTION = "U.S. Department of Agriculture, Agricultural Research Service, FoodData Central"
ALLOWED_DATA_TYPES = {"foundation_food", "survey_fndds_food"}

# Stable FoodData Central nutrient ids. Values are canonical app key + canonical storage unit.
NUTRIENTS = {
    1008: ("calories", "kcal"),
    1003: ("protein", "g"),
    1005: ("carbs", "g"),
    1004: ("fat", "g"),
    1079: ("fiber", "g"),
    2000: ("sugar", "g"),
    1235: ("addedSugar", "g"),
    1258: ("saturatedFat", "g"),
    1292: ("monounsaturatedFat", "g"),
    1293: ("polyunsaturatedFat", "g"),
    1253: ("cholesterol", "mg"),
    1093: ("sodium", "mg"),
    1092: ("potassium", "mg"),
    1257: ("transFat", "g"),
    1087: ("calcium", "mg"),
    1089: ("iron", "mg"),
    1090: ("magnesium", "mg"),
    1095: ("zinc", "mg"),
    1106: ("vitaminA", "ug"),
    1162: ("vitaminC", "mg"),
    1114: ("vitaminD", "ug"),
    1178: ("vitaminB12", "ug"),
    1109: ("vitaminE", "mg"),
    1185: ("vitaminK", "ug"),
    1177: ("folate", "ug"),
}

MACRO_KEYS = ("calories", "protein", "carbs", "fat")

# Explicit household units that the runtime can safely compare with interpreted user units.
UNIT_PATTERNS = [
    (re.compile(r"\bcups?\b", re.I), "cup"),
    (re.compile(r"\b(?:tablespoons?|tbsp)\b", re.I), "tbsp"),
    (re.compile(r"\b(?:teaspoons?|tsp)\b", re.I), "tsp"),
    (re.compile(r"\bslices?\b", re.I), "slice"),
    (re.compile(r"\bpieces?\b", re.I), "piece"),
    (re.compile(r"\bbowls?\b", re.I), "bowl"),
    (re.compile(r"\bplates?\b", re.I), "plate"),
    (re.compile(r"\bcontainers?\b", re.I), "container"),
    (re.compile(r"\bpackets?\b", re.I), "packet"),
    (re.compile(r"\bcans?\b", re.I), "can"),
    (re.compile(r"\bservings?\b", re.I), "serving"),
    (re.compile(r"\blarge\b", re.I), "large"),
    (re.compile(r"\bmedium\b", re.I), "medium"),
    (re.compile(r"\bsmall\b", re.I), "small"),
]


def normalize_name(value: str) -> str:
    value = value.replace("’", "'").strip().lower()
    value = re.sub(r"[^\w']+", " ", value, flags=re.UNICODE)
    return re.sub(r"\s+", " ", value).strip()


def aliases_for_description(description: str) -> list[str]:
    """Conservative aliases only; ambiguity is allowed and resolved at runtime."""
    result: list[str] = []
    first = description.split(",", 1)[0].strip()
    if len(first) >= 3 and normalize_name(first) != normalize_name(description):
        result.append(first)
        # "Bananas" -> "Banana", while keeping the original plural alias too.
        if " " not in first and first.lower().endswith("s") and len(first) > 4:
            result.append(first[:-1])
    de_punct = re.sub(r"[,;()]+", " ", description)
    de_punct = re.sub(r"\s+", " ", de_punct).strip()
    if normalize_name(de_punct) != normalize_name(description):
        result.append(de_punct)
    seen: set[str] = set()
    return [x for x in result if (n := normalize_name(x)) and not (n in seen or seen.add(n))]


def discover_csv_root(root: Path) -> Path:
    if (root / "food.csv").exists():
        return root
    hits = list(root.rglob("food.csv"))
    if len(hits) != 1:
        raise SystemExit(f"Expected exactly one food.csv below {root}; found {len(hits)}")
    return hits[0].parent


def open_input(path: Path) -> tuple[Path, tempfile.TemporaryDirectory[str] | None]:
    if path.is_dir():
        return discover_csv_root(path), None
    if not zipfile.is_zipfile(path):
        raise SystemExit(f"--input must be an extracted FDC CSV directory or ZIP: {path}")
    temp = tempfile.TemporaryDirectory(prefix="calorie-counter-usda-")
    with zipfile.ZipFile(path) as zf:
        zf.extractall(temp.name)
    return discover_csv_root(Path(temp.name)), temp


def read_foods(csv_root: Path) -> dict[int, dict[str, str]]:
    result: dict[int, dict[str, str]] = {}
    with (csv_root / "food.csv").open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            if row.get("data_type") not in ALLOWED_DATA_TYPES:
                continue
            try:
                fdc_id = int(row["fdc_id"])
            except (KeyError, TypeError, ValueError):
                continue
            description = (row.get("description") or "").strip()
            if description:
                result[fdc_id] = row
    return result


def read_nutrient_id_map(csv_root: Path) -> dict[int, int]:
    """Map food_nutrient.nutrient_id -> stable nutrient_nbr where available."""
    path = csv_root / "nutrient.csv"
    if not path.exists():
        return {}
    result: dict[int, int] = {}
    with path.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            try:
                row_id = int(row["id"])
                nutrient_nbr = int(float(row.get("nutrient_nbr") or row_id))
            except (KeyError, TypeError, ValueError):
                continue
            result[row_id] = nutrient_nbr
    return result


def read_nutrients(
    csv_root: Path,
    foods: dict[int, dict[str, str]],
) -> dict[int, dict[str, tuple[float, str]]]:
    id_to_nbr = read_nutrient_id_map(csv_root)
    result: dict[int, dict[str, tuple[float, str]]] = defaultdict(dict)
    with (csv_root / "food_nutrient.csv").open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            try:
                fdc_id = int(row["fdc_id"])
                raw_id = int(row["nutrient_id"])
                amount = float(row["amount"])
            except (KeyError, TypeError, ValueError):
                continue
            if fdc_id not in foods or amount < 0:
                continue
            nutrient_nbr = id_to_nbr.get(raw_id, raw_id)
            mapped = NUTRIENTS.get(nutrient_nbr)
            if mapped is None:
                continue
            key, unit = mapped
            result[fdc_id][key] = (amount, unit)
    return result


def portion_unit(*texts: str) -> str | None:
    haystack = " ".join(t for t in texts if t).strip()
    for pattern, unit in UNIT_PATTERNS:
        if pattern.search(haystack):
            return unit
    return None


def read_portions(
    csv_root: Path,
    foods: dict[int, dict[str, str]],
) -> dict[int, list[dict[str, object]]]:
    path = csv_root / "food_portion.csv"
    if not path.exists():
        return {}
    result: dict[int, list[dict[str, object]]] = defaultdict(list)
    with path.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            try:
                fdc_id = int(row["fdc_id"])
                grams = float(row["gram_weight"])
                amount = float(row.get("amount") or 1)
                seq = int(float(row.get("seq_num") or 999999))
            except (KeyError, TypeError, ValueError):
                continue
            if fdc_id not in foods or grams <= 0 or amount <= 0:
                continue
            description = (row.get("portion_description") or row.get("modifier") or "").strip()
            unit = portion_unit(description, row.get("modifier") or "")
            if unit is None:
                # Unknown wording is retained only as the source's default portion. It can support
                # an interpreted "serving" but is never equated with cup/piece/etc.
                unit = description[:48] if description and seq == 1 else None
            if not unit:
                continue
            result[fdc_id].append(
                {
                    "amount": amount,
                    "unit": unit,
                    "grams": grams,
                    "description": description or None,
                    "seq": seq,
                }
            )

    for fdc_id, portions in result.items():
        portions.sort(key=lambda p: (int(p["seq"]), str(p["unit"])))
        # Keep a small deterministic set and mark exactly one source default.
        del portions[8:]
        for i, portion in enumerate(portions):
            portion["is_default"] = i == 0
    return result


def create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA foreign_keys = ON;

        CREATE TABLE foods (
            source_food_id TEXT PRIMARY KEY,
            canonical_name TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            region TEXT,
            brand TEXT,
            preparation TEXT,
            calories REAL NOT NULL,
            protein REAL NOT NULL,
            carbs REAL NOT NULL,
            fat REAL NOT NULL,
            micronutrients_json TEXT NOT NULL,
            source_url TEXT,
            search_text TEXT NOT NULL
        );

        CREATE TABLE aliases (
            source_food_id TEXT NOT NULL,
            name TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            language_tag TEXT,
            PRIMARY KEY (source_food_id, normalized_name),
            FOREIGN KEY (source_food_id) REFERENCES foods(source_food_id) ON DELETE CASCADE
        );

        CREATE TABLE portions (
            source_food_id TEXT NOT NULL,
            amount REAL NOT NULL,
            unit TEXT NOT NULL,
            grams REAL NOT NULL,
            description TEXT,
            is_default INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (source_food_id, unit, amount, grams),
            FOREIGN KEY (source_food_id) REFERENCES foods(source_food_id) ON DELETE CASCADE
        );

        CREATE INDEX foods_normalized_name_idx ON foods(normalized_name);
        CREATE INDEX aliases_normalized_name_idx ON aliases(normalized_name);
        CREATE INDEX portions_food_idx ON portions(source_food_id);
        """
    )


def write_food(
    conn: sqlite3.Connection,
    fdc_id: int,
    meta: dict[str, str],
    nutrients: dict[str, tuple[float, str]],
    portions: list[dict[str, object]],
) -> bool:
    if any(key not in nutrients for key in MACRO_KEYS):
        return False
    macros = {key: nutrients[key][0] for key in MACRO_KEYS}
    if any(value < 0 for value in macros.values()):
        return False

    description = (meta.get("description") or "").strip()
    source_id = str(fdc_id)
    micros = {
        key: {"amount": amount, "unit": unit}
        for key, (amount, unit) in nutrients.items()
        if key not in MACRO_KEYS
    }
    aliases = aliases_for_description(description)
    search_text = " ".join([description, *aliases])
    conn.execute(
        """
        INSERT INTO foods(
            source_food_id, canonical_name, normalized_name, region, brand, preparation,
            calories, protein, carbs, fat, micronutrients_json, source_url, search_text
        ) VALUES (?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            source_id,
            description,
            normalize_name(description),
            macros["calories"],
            macros["protein"],
            macros["carbs"],
            macros["fat"],
            json.dumps(micros, sort_keys=True, separators=(",", ":")),
            f"https://fdc.nal.usda.gov/fdc-app.html#/food-details/{fdc_id}/nutrients",
            normalize_name(search_text),
        ),
    )
    for alias in aliases:
        conn.execute(
            "INSERT OR IGNORE INTO aliases(source_food_id,name,normalized_name,language_tag) VALUES(?,?,?,NULL)",
            (source_id, alias, normalize_name(alias)),
        )
    for portion in portions:
        conn.execute(
            """
            INSERT OR IGNORE INTO portions(
                source_food_id, amount, unit, grams, description, is_default
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                source_id,
                float(portion["amount"]),
                str(portion["unit"]),
                float(portion["grams"]),
                portion.get("description"),
                1 if portion.get("is_default") else 0,
            ),
        )
    return True


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(
    db_path: Path,
    dataset_version: str,
    record_count: int,
) -> Path:
    manifest = {
        "source": SOURCE_KIND,
        "datasetName": SOURCE_NAME,
        "datasetVersion": dataset_version,
        "sourceUrl": SOURCE_URL,
        "license": LICENSE,
        "attribution": ATTRIBUTION,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "recordCount": record_count,
        "sha256": sha256_file(db_path),
    }
    path = db_path.with_suffix(".manifest.json")
    path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def build_from_csv_roots(csv_roots: list[Path], output: Path, dataset_version: str) -> int:
    foods: dict[int, dict[str, str]] = {}
    nutrients: dict[int, dict[str, tuple[float, str]]] = {}
    portions: dict[int, list[dict[str, object]]] = {}

    for csv_root in csv_roots:
        source_foods = read_foods(csv_root)
        source_nutrients = read_nutrients(csv_root, source_foods)
        source_portions = read_portions(csv_root, source_foods)
        foods.update(source_foods)
        nutrients.update(source_nutrients)
        portions.update(source_portions)

    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    conn = sqlite3.connect(output)
    try:
        create_schema(conn)
        count = 0
        for fdc_id in sorted(foods):
            if write_food(
                conn,
                fdc_id,
                foods[fdc_id],
                nutrients.get(fdc_id, {}),
                portions.get(fdc_id, []),
            ):
                count += 1
        conn.commit()
        conn.execute("PRAGMA optimize")
    finally:
        conn.close()
    write_manifest(output, dataset_version, count)
    return count


def build_fixture(output: Path) -> int:
    """Synthetic rows test the schema/import path; values are not production nutrition data."""
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    conn = sqlite3.connect(output)
    try:
        create_schema(conn)
        rows = [
            (
                900000001,
                {"description": "Fixture banana, raw", "data_type": "foundation_food"},
                {
                    "calories": (100.0, "kcal"),
                    "protein": (1.0, "g"),
                    "carbs": (24.0, "g"),
                    "fat": (0.5, "g"),
                    "potassium": (350.0, "mg"),
                },
                [{"amount": 1.0, "unit": "medium", "grams": 120.0, "description": "1 medium", "is_default": True}],
            ),
            (
                900000002,
                {"description": "Fixture cooked rice", "data_type": "survey_fndds_food"},
                {
                    "calories": (130.0, "kcal"),
                    "protein": (2.5, "g"),
                    "carbs": (28.0, "g"),
                    "fat": (0.3, "g"),
                },
                [{"amount": 1.0, "unit": "cup", "grams": 160.0, "description": "1 cup", "is_default": True}],
            ),
        ]
        for fdc_id, meta, nutrients, portions in rows:
            write_food(conn, fdc_id, meta, nutrients, portions)
        conn.commit()
    finally:
        conn.close()
    write_manifest(output, "synthetic-fixture-do-not-ship", len(rows))
    return len(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        nargs="+",
        help="One or more official FDC CSV ZIPs/extracted directories (normally Foundation + FNDDS)",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--dataset-version", help="Pinned upstream release/version string")
    parser.add_argument("--fixture", action="store_true", help="Build tiny synthetic smoke-test database")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = args.output.resolve()

    if args.fixture:
        count = build_fixture(output)
        print(f"Wrote synthetic USDA fixture: {output} ({count} rows)")
        return 0

    if not args.input or not args.dataset_version:
        raise SystemExit("--input and --dataset-version are required unless --fixture is used")

    roots: list[Path] = []
    temps: list[tempfile.TemporaryDirectory[str]] = []
    try:
        for input_path in args.input:
            csv_root, temp = open_input(input_path.resolve())
            roots.append(csv_root)
            if temp is not None:
                temps.append(temp)
        count = build_from_csv_roots(roots, output, args.dataset_version.strip())
    finally:
        for temp in temps:
            temp.cleanup()
    manifest = output.with_suffix(".manifest.json")
    print(f"Wrote USDA index: {output} ({count} complete-macro rows)")
    print(f"Wrote manifest: {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
