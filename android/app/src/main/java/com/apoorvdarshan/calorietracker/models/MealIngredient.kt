package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class MealIngredient(
    val name: String,
    val grams: Double,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val imageFilename: String? = null,
    val additionalImageFilenames: List<String> = emptyList(),
    val emoji: String? = null,
    val nutritionProvenance: com.apoorvdarshan.calorietracker.nutrition.NutritionProvenance? = null,
    /**
     * Per-ingredient source micronutrients for evidence-backed meals.
     * Null means the ingredient has no component-level micronutrient snapshot, so callers must not
     * infer a mixed-meal micronutrient total from it. An empty map is a known snapshot with no
     * shared optional nutrient values.
     */
    val micronutrients: Map<String, com.apoorvdarshan.calorietracker.nutrition.NutrientAmount>? = null
) {
    val allImageFilenames: List<String>
        get() = (listOfNotNull(imageFilename) + additionalImageFilenames).distinct()

    fun scaled(factor: Double): MealIngredient {
        require(factor.isFinite() && factor >= 0.0) { "Invalid ingredient scale" }
        fun scaledDouble(value: Double, label: String): Double {
            require(value.isFinite() && value >= 0.0) { "Invalid ingredient $label" }
            val result = value * factor
            require(result.isFinite() && result >= 0.0) { "Unsafe ingredient $label" }
            return result
        }

        val scaledCalories = calories.toDouble() * factor
        require(scaledCalories.isFinite() && scaledCalories in 0.0..Int.MAX_VALUE.toDouble()) {
            "Unsafe ingredient calories"
        }
        return copy(
            grams = scaledDouble(grams, "grams"),
            calories = scaledCalories.roundToInt(),
            protein = scaledDouble(protein, "protein"),
            carbs = scaledDouble(carbs, "carbohydrate"),
            fat = scaledDouble(fat, "fat"),
            micronutrients = micronutrients?.mapValues { (key, nutrient) ->
                require(nutrient.amount.isFinite() && nutrient.amount >= 0.0) {
                    "Invalid ingredient micronutrient $key"
                }
                val amount = nutrient.amount * factor
                require(amount.isFinite() && amount >= 0.0) {
                    "Unsafe ingredient micronutrient $key"
                }
                nutrient.copy(amount = amount)
            }
        )
    }
}

data class MealIngredientTotals(
    val grams: Double,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

fun List<MealIngredient>.totals(): MealIngredientTotals {
    fun sumDouble(label: String, value: (MealIngredient) -> Double): Double {
        var total = 0.0
        forEach { ingredient ->
            val current = value(ingredient)
            require(current.isFinite() && current >= 0.0) { "Invalid ingredient $label" }
            total += current
            require(total.isFinite() && total >= 0.0) { "Unsafe ingredient $label total" }
        }
        return total
    }

    var calorieTotal = 0L
    forEach { ingredient ->
        require(ingredient.calories >= 0) { "Invalid ingredient calories" }
        calorieTotal += ingredient.calories.toLong()
        require(calorieTotal <= Int.MAX_VALUE.toLong()) { "Unsafe ingredient calorie total" }
    }

    return MealIngredientTotals(
        grams = sumDouble("grams") { it.grams },
        calories = calorieTotal.toInt(),
        protein = sumDouble("protein") { it.protein },
        carbs = sumDouble("carbohydrate") { it.carbs },
        fat = sumDouble("fat") { it.fat }
    )
}
