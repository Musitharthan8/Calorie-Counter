# Regional Nutrition Engine: external research and roadmap

This note records architecture ideas and data-source findings for the Calorie-Counter fork.
It is intentionally kept separate from third-party code. The project remains MIT-derived from Fud AI;
GPL/AGPL projects below are used as design references only unless their licences are explicitly compatible.

## Product rule

The app should understand a human meal description first, then resolve nutrition from evidence.
A model may identify food, wording, relationships and uncertainty. It should not silently become the
nutrition database.

Target flow:

```
text / voice / photo
    -> structured MealInterpretation
    -> high-impact clarification (only when needed)
    -> source resolver
       personal -> regional -> brand -> Open Food Facts -> USDA -> explicit AI estimate
    -> deterministic scaling / totals
    -> review with provenance + uncertainty
    -> log immutable snapshot
```

## Useful projects reviewed

### Chompass (MIT, Fud AI derivative)
https://github.com/fitguyfitguy/chompass

Useful patterns:
- Open Food Facts full-text product search with Search-a-licious and a legacy fallback.
- Per-request bounds, retry discipline and rate-limit handling instead of treating a public API as unlimited.
- Compact read-only USDA SQLite assets for fast/offline generic-food lookup.
- Source isolation: one failed database does not hide results from another.
- Food-accuracy benchmark tooling and explicit grounding paths.

Action for this fork:
- Implement OFF as a conservative resolver source (started in this branch).
- Next, build our own compact USDA/FNDDS SQLite import pipeline from CC0 upstream data. Do not copy
  Chompass implementation wholesale; use the published data and our own schema.

### Nut AI (AGPL-3.0-or-later)
https://github.com/SobhnathxLuffy/nut-ai

Architecture reference only unless licensing strategy changes.

Useful patterns:
- The model is perception; deterministic code owns user-visible nutrition numbers.
- Portion reconciliation is a ladder rather than one model guess.
- Show assumptions and uncertainty instead of fake precision.
- Rank clarification questions by expected nutritional impact and cap interruptions.
- Remember repeated answers after several confirmations (simple frequency memory, not model training).
- Use a weighed golden set to measure confidence rather than trusting model self-confidence.

Action for this fork:
- Add an ImpactQuestionPolicy so only the highest-value 1-2 ambiguities interrupt a log.
- Add PersonalFoodMemory for stable answers such as usual kopi style, stall serving size, or recipe.
- Later add an evaluation harness with kitchen-scale ground truth and category-level error bands.

### OpenNutriTracker + OpenNutriTracker-Backend (GPL-3.0 app; backend/data pipeline is a reference)
https://github.com/simonoppowa/OpenNutriTracker
https://github.com/simonoppowa/OpenNutriTracker-Backend

Useful patterns:
- One canonical food schema over multiple national databases.
- Source metadata and attribution are first-class.
- USDA FoodData Central can be used as CC0 generic-food coverage.
- Backend currently documents BLS (CC BY 4.0), Anuvaad INDB (CC BY 4.0) and TBCA Brazil as reusable
  sources in addition to USDA.
- Search-a-licious is primary for OFF text search; full product hydration can happen separately.

Action for this fork:
- Define an import-time CanonicalFoodRecord shared by every offline source.
- Keep source, source food id, licence/attribution and dataset version beside every record.
- Prefer reproducible import scripts over hand-edited nutrition JSON.

### FoodSAM (Apache-2.0)
https://github.com/jamesjg/FoodSAM

Useful pattern:
- Segment separate foods before identification so a mixed plate is not treated as one blob.

Caution:
- The published stack is heavyweight (SAM + semantic segmenter + detector). It is better treated as
  an optional research/server pipeline than the first mobile implementation.

Action for this fork:
- Keep photo interpretation contract component-based now.
- Evaluate lighter segmentation/on-device models later; do not block text/database work on vision R&D.


### FoodOn (CC BY 4.0)
https://github.com/FoodOntology/foodon

Useful pattern:
- A neutral controlled vocabulary plus synonym table can help map aliases and translations without
  flattening culturally distinct foods into generic labels.

Action for this fork:
- Consider FoodOn only as an alias/ontology aid, never as a nutrition source.
- Keep local culturally specific names as first-class labels; ontology ids can be optional crosswalks.
- Do not let an ontology mapping overwrite the user's original wording.

### Ingredient Parser / Recipe Scrapers (MIT)
https://github.com/strangetom/ingredient-parser
https://github.com/recipe-scrapers/recipe-scrapers

Useful pattern:
- Recipe URLs and ingredient lines can become structured quantities, units, ingredient names and yields.

Action for this fork:
- Future "Import recipe from link" flow: fetch/parse Schema.org Recipe data, parse each ingredient line,
  resolve each ingredient through the same nutrition engine, calculate the full recipe, then divide by
  explicit yield/servings and save as a Personal recipe.
- Do not embed Python/Node runtimes in the Android app just to reuse these libraries. Reimplement the
  small portable contract in Kotlin or use them as offline tooling/test references.
- Respect each recipe website's terms; parsing structured metadata does not grant rights to republish
  recipe text or images.

## Data-source notes

### Open Food Facts
https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/

Good for:
- packaged/branded products;
- barcode identity;
- nutrition labels and serving metadata.

Operational constraint:
- OFF documents a 10 search-request/minute/IP limit and 15 product-read requests/minute/IP.
- Never search on every keystroke. Cache and bound resolver calls.

Current branch:
- `OpenFoodFactsNutritionSource` uses full-text search, conservative lexical/brand matching,
  source provenance, a local cache and a request budget.
- Missing macro fields are rejected rather than silently filled with zero.
- Unknown serving sizes remain a 100 g reference instead of inventing a household portion.

### USDA FoodData Central
https://fdc.nal.usda.gov/

Good for:
- generic foods, raw ingredients and common prepared foods;
- CC0 data suitable for a reproducible offline subset.

Recommended implementation:
- import Foundation + FNDDS first;
- store a compact SQLite FTS index;
- keep per-100 g nutrients plus explicit portions where upstream supplies them;
- do not bundle the entire branded corpus initially because OFF already covers the product use case.

### Singapore SG FoodID
https://www.hpb.gov.sg/healthy-living/food-and-beverage/sgfoodid/

High product priority. It covers foods and drinks commonly found in Singapore and provides search,
comparison and download tooling.

Before bundling:
- confirm the specific SG FoodID export's licence/attribution terms, not merely the general HPB/MOH
  website terms;
- preserve dataset version/access date in provenance;
- build aliases without collapsing culturally distinct drinks (kopi, kopi O, kopi C, etc.).

### Malaysia MyFCD
https://myfcd.moh.gov.my/

High product priority. The current database includes raw/processed and prepared Malaysian foods.

Before bundling:
- obtain/confirm explicit reuse terms for bulk/application use;
- do not scrape the site into the repository without permission;
- build an adapter/importer only after licensing and a stable source format are confirmed.

### Indian food data

Prefer clearly reusable datasets rather than assuming every IFCT-derived file can be redistributed.

Candidates to investigate:
- USDA generic coverage for ingredients;
- Anuvaad INDB as documented by OpenNutriTracker-Backend (CC BY 4.0);
- Sangat cooked-dish data (CC BY-SA 4.0) if share-alike obligations for the data layer are acceptable.

Caution:
- the Jaacks INDB repository documents recipes and generation code but points back to source food
  composition material whose redistribution terms need separate verification.
- Treat claims that "IFCT is public domain" as unverified until the exact dataset licence is confirmed.

### FoodSG-233

Useful as a research benchmark for Singapore food recognition, not a production dataset:
the published access terms limit it to non-commercial research/education and restrict redistribution.

## Resolver roadmap

### R1 - product grounding (current)
- Personal saved food source.
- Open Food Facts branded source.
- Provenance and explicit AI fallback.
- Material ambiguity clarification.

### R2 - offline generic/regional data
- CanonicalFoodRecord schema + dataset manifest.
- USDA Foundation/FNDDS importer and compact SQLite index.
- SG FoodID importer after licence verification.
- MyFCD importer after licence verification.
- Indian reusable dataset adapter after licence verification.
- Accent/alias table for regional spellings and multilingual names.

### R3 - smarter uncertainty
- ImpactQuestionPolicy: calculate expected calorie swing and interrupt only for the top 1-2 questions.
- Pre-answer lower-impact assumptions visibly rather than silently hiding them.
- Do not ask questions when the answer cannot materially alter the result.
- Meal-level questions fire once, not once per component.

### R4 - personal food memory
- Learn an answer only after repeated explicit confirmation.
- Store aliases, usual serving size, preparation and vendor/stall context locally.
- Changing an answer resets the learned streak.
- Personal memory outranks public databases but always remains editable.

Examples:
- "my kopi" -> kopi C kosong, usual cup size.
- "Amma's chicken curry" -> saved recipe + serving.
- "usual chicken rice downstairs" -> restaurant/stall alias + prior serving.

### R5 - photo pipeline
- Vision produces the same MealInterpretation contract as text.
- Detect/segment components before nutrition resolution.
- Barcode/label evidence outranks visual estimation.
- Countable items can use count; visual grams remain uncertain unless scale/reference evidence exists.
- Review shows each component, assumed portion, source and uncertainty.

### R6 - evaluation
Create a versioned golden set:
- Singapore/Malaysia/Indian mixed meals;
- packaged foods;
- drinks with local ordering vocabulary;
- weighed portions and recipe-ground-truth meals;
- deliberately ambiguous natural-language logs.

Measure separately:
- food identification accuracy;
- quantity parsing;
- database match precision/recall;
- portion error;
- calorie/macro error after the full resolver;
- clarification frequency and value;
- rate of false confident matches.

A model upgrade must not be accepted merely because raw model output looks better; score the complete
pipeline that produces the logged number.

## UX additions worth building

- Source chip per component: Personal / SG FoodID / MyFCD / OFF / USDA / Estimate.
- Confidence or range on uncertain portions, not fake decimal precision.
- "Assumptions" chips editable directly in Review.
- "Why this number?" sheet with source, serving basis and calculation.
- One-tap correction that recomputes totals locally.
- "Remember this next time" only after the user confirms a correction.
- Vendor/stall aliases and personal recipes.
- Partial resolution must never masquerade as a complete meal total.
- Photo + text can be combined: photo identifies components, user note supplies hidden facts
  ("shared half", "no skin", "extra rice", "2 pieces").

## Licensing rule for contributors/agents

Before importing code or data:
1. record the upstream URL and exact licence;
2. distinguish code licence from data licence;
3. preserve required attribution;
4. do not copy GPL/AGPL code into this MIT codebase;
5. do not bundle a dataset whose redistribution rights are unclear;
6. architecture ideas, algorithms and independently reimplemented behaviour are fine, but source
   copying must remain licence-compatible.
