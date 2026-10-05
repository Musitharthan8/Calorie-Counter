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
