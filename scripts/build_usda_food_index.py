#!/usr/bin/env python3
"""Build a compact USDA FoodData Central Foundation + FNDDS nutrition index.

This script intentionally has no third-party Python dependencies and does not download data.
Give it official FoodData Central Foundation/FNDDS JSON ZIPs/files (preferred) or CSV ZIPs/directories.

Preferred, much smaller official JSON inputs:
  python scripts/build_usda_food_index.py \
      --input ~/Downloads/FoodData_Central_foundation_food_json_2026-04-30.zip \
              ~/Downloads/FoodData_Central_survey_food_json_2024-10-31.zip \
      --dataset-version foundation-2026-04+fndds-2021-2023

CSV remains supported for reproducibility/backward compatibility:
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
import math
import re
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
JSON_ROOT_DATA_TYPES = {
    "FoundationFoods": "foundation_food",
    "SurveyFoods": "survey_fndds_food",
}
JSON_DATA_TYPE_NAMES = {
    "foundation_food": "Foundation",
    "survey_fndds_food": "Survey (FNDDS)",
}
# Foundation JSON may publish measured/calculated energy as 2048/2047 rather than 1008.
# These are source-authored kcal values, not energy reconstructed by this importer.
ENERGY_ID_PRECEDENCE = (1008, 2048, 2047)
MAX_ARCHIVE_MEMBERS = 512
MAX_ARCHIVE_EXPANDED_BYTES = 4 * 1024 * 1024 * 1024
MAX_ARCHIVE_COMPRESSION_RATIO = 500

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
# Legacy USDA nutrient numbers are a different namespace from modern FDC IDs.
LEGACY_NUTRIENTS = {old: NUTRIENTS[new] for old, new in {
    208: 1008, 203: 1003, 205: 1005, 204: 1004, 291: 1079, 269: 2000,
    606: 1258, 645: 1292, 646: 1293, 601: 1253, 307: 1093, 306: 1092,
    605: 1257, 301: 1087, 303: 1089, 304: 1090, 309: 1095, 320: 1106,
    401: 1162, 328: 1114, 418: 1178, 323: 1109, 430: 1185, 435: 1177,
}.items()}

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
    (re.compile(r"\bfl\s*\.?\s*oz\b", re.I), "fl oz"),
    (re.compile(r"\bounces?\b|\boz\b", re.I), "oz"),
    (re.compile(r"\bglasses?\b", re.I), "glass"),
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


def _finite_nonnegative(value: object) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) and parsed >= 0 else None


def _validated_zip_infos(path: Path, archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    infos = [info for info in archive.infolist() if not info.is_dir()]
    if len(infos) > MAX_ARCHIVE_MEMBERS:
        raise SystemExit(f"USDA archive has too many files: {path}")
    expanded = 0
    for info in infos:
        expanded += info.file_size
        if expanded > MAX_ARCHIVE_EXPANDED_BYTES:
            raise SystemExit(f"USDA archive expands beyond supported size: {path}")
        if info.file_size > 0:
            if info.compress_size <= 0:
                raise SystemExit(f"USDA archive has invalid compressed member: {info.filename}")
            if info.file_size / info.compress_size > MAX_ARCHIVE_COMPRESSION_RATIO:
                raise SystemExit(f"USDA archive compression ratio is unsafe: {info.filename}")
    return infos


def _safe_extract_zip(path: Path, destination: Path) -> None:
    base = destination.resolve()
    with zipfile.ZipFile(path) as archive:
        infos = _validated_zip_infos(path, archive)
        for info in infos:
            target = (base / info.filename).resolve()
            if target != base and base not in target.parents:
                raise SystemExit(f"USDA archive contains unsafe path: {info.filename}")
        archive.extractall(base)


def _json_member(path: Path) -> str | None:
    if path.suffix.lower() == ".json":
        return ""
    if not path.is_file() or not zipfile.is_zipfile(path):
        return None
    with zipfile.ZipFile(path) as archive:
        members = [
            info.filename for info in _validated_zip_infos(path, archive)
            if info.filename.lower().endswith(".json")
        ]
    if len(members) > 1:
        raise SystemExit(f"Expected at most one JSON file in {path}; found {len(members)}")
    return members[0] if members else None


def _load_json_document(path: Path) -> dict[str, object]:
    member = _json_member(path)
    if member is None:
        raise SystemExit(f"No USDA JSON document found in {path}")
    try:
        if member == "":
            with path.open(encoding="utf-8-sig") as fh:
                value = json.load(fh)
        else:
            with zipfile.ZipFile(path) as archive:
                with archive.open(member) as raw:
                    import io
                    with io.TextIOWrapper(raw, encoding="utf-8-sig") as fh:
                        value = json.load(fh)
    except (OSError, UnicodeError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Unable to read USDA JSON input {path}: {error}") from error
    if not isinstance(value, dict):
        raise SystemExit(f"USDA JSON input must contain a top-level object: {path}")
    return value


def _normalize_nutrient_unit(value: object) -> str:
    raw = str(value or "").strip().lower()
    return {
        "µg": "ug",
        "μg": "ug",
        "ug": "ug",
        "mcg": "ug",
        "g": "g",
        "mg": "mg",
        "kcal": "kcal",
    }.get(raw, raw)


def _json_nutrients(item: dict[str, object]) -> dict[str, tuple[float, str]]:
    result: dict[str, tuple[float, str]] = {}
    energy: dict[int, float] = {}
    raw_entries = item.get("foodNutrients")
    if not isinstance(raw_entries, list):
        return result

    for raw_entry in raw_entries:
        if not isinstance(raw_entry, dict):
            continue
        raw_nutrient = raw_entry.get("nutrient")
        if not isinstance(raw_nutrient, dict):
            continue
        try:
            nutrient_id = int(raw_nutrient.get("id"))
        except (TypeError, ValueError):
            continue
        amount = _finite_nonnegative(raw_entry.get("amount"))
        if amount is None:
            continue
        unit = _normalize_nutrient_unit(raw_nutrient.get("unitName"))

        if nutrient_id in ENERGY_ID_PRECEDENCE:
            if unit == "kcal":
                existing = energy.get(nutrient_id)
                if existing is not None and existing != amount:
                    raise ValueError(f"Conflicting USDA energy values for nutrient {nutrient_id}")
                energy[nutrient_id] = amount
            continue

        mapped = NUTRIENTS.get(nutrient_id)
        if mapped is None:
            continue
        key, expected_unit = mapped
        if unit != expected_unit:
            continue
        existing = result.get(key)
        candidate = (amount, expected_unit)
        if existing is not None and existing != candidate:
            raise ValueError(f"Conflicting USDA nutrient values for {key}")
        result[key] = candidate

    for nutrient_id in ENERGY_ID_PRECEDENCE:
        if nutrient_id in energy:
            result["calories"] = (energy[nutrient_id], "kcal")
            break
    return result


# Fraction alternatives must precede decimals so "1/2" cannot be read as "1".
PORTION_AMOUNT_PREFIX = re.compile(
    r"^\s*([+-]?(?:\d+\s+\d+\s*/\s*\d+|\d+\s*/\s*\d+|\d+(?:\.\d+)?))(?=\s|$)"
)


def _leading_portion_amount(description: str) -> float | None:
    match = PORTION_AMOUNT_PREFIX.match(description)
    if match is None:
        return None
    token = match.group(1)
    try:
        if "/" in token:
            numerator_text, denominator_text = token.split("/", 1)
            parts = numerator_text.split()
            if len(parts) == 2:
                result = float(parts[0]) + float(parts[1]) / float(denominator_text)
            else:
                result = float(parts[0]) / float(denominator_text)
        else:
            result = float(token)
    except (ValueError, ZeroDivisionError):
        return None
    return result if math.isfinite(result) and result > 0 else None


def _json_portions(item: dict[str, object]) -> list[dict[str, object]]:
    raw_portions = item.get("foodPortions")
    if not isinstance(raw_portions, list):
        return []

    portions: list[dict[str, object]] = []
    for raw in raw_portions:
        if not isinstance(raw, dict):
            continue
        grams = _finite_nonnegative(raw.get("gramWeight"))
        if grams is None or grams <= 0:
            continue

        description = str(raw.get("portionDescription") or raw.get("modifier") or "").strip()
        measure = raw.get("measureUnit")
        measure_name = ""
        measure_abbreviation = ""
        if isinstance(measure, dict):
            measure_name = str(measure.get("name") or "").strip()
            measure_abbreviation = str(measure.get("abbreviation") or "").strip()
        measure_text = " ".join(
            text for text in (measure_abbreviation, measure_name)
            if text and text.lower() != "undetermined"
        )

        raw_amount = raw.get("amount")
        if raw_amount is not None:
            amount = _finite_nonnegative(raw_amount)
            if amount is None or amount <= 0:
                continue  # An invalid explicit amount must not become an invented one-unit portion.
        else:
            amount = _leading_portion_amount(description)
            if amount is None:
                if re.match(r"^\s*[+-]?\d", description):
                    continue  # Includes zero, negative, malformed and zero-denominator quantities.
                amount = 1.0  # Source measure without a stated count describes one such measure.

        unit = portion_unit(measure_text, description, str(raw.get("modifier") or ""))
        if unit is None:
            # Keep an unknown source measure only as an exact, source-authored label. It will not
            # match cup/piece/etc. unless the interpreter emits the same normalized wording.
            stripped = PORTION_AMOUNT_PREFIX.sub("", description).strip()
            unit = (stripped or measure_text)[:48] or None
        if not unit:
            continue

        portions.append(
            {
                "amount": amount,
                "unit": unit,
                "grams": grams,
                "description": description or None,
            }
        )

    unique = {
        (str(p["unit"]), float(p["amount"]), float(p["grams"])): p
        for p in portions
    }
    result = list(unique.values())
    for portion in result:
        portion["is_default"] = len(result) == 1
    return result


def read_json_dataset(
    path: Path,
) -> tuple[dict[int, dict[str, str]], dict[int, dict[str, tuple[float, str]]], dict[int, list[dict[str, object]]]]:
    document = _load_json_document(path)
    roots = [root for root in JSON_ROOT_DATA_TYPES if root in document]
    if len(roots) != 1:
        raise SystemExit(
            f"Expected exactly one supported USDA JSON root in {path}; found {roots or 'none'}"
        )
    root = roots[0]
    data_type = JSON_ROOT_DATA_TYPES[root]
    records = document.get(root)
    if not isinstance(records, list):
        raise SystemExit(f"USDA JSON root {root} must be an array: {path}")

    foods: dict[int, dict[str, str]] = {}
    nutrients: dict[int, dict[str, tuple[float, str]]] = {}
    portions: dict[int, list[dict[str, object]]] = {}
    expected_data_type = JSON_DATA_TYPE_NAMES[data_type]

    for raw_item in records:
        if not isinstance(raw_item, dict):
            continue
        declared = raw_item.get("dataType")
        if declared is not None and str(declared) != expected_data_type:
            continue
        try:
            fdc_id = int(raw_item.get("fdcId"))
        except (TypeError, ValueError):
            continue
        if fdc_id <= 0:
            continue
        description = str(raw_item.get("description") or "").strip()
        if not description:
            continue
        if fdc_id in foods:
            raise SystemExit(f"Duplicate USDA FDC ID {fdc_id} in {path}")

        try:
            parsed_nutrients = _json_nutrients(raw_item)
        except ValueError:
            # A conflicting published nutrient row is unsafe for automatic resolution.
            continue

        foods[fdc_id] = {"description": description, "data_type": data_type}
        nutrients[fdc_id] = parsed_nutrients
        portions[fdc_id] = _json_portions(raw_item)

    return foods, nutrients, portions


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
    _safe_extract_zip(path, Path(temp.name))
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
            if fdc_id <= 0:
                continue
            if description:
                if fdc_id in result:
                    raise SystemExit(f"Duplicate USDA FDC ID {fdc_id} in {csv_root}")
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


def read_nutrient_units(csv_root: Path) -> dict[int, str]:
    """Validate declared CSV units when metadata exists; never guess a conversion."""
    path = csv_root / "nutrient.csv"
    if not path.exists():
        return {}
    result: dict[int, str] = {}
    with path.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            if "unit_name" not in row:
                continue  # Older exports may omit the metadata column entirely.
            try:
                nutrient_id = int(row["id"])
            except (KeyError, TypeError, ValueError):
                continue
            unit = _normalize_nutrient_unit(row.get("unit_name"))
            if nutrient_id in result and result[nutrient_id] != unit:
                raise SystemExit(f"Conflicting USDA units for nutrient {nutrient_id}")
            result[nutrient_id] = unit
    return result


def read_nutrients(
    csv_root: Path,
    foods: dict[int, dict[str, str]],
) -> dict[int, dict[str, tuple[float, str]]]:
    id_to_nbr = read_nutrient_id_map(csv_root)
    declared_units = read_nutrient_units(csv_root)
    conflicted_foods: set[int] = set()
    result: dict[int, dict[str, tuple[float, str]]] = defaultdict(dict)
    energy_candidates: dict[int, dict[int, float]] = defaultdict(dict)
    with (csv_root / "food_nutrient.csv").open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            try:
                fdc_id = int(row["fdc_id"])
                raw_id = int(row["nutrient_id"])
                amount = float(row["amount"])
            except (KeyError, TypeError, ValueError):
                continue
            if fdc_id not in foods or not math.isfinite(amount) or amount < 0:
                continue

            if raw_id in ENERGY_ID_PRECEDENCE:
                if raw_id in declared_units and declared_units[raw_id] != "kcal":
                    continue
                existing = energy_candidates[fdc_id].get(raw_id)
                if existing is not None and existing != amount:
                    conflicted_foods.add(fdc_id)
                energy_candidates[fdc_id][raw_id] = amount
                continue

            # Modern FDC IDs and legacy nutrient numbers are separate namespaces.
            mapped = NUTRIENTS.get(raw_id)
            legacy_number = id_to_nbr.get(raw_id, raw_id)
            if mapped is None:
                mapped = LEGACY_NUTRIENTS.get(legacy_number)
            if mapped is None:
                continue
            key, unit = mapped
            if raw_id in declared_units and declared_units[raw_id] != unit:
                continue
            if key == "calories":
                # Legacy 208 is lower priority than published modern FDC kcal fields.
                existing = energy_candidates[fdc_id].get(208)
                if existing is not None and existing != amount:
                    conflicted_foods.add(fdc_id)
                energy_candidates[fdc_id][208] = amount
            else:
                candidate = (amount, unit)
                existing = result[fdc_id].get(key)
                if existing is not None and existing != candidate:
                    conflicted_foods.add(fdc_id)
                result[fdc_id][key] = candidate

    for fdc_id, candidates in energy_candidates.items():
        for nutrient_id in (*ENERGY_ID_PRECEDENCE, 208):
            if nutrient_id in candidates:
                result[fdc_id]["calories"] = (candidates[nutrient_id], "kcal")
                break
    # JSON rejects conflicting nutrient rows for an entire food; CSV must not depend on row order.
    for fdc_id in conflicted_foods:
        result.pop(fdc_id, None)
    return result


def portion_unit(*texts: str) -> str | None:
    # Prefer the source's primary measure over later description/modifier alternatives.
    # Within a description, use the first unit, not the first regex in a global priority list.
    for text in texts:
        matches = [(match.start(), order, unit)
                   for order, (pattern, unit) in enumerate(UNIT_PATTERNS)
                   if (match := pattern.search(text)) is not None]
        if matches:
            return min(matches)[2]
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
            if fdc_id not in foods or not math.isfinite(grams) or not math.isfinite(amount) or grams <= 0 or amount <= 0:
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
        # seq_num is ordering, not evidence of a default serving. Keep all alternatives so
        # truncation cannot erase ambiguity. Only one distinct portion permits a default.
        unique = {(p["unit"], p["amount"], p["grams"]): p for p in portions}
        portions[:] = unique.values()
        for portion in portions:
            portion["is_default"] = len(portions) == 1
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
            is_derived INTEGER NOT NULL DEFAULT 0,
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
    if any(not math.isfinite(value) or value < 0 for value in macros.values()):
        return False

    description = (meta.get("description") or "").strip()
    source_id = str(fdc_id)
    micros = {
        key: {"amount": amount, "unit": unit}
        for key, (amount, unit) in nutrients.items()
        if key not in MACRO_KEYS and math.isfinite(amount) and amount >= 0
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
                source_food_id, amount, unit, grams, description, is_default, is_derived
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                source_id,
                float(portion["amount"]),
                str(portion["unit"]),
                float(portion["grams"]),
                portion.get("description"),
                1 if portion.get("is_default") else 0,
                1 if portion.get("is_derived") else 0,
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


def build_from_inputs(input_paths: list[Path], output: Path, dataset_version: str) -> int:
    foods: dict[int, dict[str, str]] = {}
    nutrients: dict[int, dict[str, tuple[float, str]]] = {}
    portions: dict[int, list[dict[str, object]]] = {}
    temps: list[tempfile.TemporaryDirectory[str]] = []

    def merge(
        source_foods: dict[int, dict[str, str]],
        source_nutrients: dict[int, dict[str, tuple[float, str]]],
        source_portions: dict[int, list[dict[str, object]]],
        source_path: Path,
    ) -> None:
        duplicates = foods.keys() & source_foods.keys()
        if duplicates:
            sample = ", ".join(str(value) for value in sorted(duplicates)[:5])
            raise SystemExit(f"Duplicate USDA FDC IDs across inputs ({sample}) while reading {source_path}")
        foods.update(source_foods)
        nutrients.update(source_nutrients)
        portions.update(source_portions)

    try:
        for input_path in input_paths:
            resolved = input_path.resolve()
            json_member = _json_member(resolved) if resolved.is_file() else None
            if resolved.suffix.lower() == ".json" or json_member is not None:
                merge(*read_json_dataset(resolved), source_path=resolved)
                continue

            csv_root, temp = open_input(resolved)
            if temp is not None:
                temps.append(temp)
            source_foods = read_foods(csv_root)
            merge(
                source_foods,
                read_nutrients(csv_root, source_foods),
                read_portions(csv_root, source_foods),
                source_path=resolved,
            )

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
    finally:
        for temp in temps:
            temp.cleanup()


def build_from_csv_roots(csv_roots: list[Path], output: Path, dataset_version: str) -> int:
    # One merge/build path keeps duplicate-ID checks identical for JSON and legacy CSV callers.
    return build_from_inputs(csv_roots, output, dataset_version)


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
        help="Official FDC Foundation/FNDDS JSON files/ZIPs (preferred) or CSV ZIPs/directories",
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

    count = build_from_inputs(args.input, output, args.dataset_version.strip())
    manifest = output.with_suffix(".manifest.json")
    print(f"Wrote USDA index: {output} ({count} complete-macro rows)")
    print(f"Wrote manifest: {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
