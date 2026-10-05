# USDA FoodData Central nutrition index

The Android regional nutrition engine can optionally bundle a compact, read-only USDA FoodData
Central index for broad generic-food coverage.

This file documents the reproducible build. The generated SQLite database is intentionally not
hand-edited.

## Pinned upstream inputs

Verified against the USDA FoodData Central download page on 2026-10-04:

- Foundation Foods — April 2026 JSON (preferred)
  - archive: `FoodData_Central_foundation_food_json_2026-04-30.zip`
  - USDA currently lists about 459 KB zipped / 6.5 MB extracted.
- FNDDS — FNDDS 2021-2023, October 2024 JSON (preferred)
  - archive: `FoodData_Central_survey_food_json_2024-10-31.zip`
  - USDA currently lists about 3.7 MB zipped / 64 MB extracted.

The equivalent CSV releases remain supported by the importer:
- `FoodData_Central_foundation_food_csv_2026-04-30.zip`
- `FoodData_Central_survey_food_csv_2024-10-31.zip`

JSON is preferred for normal builds because USDA currently lists the FNDDS CSV at roughly 200 MB
zipped / 1.6 GB extracted. The JSON release carries the same FNDDS data type in a far smaller
build-time representation.

Official download page:
https://fdc.nal.usda.gov/download-datasets/

USDA's September 2026 inventory identifies FoodData Central v15.x as current, while the separate
Foundation bulk release remains April 2026 and FNDDS remains the 2021-2023 October 2024 release.
Do not replace these inputs with the huge Branded or all-data archive merely because it is newer;
Open Food Facts owns the branded-product role in this application.

## Build

Download the two official JSON archives yourself, then from the repository root run:

```bash
python scripts/build_usda_food_index.py \
  --input ~/Downloads/FoodData_Central_foundation_food_json_2026-04-30.zip \
          ~/Downloads/FoodData_Central_survey_food_json_2024-10-31.zip \
  --dataset-version foundation-2026-04+fndds-2021-2023
```

The importer also accepts the matching official CSV ZIPs/directories. JSON and CSV inputs may be
mixed, but duplicate FDC IDs across inputs fail the build rather than silently overwriting records.

Default outputs:

```text
android/app/src/main/assets/nutrition/usda/usda_foods.sqlite
android/app/src/main/assets/nutrition/usda/usda_foods.manifest.json
```

The manifest records:

- source and dataset version;
- official source URL;
- CC0 licence;
- USDA attribution;
- generation timestamp;
- record count;
- SHA-256 of the exact SQLite database.

The Android runtime verifies the SHA-256 before opening the copied database.

## Import policy

Only these upstream data types are accepted:

- `foundation_food`
- `survey_fndds_food`

Branded foods are deliberately excluded.

A record is eligible for automatic meal resolution only when the upstream rows contain all four:

- energy in kcal;
- protein;
- carbohydrate;
- fat.

The importer never uses Atwater factors to silently reconstruct missing energy. Incomplete rows are
skipped for automatic resolution rather than made to look database-authored.

Optional micronutrients remain optional. Missing values stay missing, not zero.

Both JSON and CSV reject conflicting repeated nutrient values for a food. Identical repeated
values are harmless. Published energy variants remain distinct and use the documented precedence.
Where CSV supplies `nutrient.csv` unit metadata, declared units must match the canonical nutrient
unit; incompatible or blank declared units are not silently converted or treated as grams.
Legacy CSV exports without that metadata column retain the stable nutrient-ID unit contract.

## Portions

USDA portion data are used only when USDA explicitly supplies a positive gram weight. In CSV this
comes from `food_portion.csv`; in JSON it comes from `foodPortions[].gramWeight`.

Common household measures such as cup, tablespoon, teaspoon, slice, piece, bowl, plate, container,
packet and can are normalised to the app's unit vocabulary.

Unknown source wording may be retained only as the record's default serving. This can support
`1 serving`, but must never be treated as evidence that an unknown measure equals a cup, piece or
other household unit.

The runtime still refuses density guesses such as converting an arbitrary cup to grams when the
source has no explicit cup portion.

## Search and aliases

The first release deliberately favours precision over fuzzy recall.

The importer creates conservative aliases from USDA descriptions, for example a leading food name
before descriptive comma-separated qualifiers. The runtime only promotes an offline search result
to an automatic nutrition match when the interpreted food name exactly matches the canonical name,
an alias or a stored translation after normalisation.

Approximate discovery can become more sophisticated later without weakening the final matching gate.

## Tests

The importer has a network-free regression suite:

```bash
python -m unittest -v scripts/test_build_usda_food_index.py
```

It covers:

- fixture database schema and manifest checksum;
- modern FDC nutrient IDs versus legacy nutrient numbers;
- Foundation source-authored energy IDs 2048/2047 versus FNDDS/standard 1008 energy;
- official FoundationFoods / SurveyFoods JSON object shapes and zipped JSON input;
- complete macro import;
- micronutrient preservation;
- explicit portion parsing;
- rejection of incomplete macro rows;
- merging Foundation + FNDDS inputs;
- exclusion of branded rows;
- conservative alias generation;
- FNDDS portion descriptions such as `1 fl oz` when measureUnit is undetermined;
- duplicate FDC IDs within CSV inputs and across JSON/CSV inputs;
- fractional/mixed-number portion amounts and primary-unit precedence;
- invalid explicit amounts are rejected instead of becoming one-unit portions;
- unknown portion wording.

The repository's `Quality checks` workflow includes this suite in a separate
`nutrition-importers` job.

## Synthetic fixture

For importer smoke-testing without USDA files:

```bash
python scripts/build_usda_food_index.py \
  --fixture \
  --output build/usda-fixture.sqlite
```

The fixture uses deliberately synthetic nutrition values and is labelled
`synthetic-fixture-do-not-ship`. It must never be copied into production Android assets.

## Updating USDA

Do not automatically switch to a new upstream release.

For every update:

1. confirm the new release on the official USDA page;
2. run the importer tests;
3. rebuild both SQLite and manifest;
4. inspect record count changes;
5. test representative generic foods and portions;
6. run Android unit tests and release lint;
7. update this document and the dataset version;
8. commit the database and manifest together.

A dataset update is a nutrition-behaviour change, not merely an asset refresh.
