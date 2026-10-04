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
            .mapNotNull(::candidate)
            .toList()
    }

    private fun candidate(entry: FoodEntry): NutritionCandidate? {
        if (listOf(entry.calories.toDouble(), entry.protein, entry.carbs, entry.fat)
                .any { !it.isFinite() || it < 0 }
        ) return null
        val quantity = entry.selectedServingQuantity?.takeIf { it.isFinite() && it > 0 }
        val unit = entry.selectedServingUnit?.takeIf { it.isNotBlank() }
        return NutritionCandidate(
            canonicalName = entry.name,
            source = NutritionSourceKind.PERSONAL,
            sourceName = "Personal saved food",
            sourceFoodId = entry.id.toString(),
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
                    add("fiber", entry.fiber, "g")
                    add("sodium", entry.sodium, "mg")
                    add("calcium", entry.calcium, "mg")
                    add("iron", entry.iron, "mg")
                    add("potassium", entry.potassium, "mg")
                    add("vitaminC", entry.vitaminC, "mg")
                    add("vitaminD", entry.vitaminD, "ug")
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
                entry.nutritionProvenance.any { it.estimated }
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
