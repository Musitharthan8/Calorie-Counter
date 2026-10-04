package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.models.FoodEntry

/**
 * Local personal-food memory backed by user-owned saved/repeated foods.
 *
 * The loader supplies candidates in preference order (explicit favourites first, learned history
 * afterwards). Duplicate names are intentionally collapsed to the first candidate so an old and a
 * new version of the same named meal cannot both create an arbitrary resolver tie.
 */
class PersonalFoodSource(private val loadSavedFoods: suspend () -> List<FoodEntry>) : NutritionSource {
    override suspend fun search(mention: FoodMention): List<NutritionCandidate> {
        val lookupNames = personalLookupNames(mention)
        val seenNames = mutableSetOf<String>()
        return loadSavedFoods()
            .asSequence()
            .filter { entry -> normaliseFoodName(entry.name) in lookupNames }
            .filter { entry -> seenNames.add(normaliseFoodName(entry.name)) }
            .mapNotNull { entry ->
                candidate(entry)?.copy(aliases = setOf(mention.name))
            }
            .toList()
    }

    private fun candidate(entry: FoodEntry): NutritionCandidate? {
        if (listOf(entry.calories.toDouble(), entry.protein, entry.carbs, entry.fat)
                .any { !it.isFinite() || it < 0 }
        ) return null
        val quantity = entry.selectedServingQuantity?.takeIf { it.isFinite() && it > 0 }
        val unit = entry.selectedServingUnit?.takeIf { it.isNotBlank() }
        val originalSource = entry.nutritionProvenance.singleOrNull()
        return NutritionCandidate(
            canonicalName = entry.name,
            source = NutritionSourceKind.PERSONAL,
            sourceName = originalSource?.let { "Personal saved food · ${it.sourceName}" }
                ?: "Personal saved food",
            sourceFoodId = entry.id.toString(),
            sourceUrl = originalSource?.sourceUrl,
            datasetVersion = originalSource?.datasetVersion,
            license = originalSource?.license,
            attribution = originalSource?.attribution,
            evidence = NutritionEvidence.SAVED_FOOD,
            nutrition = NutrientValues(
                entry.calories.toDouble(),
                entry.protein,
                entry.carbs,
                entry.fat,
                buildMap {
                    fun add(key: String, value: Double?, unitName: String) {
                        value?.takeIf { it.isFinite() && it >= 0 }
                            ?.let { put(key, NutrientAmount(it, unitName)) }
                    }
                    add("sugar", entry.sugar, "g")
                    add("addedSugar", entry.addedSugar, "g")
                    add("fiber", entry.fiber, "g")
                    add("saturatedFat", entry.saturatedFat, "g")
                    add("monounsaturatedFat", entry.monounsaturatedFat, "g")
                    add("polyunsaturatedFat", entry.polyunsaturatedFat, "g")
                    add("cholesterol", entry.cholesterol, "mg")
                    add("caffeine", entry.caffeine, "mg")
                    add("sodium", entry.sodium, "mg")
                    add("potassium", entry.potassium, "mg")
                    add("transFat", entry.transFat, "g")
                    add("calcium", entry.calcium, "mg")
                    add("iron", entry.iron, "mg")
                    add("magnesium", entry.magnesium, "mg")
                    add("zinc", entry.zinc, "mg")
                    add("vitaminA", entry.vitaminA, "ug")
                    add("vitaminC", entry.vitaminC, "mg")
                    add("vitaminD", entry.vitaminD, "ug")
                    add("vitaminB12", entry.vitaminB12, "ug")
                    add("vitaminE", entry.vitaminE, "mg")
                    add("vitaminK", entry.vitaminK, "ug")
                    add("folate", entry.folate, "ug")
                    add("omega3", entry.omega3, "g")
                    entry.sourceNutrients.forEach { (key, nutrient) ->
                        if (nutrient.amount.isFinite() && nutrient.amount >= 0) {
                            put(key, nutrient)
                        }
                    }
                }
            ),
            // No known unit means one copy of the saved food, not an invented 100 g portion.
            referenceQuantity = if (quantity != null && unit != null) {
                FoodQuantity(quantity, unit)
            } else {
                FoodQuantity(1.0, "serving")
            },
            referenceGrams = entry.servingSizeGrams?.takeIf { it.isFinite() && it > 0 },
            estimated = entry.nutritionProvenance.isEmpty() ||
                entry.nutritionProvenance.any { it.estimated },
            referencePortionEstimated = entry.nutritionProvenance.any { it.portionEstimated },
            userEdited = entry.nutritionProvenance.any { it.userEdited }
        )
    }

    private fun personalLookupNames(mention: FoodMention): Set<String> = buildSet {
        listOfNotNull(mention.name, mention.personalFoodName).forEach { raw ->
            val normalized = normaliseFoodName(raw)
            if (normalized.isNotEmpty()) add(normalized)
            stripPersonalPrefix(raw)?.let { add(normaliseFoodName(it)) }
        }
    }

    private fun stripPersonalPrefix(value: String): String? {
        val stripped = value.trim().replace(
            Regex("^(?:my\\s+usual|usual|my)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return stripped.takeIf { it.isNotEmpty() && !it.equals(value.trim(), ignoreCase = true) }
    }
}
