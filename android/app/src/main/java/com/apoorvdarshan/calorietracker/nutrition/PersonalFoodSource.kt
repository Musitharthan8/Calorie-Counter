package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.models.FoodEntry

/** Saved meals are already durable, local and user editable. Their names act as personal aliases. */
class PersonalFoodSource(private val loadSavedFoods: suspend () -> List<FoodEntry>) : NutritionSource {
    override suspend fun search(mention: FoodMention): List<NutritionCandidate> = loadSavedFoods()
        .filter { normaliseFoodName(it.name) == normaliseFoodName(mention.personalFoodName ?: mention.name) }
        .mapNotNull { entry ->
            if (listOf(entry.calories.toDouble(), entry.protein, entry.carbs, entry.fat).any { !it.isFinite() || it < 0 }) return@mapNotNull null
            val quantity = entry.selectedServingQuantity?.takeIf { it.isFinite() && it > 0 }
            val unit = entry.selectedServingUnit?.takeIf { it.isNotBlank() }
            NutritionCandidate(
                canonicalName = entry.name,
                source = NutritionSourceKind.PERSONAL,
                sourceName = "Personal saved food",
                sourceFoodId = entry.id.toString(),
                evidence = NutritionEvidence.SAVED_FOOD,
                nutrition = NutrientValues(entry.calories.toDouble(), entry.protein, entry.carbs, entry.fat,
                    buildMap {
                        fun add(key: String, value: Double?, unit: String) {
                            value?.takeIf { it.isFinite() && it >= 0 }?.let { put(key, NutrientAmount(it, unit)) }
                        }
                        add("sugar", entry.sugar, "g"); add("fiber", entry.fiber, "g")
                        add("sodium", entry.sodium, "mg"); add("calcium", entry.calcium, "mg")
                        add("iron", entry.iron, "mg"); add("potassium", entry.potassium, "mg")
                        add("vitaminC", entry.vitaminC, "mg"); add("vitaminD", entry.vitaminD, "ug")
                    }),
                // No known unit means one copy of the saved food, not an invented 100g portion.
                referenceQuantity = if (quantity != null && unit != null) FoodQuantity(quantity, unit)
                    else FoodQuantity(1.0, "serving"),
                referenceGrams = entry.servingSizeGrams?.takeIf { it.isFinite() && it > 0 },
                estimated = entry.nutritionProvenance.isEmpty() || entry.nutritionProvenance.any { it.estimated }
            )
        }
}
