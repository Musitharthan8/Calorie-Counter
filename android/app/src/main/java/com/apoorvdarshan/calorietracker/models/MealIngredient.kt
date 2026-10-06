package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DISPLAY_ROUNDING_TOLERANCE_G = 0.05 + 1e-9

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

    /**
     * Apply review-sheet edits without letting source evidence drift onto a different food/composition.
     *
     * Per-component micronutrients/provenance survive only when the edit is a pure proportional
     * serving change of the same ingredient. Identity or manual macro changes invalidate that
     * component-level source evidence; the parent meal still records that the user edited nutrition.
     */
    fun withUserEdits(
        name: String,
        grams: Double,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double
    ): MealIngredient {
        require(name.isNotBlank())
        require(grams.isFinite() && grams > 0.0)
        require(calories >= 0)
        require(listOf(protein, carbs, fat).all { it.isFinite() && it >= 0.0 })

        val proportional = if (this.grams.isFinite() && this.grams > 0.0) {
            runCatching { scaled(grams / this.grams) }.getOrNull()
        } else {
            null
        }
        fun close(actual: Double, expected: Double): Boolean =
            abs(actual - expected) <= maxOf(DISPLAY_ROUNDING_TOLERANCE_G, abs(expected) * 0.001)

        val sourceEvidenceStillApplies = proportional != null &&
            name.trim() == this.name.trim() &&
            calories == proportional.calories &&
            close(protein, proportional.protein) &&
            close(carbs, proportional.carbs) &&
            close(fat, proportional.fat)

        return copy(
            name = name.trim(),
            grams = grams,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            nutritionProvenance = if (sourceEvidenceStillApplies) {
                proportional?.nutritionProvenance?.copy(userEdited = true)
            } else {
                null
            },
            micronutrients = if (sourceEvidenceStillApplies) proportional?.micronutrients else null
        )
    }

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
