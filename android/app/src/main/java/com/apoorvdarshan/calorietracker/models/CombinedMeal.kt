package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.util.UUID

/** Map a diary food to one ingredient line (nested ingredients stay collapsed). */
fun FoodEntry.toMealIngredient(): MealIngredient {
    val componentMicros = MealMicronutrientSnapshot.from(this)
        .toNutrientMap(sourceNutrients)
        .takeIf { it.isNotEmpty() }
    return MealIngredient(
        name = name,
        grams = servingSizeGrams?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        imageFilename = allImageFilenames.firstOrNull(),
        additionalImageFilenames = allImageFilenames.drop(1),
        emoji = emoji,
        nutritionProvenance = nutritionProvenance.singleOrNull(),
        micronutrients = componentMicros
    )
}

private fun FoodAnalysis.micronutrientSnapshot() = MealMicronutrientSnapshot(
    sugar = sugar,
    addedSugar = addedSugar,
    fiber = fiber,
    saturatedFat = saturatedFat,
    monounsaturatedFat = monounsaturatedFat,
    polyunsaturatedFat = polyunsaturatedFat,
    cholesterol = cholesterol,
    caffeine = caffeine,
    supplementalNutrients = supplementalNutrients,
    sodium = sodium,
    potassium = potassium,
    transFat = transFat,
    calcium = calcium,
    iron = iron,
    magnesium = magnesium,
    zinc = zinc,
    vitaminA = vitaminA,
    vitaminC = vitaminC,
    vitaminD = vitaminD,
    vitaminB12 = vitaminB12,
    vitaminE = vitaminE,
    vitaminK = vitaminK,
    folate = folate,
    omega3 = omega3
)

/** Map an analysis result to one ingredient line (nested ingredients stay collapsed). */
fun FoodAnalysis.toMealIngredient(): MealIngredient {
    val componentMicros = micronutrientSnapshot()
        .toNutrientMap(sourceNutrients)
        .takeIf { it.isNotEmpty() }
    return MealIngredient(
        name = name,
        grams = servingSizeGrams.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        emoji = emoji,
        nutritionProvenance = nutritionProvenance.singleOrNull(),
        micronutrients = componentMicros
    )
}

/** Recompute parent nutrition from an ingredient list without inventing missing micronutrients. */
fun FoodEntry.withIngredients(ingredients: List<MealIngredient>): FoodEntry {
    val totals = ingredients.totals()
    val componentMicros = ingredients.micronutrientTotalsOrNull()
    val withComponentMicros = when {
        componentMicros != null -> {
            withMicros(componentMicros.snapshot).copy(sourceNutrients = componentMicros.sourceNutrients)
        }
        this.ingredients.any { it.micronutrients != null } ||
            ingredients.any { it.micronutrients != null } -> {
            withMicros(MealMicronutrientSnapshot()).copy(sourceNutrients = emptyMap())
        }
        else -> copy()
    }
    return withComponentMicros.copy(
        calories = totals.calories,
        protein = totals.protein,
        carbs = totals.carbs,
        fat = totals.fat,
        servingSizeGrams = totals.grams.takeIf { it > 0 } ?: servingSizeGrams,
        servingUnitOptions = emptyList(),
        selectedServingUnit = null,
        selectedServingQuantity = null,
        ingredients = ingredients
    )
}

object CombinedMeal {
    fun combinedName(entries: List<FoodEntry>): String {
        val names = entries.map { it.name.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return "Combined meal"
        if (names.size <= 3) return names.joinToString(" + ")
        return names.take(2).joinToString(" + ") + " + ${names.size - 2} more"
    }

    fun combineFoodEntries(entries: List<FoodEntry>): FoodEntry {
        require(entries.size >= 2) { "Combine requires at least two food entries" }
        val ingredients = entries.map { it.toMealIngredient() }
        val totals = ingredients.totals()
        val latest = entries.maxByOrNull { it.timestamp } ?: entries.first()
        val filenames = entries.flatMap { it.allImageFilenames }.distinct()
        val componentMicros = ingredients.micronutrientTotalsOrNull()
        val base = FoodEntry(
            id = UUID.randomUUID(),
            name = combinedName(entries),
            calories = totals.calories,
            protein = totals.protein,
            carbs = totals.carbs,
            fat = totals.fat,
            timestamp = latest.timestamp,
            imageFilename = filenames.firstOrNull(),
            additionalImageFilenames = filenames.drop(1),
            emoji = entries.firstNotNullOfOrNull { it.emoji },
            source = FoodSource.MANUAL,
            healthConnectOrigin = entries.firstNotNullOfOrNull { it.healthConnectOrigin },
            mealType = latest.mealType,
            servingSizeGrams = totals.grams.takeIf { it > 0 },
            ingredients = ingredients,
            nutritionProvenance = entries.flatMap { it.nutritionProvenance }.distinct(),
            nutritionWarnings = entries.flatMap { it.nutritionWarnings }.distinct()
        )
        return if (componentMicros != null) {
            base.withMicros(componentMicros.snapshot)
                .copy(sourceNutrients = componentMicros.sourceNutrients)
        } else {
            base
        }
    }
}

fun combineFoodEntries(entries: List<FoodEntry>): FoodEntry = CombinedMeal.combineFoodEntries(entries)
