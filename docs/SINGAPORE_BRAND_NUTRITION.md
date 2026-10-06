# Singapore official brand nutrition

The application now includes a small offline catalogue of official McDonald's Singapore menu facts:

| Product | kcal | Protein g | Carbs g | Fat g | Sodium mg |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hot Fudge Sundae | 314 | 6 | 49 | 10 | 168 |
| Vanilla Cone | 133 | 3 | 22 | 4 | 56 |
| Coca-Cola Zero Sugar (Small) | 0 | 0 | 0 | 0 | 29 |

Each record also includes only the published saturated fat, cholesterol and fibre values.
Sources checked 2026-10-07; the publisher dates the nutrition information December 2023:

- https://www.mcdonalds.com.sg/food-menu/hot-fudge-sundae
- https://www.mcdonalds.com.sg/food-menu/vanilla-cone
- https://www.mcdonalds.com.sg/food-menu/coca-cola-zero-small

These are serving-based records. Serving mass, caffeine, vitamins and other unpublished minerals remain unknown, rather than invented zeros. Grams cannot be converted to menu servings without published mass. Standard servings can be multiplied deterministically. Restaurant formulation and portion variation still apply.

Matching requires McDonald's identity and explicit Singapore/SG region, or a fully qualified McDonald's Singapore product name. Example input: `1 serving McDonald's Singapore Hot Fudge Sundae`. No global country default is inferred from language, device locale or user location. The catalogue participates at BRAND priority; Personal, SG FoodID, MyFCD, INDB and IFCT keep their existing higher priorities. Existing diary entries are not rewritten.

The seasonal Hershey's product is deliberately not aliased to the standard sundae: equivalence has not been established. KFC Singapore chicken, generic cashews, medium drinks, unspecified chicken cuts and unsupported products continue through the existing review/fallback flow. This catalogue is an initial Singapore reference set, not a complete SG FoodID import or personal daily-intake guideline.

Validation: `:app:testDebugUnitTest :app:lintRelease :app:assembleDebug -PworkoutVectors=none --offline` passed on 2026-10-07. All 426 JVM tests passed (four Singapore test methods), with no errors or failures. Release lint has zero errors, 597 existing warnings and 20 hints. Debug/release compilation and debug APK assembly passed. No device tests were run. An initial plural-serving regression was reproduced and repaired before the final successful run. The sandbox could not connect to the local Gradle daemon; validation succeeded outside it.

Files changed: FudAIApp.kt registers the source, SingaporeBrandNutritionSource.kt supplies the catalogue, RegionalNutritionEngine.kt warns when serving mass is unavailable, SingaporeBrandNutritionSourceTest.kt verifies matching/scaling/source priority/unknown values, and this document records scope and references.

Next priorities: replace the legacy gram placeholder with an explicit unknown-mass/serving model; add a user-selected regional preference; verify KFC Singapore products by chicken cut; expand official local brand data and licensed SG FoodID coverage. A mixed meal still follows the existing complete-resolution policy, so an unsupported component can trigger the clearly labelled AI fallback for the whole meal.
