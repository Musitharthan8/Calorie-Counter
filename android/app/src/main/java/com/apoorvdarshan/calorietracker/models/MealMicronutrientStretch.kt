package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.nutrition.NutrientAmount

/** Meal-level micro stretch used when ingredients change but micros are not per-ingredient. */
object MealMicronutrientStretch {
    fun factor(oldGrams: Double, newGrams: Double): Double? {
        if (oldGrams <= 0.0) return null
        if (newGrams <= 0.0) return 0.0
        return newGrams / oldGrams
    }

    fun scale(value: Double?, factor: Double): Double? = value?.let { it * factor }

    fun scale(values: Map<String, Double>, factor: Double): Map<String, Double> =
        values.mapValues { (_, value) -> value * factor }
}

data class MealMicronutrientSnapshot(
    val sugar: Double? = null,
    val addedSugar: Double? = null,
    val fiber: Double? = null,
    val saturatedFat: Double? = null,
    val monounsaturatedFat: Double? = null,
    val polyunsaturatedFat: Double? = null,
    val cholesterol: Double? = null,
    val caffeine: Double? = null,
    val supplementalNutrients: Map<String, Double> = emptyMap(),
    val sodium: Double? = null,
    val potassium: Double? = null,
    val transFat: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
    val vitaminD: Double? = null,
    val vitaminB12: Double? = null,
    val vitaminE: Double? = null,
    val vitaminK: Double? = null,
    val folate: Double? = null,
    val omega3: Double? = null
) {
    fun stretched(oldGrams: Double, newGrams: Double): MealMicronutrientSnapshot {
        val factor = MealMicronutrientStretch.factor(oldGrams, newGrams) ?: return this
        return copy(
            sugar = MealMicronutrientStretch.scale(sugar, factor),
            addedSugar = MealMicronutrientStretch.scale(addedSugar, factor),
            fiber = MealMicronutrientStretch.scale(fiber, factor),
            saturatedFat = MealMicronutrientStretch.scale(saturatedFat, factor),
            monounsaturatedFat = MealMicronutrientStretch.scale(monounsaturatedFat, factor),
            polyunsaturatedFat = MealMicronutrientStretch.scale(polyunsaturatedFat, factor),
            cholesterol = MealMicronutrientStretch.scale(cholesterol, factor),
            caffeine = MealMicronutrientStretch.scale(caffeine, factor),
            supplementalNutrients = MealMicronutrientStretch.scale(supplementalNutrients, factor),
            sodium = MealMicronutrientStretch.scale(sodium, factor),
            potassium = MealMicronutrientStretch.scale(potassium, factor),
            transFat = MealMicronutrientStretch.scale(transFat, factor),
            calcium = MealMicronutrientStretch.scale(calcium, factor),
            iron = MealMicronutrientStretch.scale(iron, factor),
            magnesium = MealMicronutrientStretch.scale(magnesium, factor),
            zinc = MealMicronutrientStretch.scale(zinc, factor),
            vitaminA = MealMicronutrientStretch.scale(vitaminA, factor),
            vitaminC = MealMicronutrientStretch.scale(vitaminC, factor),
            vitaminD = MealMicronutrientStretch.scale(vitaminD, factor),
            vitaminB12 = MealMicronutrientStretch.scale(vitaminB12, factor),
            vitaminE = MealMicronutrientStretch.scale(vitaminE, factor),
            vitaminK = MealMicronutrientStretch.scale(vitaminK, factor),
            folate = MealMicronutrientStretch.scale(folate, factor),
            omega3 = MealMicronutrientStretch.scale(omega3, factor)
        )
    }

    companion object {
        fun from(entry: FoodEntry): MealMicronutrientSnapshot = MealMicronutrientSnapshot(
            sugar = entry.sugar,
            addedSugar = entry.addedSugar,
            fiber = entry.fiber,
            saturatedFat = entry.saturatedFat,
            monounsaturatedFat = entry.monounsaturatedFat,
            polyunsaturatedFat = entry.polyunsaturatedFat,
            cholesterol = entry.cholesterol,
            caffeine = entry.caffeine,
            supplementalNutrients = entry.supplementalNutrients,
            sodium = entry.sodium,
            potassium = entry.potassium,
            transFat = entry.transFat,
            calcium = entry.calcium,
            iron = entry.iron,
            magnesium = entry.magnesium,
            zinc = entry.zinc,
            vitaminA = entry.vitaminA,
            vitaminC = entry.vitaminC,
            vitaminD = entry.vitaminD,
            vitaminB12 = entry.vitaminB12,
            vitaminE = entry.vitaminE,
            vitaminK = entry.vitaminK,
            folate = entry.folate,
            omega3 = entry.omega3
        )
    }
}

data class IngredientMicronutrientTotals(
    val snapshot: MealMicronutrientSnapshot,
    val sourceNutrients: Map<String, NutrientAmount>
)

/**
 * Recompute micronutrients from component evidence.
 *
 * Returns null when any ingredient lacks a component-level micronutrient snapshot. Within a fully
 * evidenced meal, only nutrients present for every component in the same unit are summed. Missing
 * source values remain unknown rather than becoming zero.
 */
fun List<MealIngredient>.micronutrientTotalsOrNull(): IngredientMicronutrientTotals? {
    if (isEmpty() || any { it.micronutrients == null }) return null
    val maps = map { requireNotNull(it.micronutrients) }
    val commonKeys = maps
        .map { it.keys }
        .reduce { left, right -> left intersect right }

    val totals = buildMap {
        commonKeys.forEach { key ->
            val values = maps.mapNotNull { it[key] }
            val unit = values.firstOrNull()?.unit ?: return@forEach
            if (values.size != maps.size || values.any { it.unit != unit }) return@forEach
            var total = 0.0
            values.forEach { nutrient ->
                if (!nutrient.amount.isFinite() || nutrient.amount < 0.0) return@forEach
                total += nutrient.amount
                if (!total.isFinite() || total < 0.0) return@forEach
            }
            put(key, NutrientAmount(total, unit))
        }
    }

    fun amount(key: String, unit: String): Double? =
        totals[key]?.takeIf { it.unit == unit }?.amount

    val supplementalKeys = SupplementalNutrient.values().map { it.storageKey }.toSet()
    val supplemental = SupplementalNutrient.values().mapNotNull { nutrient ->
        amount(nutrient.storageKey, "g")?.let { nutrient.storageKey to it }
    }.toMap()
    val legacyKeys = setOf(
        "sugar", "addedSugar", "fiber", "saturatedFat", "monounsaturatedFat",
        "polyunsaturatedFat", "cholesterol", "caffeine", "sodium", "potassium",
        "transFat", "calcium", "iron", "magnesium", "zinc", "vitaminA",
        "vitaminC", "vitaminD", "vitaminB12", "vitaminE", "vitaminK",
        "folate", "omega3"
    )

    return IngredientMicronutrientTotals(
        snapshot = MealMicronutrientSnapshot(
            sugar = amount("sugar", "g"),
            addedSugar = amount("addedSugar", "g"),
            fiber = amount("fiber", "g"),
            saturatedFat = amount("saturatedFat", "g"),
            monounsaturatedFat = amount("monounsaturatedFat", "g"),
            polyunsaturatedFat = amount("polyunsaturatedFat", "g"),
            cholesterol = amount("cholesterol", "mg"),
            caffeine = amount("caffeine", "mg"),
            supplementalNutrients = supplemental,
            sodium = amount("sodium", "mg"),
            potassium = amount("potassium", "mg"),
            transFat = amount("transFat", "g"),
            calcium = amount("calcium", "mg"),
            iron = amount("iron", "mg"),
            magnesium = amount("magnesium", "mg"),
            zinc = amount("zinc", "mg"),
            vitaminA = amount("vitaminA", "ug"),
            vitaminC = amount("vitaminC", "mg"),
            vitaminD = amount("vitaminD", "ug"),
            vitaminB12 = amount("vitaminB12", "ug"),
            vitaminE = amount("vitaminE", "mg"),
            vitaminK = amount("vitaminK", "ug"),
            folate = amount("folate", "ug"),
            omega3 = amount("omega3", "g")
        ),
        sourceNutrients = totals.filterKeys { it !in legacyKeys && it !in supplementalKeys }
    )
}

fun FoodEntry.withMicros(snapshot: MealMicronutrientSnapshot): FoodEntry = copy(
    sugar = snapshot.sugar,
    addedSugar = snapshot.addedSugar,
    fiber = snapshot.fiber,
    saturatedFat = snapshot.saturatedFat,
    monounsaturatedFat = snapshot.monounsaturatedFat,
    polyunsaturatedFat = snapshot.polyunsaturatedFat,
    cholesterol = snapshot.cholesterol,
    caffeine = snapshot.caffeine,
    supplementalNutrients = snapshot.supplementalNutrients,
    sodium = snapshot.sodium,
    potassium = snapshot.potassium,
    transFat = snapshot.transFat,
    calcium = snapshot.calcium,
    iron = snapshot.iron,
    magnesium = snapshot.magnesium,
    zinc = snapshot.zinc,
    vitaminA = snapshot.vitaminA,
    vitaminC = snapshot.vitaminC,
    vitaminD = snapshot.vitaminD,
    vitaminB12 = snapshot.vitaminB12,
    vitaminE = snapshot.vitaminE,
    vitaminK = snapshot.vitaminK,
    folate = snapshot.folate,
    omega3 = snapshot.omega3
)

/** Review/Edit Food ingredient edits reset meal scale to 1.0, so micros must be rewritten. */
fun FoodEntry.applyingIngredientChanges(displayedIngredients: List<MealIngredient>): FoodEntry {
    val totals = displayedIngredients.totals()
    val componentMicros = displayedIngredients.micronutrientTotalsOrNull()
    val previousGrams = ingredients.totals().grams
    val fallbackFactor = MealMicronutrientStretch.factor(previousGrams, totals.grams)
    val base = if (componentMicros != null) {
        withMicros(componentMicros.snapshot).copy(sourceNutrients = componentMicros.sourceNutrients)
    } else {
        val stretched = MealMicronutrientSnapshot.from(this).stretched(previousGrams, totals.grams)
        val stretchedSource = if (fallbackFactor == null) {
            sourceNutrients
        } else {
            sourceNutrients.mapNotNull { (key, nutrient) ->
                val amount = nutrient.amount * fallbackFactor
                if (amount.isFinite() && amount >= 0.0) key to nutrient.copy(amount = amount) else null
            }.toMap()
        }
        withMicros(stretched).copy(sourceNutrients = stretchedSource)
    }
    return base.copy(
        calories = totals.calories,
        protein = totals.protein,
        carbs = totals.carbs,
        fat = totals.fat,
        servingSizeGrams = totals.grams.takeIf { it > 0 } ?: servingSizeGrams,
        servingUnitOptions = emptyList(),
        selectedServingUnit = null,
        selectedServingQuantity = null,
        ingredients = displayedIngredients
    )
}
