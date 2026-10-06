# Nutrition validation checkpoint

## 5 October 2026

Baseline inspected: `b1242c606c4f685d17f82167b8317e4cb39ee5e5` on
`feature/regional-nutrition-engine`, draft PR #1.

### Executed locally

```bash
python -m unittest -v scripts/test_build_usda_food_index.py scripts/test_build_indb_food_index.py
```

All **25 tests passed** after the portion/candidate audit. The suite uses synthetic,
network-free fixtures; this does not establish coverage of a production USDA asset.
`git diff --check` passed.

New USDA regressions first reproduced these bugs:
- fractional `1/2 cup` and mixed `1 1/2 cups` were parsed as one cup;
- a secondary cup measure could override the primary piece measure;
- invalid explicit JSON portion amounts were replaced by one;
- the legacy CSV build entry point silently overwrote duplicate FDC IDs.

The importer now preserves fractional amounts and primary units, rejects invalid
amounts, and shares duplicate detection across the JSON/CSV build entry points.
The CSV fixture's unclosed file handle was also corrected.

### Android gate remains blocked

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease -PworkoutVectors=none
```

This command failed **before task execution** when downloading Gradle 9.8.0:
`java.net.SocketException: Network is unreachable`.
No Android compilation, unit-test, lint or APK success is claimed.

The new offline-source regression checks that name/brand filtering cannot hide a
full discovery page and bypass the truncation guard. It is committed but unrun.

### GitHub Actions gate remains blocked

The repository Actions page showed zero workflow runs. The connector also returned
no PR-triggered runs for the baseline commit. The Cloud Browser was signed out;
GitHub's sign-in page then returned `502 Bad Gateway / connection refused` before
credential entry. Actions was not enabled and no workflow was dispatched.

Keep PR #1 draft. When authenticated Actions access is available, run `Quality checks`
on the feature branch and inspect all web/importer/Android results and reports.
Fix failures before considering review readiness.

### Dataset status

No production USDA or INDB assets were generated or bundled in this audit.
SG FoodID, MyFCD and INDB redistribution licensing gates are unchanged.

## Follow-up CSV consistency audit, 5 October 2026

Baseline: `f7ba96483a0f9ef65bc6ea68f10680221d1f72be`.
The full importer suite executed successfully with **28 tests passed**, and
`git diff --check` passed after these changes:

- conflicting CSV nutrient values reject the food, independently of row order;
- identical repeated nutrient rows remain usable;
- declared CSV nutrient units are checked without inventing conversions;
- JSON and CSV share nutrient-unit normalization.

New regressions reproduced the CSV conflicts/unit gaps before the fix.
The Android and GitHub Actions blockers above remain unresolved; this follow-up
contains Python/import documentation changes only and does not establish Android validation.


## Mixed-meal micronutrient evidence audit, 5 October 2026

Current head at this checkpoint: `11f1199bc14ca1a04b02ed17d984874acc102e32`.

A correctness audit confirmed that Review Food previously stretched whole-meal micronutrients by
the ratio of total ingredient grams when component proportions changed. That is only valid when all
ingredients have identical micronutrient density, so changing rice/chicken proportions could produce
incorrect sodium/vitamin/source-nutrient totals.

The branch now:
- stores optional per-component micronutrient snapshots on `MealIngredient`;
- attaches those snapshots to evidence-backed resolver ingredients;
- recomputes a mixed meal from edited component values instead of stretching the old whole-meal total;
- sums a micronutrient only when every component provides that nutrient in the same unit;
- keeps missing component nutrient data unknown rather than treating it as zero;
- falls back to the legacy whole-meal stretch only for legacy/manual meals that never had component
  micronutrient evidence;
- clears parent micronutrients when previously available component evidence becomes incomplete,
  instead of resurrecting stale totals;
- scales component micronutrient evidence for pure portion edits;
- invalidates component micronutrient/provenance evidence when the user changes food identity or
  manually changes the component macros away from the source-backed proportional values;
- preserves component evidence/provenance through saved-food re-logging and Combine into Meal;
- preserves backward JSON compatibility because the new ingredient micronutrient field defaults to null.

New Kotlin regression tests cover:
- proportion changes with different rice/chicken micronutrient densities;
- missing component nutrients remaining unknown;
- legacy meals retaining the old proportional fallback;
- invalidated component evidence clearing parent micronutrients;
- component evidence scaling on a pure portion edit;
- semantic/name/macro edits invalidating source evidence;
- grounded resolver ingredients carrying component micronutrients;
- old ingredient JSON decoding without the new field;
- combined meals preserving complete component evidence and refusing partial micronutrient totals.

These Android/Kotlin tests are committed but **have not executed successfully in this environment**.
The Android Gradle gate remains blocked before task execution by the unavailable Gradle distribution.
GitHub still reports zero workflow runs on the feature branch, and the connector exposes inspection /
rerun of existing runs but not initial workflow enable/dispatch. The Actions permissions endpoint is
also outside the connector's allowed GET surface.

The latest successfully executed importer validation remains:
- `python -m unittest -q scripts/test_build_usda_food_index.py scripts/test_build_indb_food_index.py`
- **28 tests passed**
- `git diff --check` passed at that earlier importer checkpoint.

Do not interpret this section as an Android pass. PR #1 must remain draft until Android compile,
unit tests and lint execute successfully.


## Cancellation and component-edit follow-up, 5 October 2026

Current head at this checkpoint: `525045b664d74fb1b7ee91999873d3c14feecae9`.

A cancellation audit found that optional serving-unit repair used `runCatching` around a suspend AI
call. Because `runCatching` catches `CancellationException`, canceling during that repair could be
converted into an empty repair result and allow the surrounding food/label analysis to continue.

The branch now:
- uses a cancellation-aware `bestEffortServingUnitRepair` helper;
- rethrows `CancellationException`;
- still treats ordinary repair/provider failures as optional and continues without repaired units;
- preserves cancellation through the shared `RegionalNutritionEngine.analyzeInterpretation`
  fallback path;
- keeps the existing resolver and Open Food Facts cancellation rethrow behavior.

New unexecuted Kotlin regressions cover ordinary repair failure versus cancellation and cancellation
from the shared unresolved-input fallback.

The component evidence audit also tightened manual ingredient edits:
- pure proportional serving edits may scale and retain component source evidence while marking it
  `userEdited`;
- food-name or manual macro changes invalidate component micronutrient/provenance evidence;
- once grounded component evidence becomes incomplete, stale parent micronutrients are cleared
  rather than falling back to legacy whole-meal stretching;
- the Review source-nutrient row now renders from the current editable map, not the original
  analysis map.

A wider sweep confirmed `NutritionResolver` and Open Food Facts explicitly rethrow coroutine
cancellation. Remaining `runCatching` uses in the inspected offline paths are synchronous JSON,
file-close, or asset-probe operations rather than suspend-call cancellation boundaries.

Validation status is unchanged:
- the last executed importer suite remains **28 passing tests**;
- these newer Kotlin tests have not run;
- Android compilation/unit/lint remain blocked before Gradle task execution;
- GitHub still reports zero workflow runs and the connector still has no initial workflow
  enable/dispatch operation.

PR #1 must remain draft.


## Display-rounded component evidence regression, 6 October 2026

Claude's static audit identified a boundary bug in `MealIngredient.withUserEdits`.

The ingredient editor displays non-integer macros through `MacroValueFormatter.string`, which uses
one decimal place. A pure portion change can therefore turn an exact proportional value such as
`0.75 g` fat into editable text `0.8`. The previous evidence check allowed a flat `0.05 g`
difference. In binary floating point, `0.8 - 0.75` can evaluate to
`0.05000000000000004`, just above that boundary.

That caused a valid pure portion edit to be misclassified as a manual macro edit. The component's
micronutrient snapshot and provenance were then dropped, which safely made mixed-meal parent
micronutrients unknown but lost valid evidence unnecessarily.

The branch now uses a display-rounding tolerance of:

`0.05 + 1e-9`

while retaining the existing relative tolerance for larger values.

New regression tests cover:
- 100 g → 150 g Rice where exact fat becomes 0.75 g and displayed/editable fat is 0.8 g:
  source evidence remains and sodium scales from 3.0 mg to 4.5 mg;
- changing the displayed fat to 0.9 g still invalidates component micronutrients/provenance;
- renaming the component to Tofu still invalidates component micronutrients/provenance.

These tests are committed in `MealIngredientEvidenceTest.kt` but have **not executed** because the
Android Gradle gate remains unavailable. This does not change the last executed validation result:
the latest actually run importer suite remains **28 passing tests**.

PR #1 remains draft.
