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
## Component evidence and diary round-trip follow-up, 6 October 2026

Static continuation of the mixed-meal evidence audit found two additional data-integrity gaps.

### Newly introduced component evidence

Both the model edit helper and the first-time Review Food sheet previously cleared stale parent
micronutrients only when the **old** ingredient list already contained component-level evidence.
That missed a legacy/manual meal where a newly added grounded ingredient introduced evidence for
the first time while the remaining legacy ingredients still had no component snapshots.

In that case the component total is incomplete, so stretching the old meal-level micronutrients
across the new mixed list is not defensible. The branch now checks evidence on both the previous and
edited ingredient sets and clears parent micronutrients/source nutrients when full component
coverage is unavailable.

`MealMicronutrientStretchTest` now includes an unexecuted regression where a 100 g legacy rice
entry with stale sodium/phosphorus gets a grounded chicken component added. The safe result keeps
the edited macros/weight but clears the unsupported parent micronutrients instead of stretching
them.

The existing Edit Food sheet delegates to the same model helper, so the model fix covers saved-entry
edits as well as the explicit matching guard in Review Food.

### Diary JSON evidence preservation

The shareable/importable diary JSON format previously preserved legacy nutrient columns and basic
ingredient macros but discarded newer grounding evidence. Export -> import could therefore lose:

- `sourceNutrients`;
- parent `nutritionProvenance` and `nutritionWarnings`;
- per-ingredient `nutritionProvenance`;
- per-ingredient micronutrient snapshots.

JSON export format `1.6` now carries those fields. They all have defaults on import, so older 1.x
diary exports remain accepted by the existing major-version compatibility check.

The replace-date-range merge path was also corrected. It still preserves local-only media from the
matched existing entry, but nutrition evidence now comes from the imported entry rather than
silently retaining stale pre-import `sourceNutrients`, provenance, or warnings.

New unexecuted regressions cover:
- grounded parent and ingredient evidence surviving JSON export -> parse;
- replace-date-range using imported evidence while preserving the existing local image.

### Remaining static audit

The previously uninspected paths were also read at the live branch state:

- `CombinedMeal.kt` recomputes complete component micronutrients and clears stale parent values when
  evidence becomes incomplete;
- `HomeViewModel.saveAnalysis` carries edited ingredients, source nutrients and provenance into the
  saved `FoodEntry`;
- `PersonalFoodSource` preserves saved parent micronutrients plus original single-source dataset
  metadata when a personal food is reused;
- `FoodEntry.duplicatedForLogging` retains ingredients, source nutrients and provenance.

No additional change was required in those paths during this pass.

### Validation status

These Kotlin changes and regressions are committed but **have not executed**. GitHub still reports
zero workflow runs/status checks for the branch, and the available connector can inspect or rerun an
existing Actions run but cannot create the first run. Android compile, unit tests and lint therefore
remain unvalidated.

The latest actually executed validation remains the earlier importer checkpoint: **28 Python tests
passed**. PR #1 remains draft.

## Executed Android audit gate, 6 October 2026

This checkpoint supersedes the unexecuted Android status above. GitHub PR #1 and a direct remote
branch check both identified `b2229af542bce44b47c2d13aafe1a7595b4526eb` as the live starting head,
12 commits beyond `e97e8a28bcfe175eb8ca50401f05f3a47d4022d1`. All 12 commits were audited through
their production, test and documentation diffs. The landed rounding changes in `3e61b4c`,
`102a391` and `3eef0d4` were not reapplied or modified.

The continuation fix is `343cc7c1e816133949689709d52faeab24fd5f8e`:

- JSON item/component nutrition and weights now retain stored precision. Display rounding had
  changed 0.75 g fat to 0.8 g and could erase a 0.04 g component weight while retaining its exact
  micronutrient evidence. Three export/import cycles now preserve the tested values exactly.
- Date-range replacement clears stale serving controls, interpretation and product metadata absent
  from diary JSON. Imported nutrition/evidence and existing local parent media remain preserved.
- Invalid source/component `NutrientAmount` constructor inputs now produce `DiaryImportException`,
  matching malformed JSON, rather than leaking a generic `IllegalArgumentException`.
- Resolution and component aggregation retain source nutrients whose units cannot populate a
  legacy typed field. Such nutrients stay in `sourceNutrients` with their authored units; no hidden
  conversion or incompatible unit mapping is introduced.

Five added tests reproduced these defects before production fixes. The targeted pre-fix run had
39 tests and six failures: five new regressions plus an existing invalid legacy source fixture.
All three landed `MealIngredientEvidenceTest` rounding/identity cases passed unchanged.

Actual execution also uncovered and repaired validation problems:

- Four photo-test lambdas returned JUnit `fail`'s `Unit` where typed results were required. They now
  throw `AssertionError` and the test source compiles.
- The old-entry serialization fixture now uses the existing persisted `textInput` source value,
  instead of the unsupported `text` value. FoodSource's production contract was not changed.
- Local parser tests use test-only `org.json:json:20260814`, since the SDK's JSONObject stubs throw
  in JVM tests. No JSON library was added to the application runtime.
- Importer tests explicitly close SQLite read connections, fixing Windows fixture cleanup errors.

### Commands and measured results

Executed on Windows with portable Temurin Java 17.0.20, the repository Gradle 9.8.0 wrapper,
Android platform 37.2 and required build tools. Tooling/caches remain outside the repository under
the task workspace. A sparse checkout included the workout manifest and the eight real squat images
needed by the existing corpus test; no workout test was skipped or weakened.

```powershell
.\android\gradlew.bat -p android :app:compileDebugKotlin :app:testDebugUnitTest :app:lintRelease -PworkoutVectors=none
python -m unittest -v scripts/test_build_usda_food_index.py scripts/test_build_indb_food_index.py
git diff --check
```

Results:

- Android command: **BUILD SUCCESSFUL**. Debug and release Kotlin/Java compilation completed during
  the validation runs; unchanged compile tasks were up-to-date in the final run.
- Android JVM suite: **422 tests, 76 classes, zero failures, errors or skips**.
- Release lint: **zero errors/fatal findings**, **597 warnings and 20 hints**. Warnings were not
  suppressed or baselined to obtain this result. Gradle/Kotlin deprecation warnings remain.
- USDA/INDB synthetic importer suite: **28 tests passed**.
- Whitespace check: passed.

Initial attempts failed because Java/SDK were absent, then exposed test compilation, JSON stub and
Windows SQLite cleanup problems. Those blockers were resolved. The intermediate full Android run
had five failures (four JSON-stub failures and one missing sparse-checkout image fixture); all were
resolved before the final passing gate.

No emulator/device instrumentation, APK assembly/signing, production dataset matching, or real
SG FoodID/MyFCD/INDB redistribution validation is claimed. GitHub reported zero Actions runs at
the starting checkpoint; local passing execution is not a green GitHub Actions run. PR #1 remains
draft pending remote CI and review.

The architecture and source hierarchy remain:

`MealInterpretation -> ambiguity -> NutritionResolver -> deterministic scaling -> provenance -> review`

`Personal -> SG FoodID -> MyFCD -> INDB/IFCT -> official brand -> OFF -> USDA -> AI last`

### Next priorities

1. Obtain a remote Quality checks run for this branch before merge.
2. Exercise review/edit and JSON replacement flows on an Android device/emulator; extend diary
   interchange to preserve serving/product/interpretation metadata explicitly if that fidelity is needed.
3. Build and inspect a real USDA Foundation/FNDDS data pack; synthetic importer tests do not establish
   production matching quality or coverage.
4. Verify first-party redistribution rights before bundling SG FoodID, MyFCD or INDB.
5. Triage lint warnings and compiler/Gradle deprecations, then continue clarification/photo UX and
   safe composite-meal modelling without weakening ambiguity or evidence guards.
