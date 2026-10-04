package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Migration boundary: keep FoodAnalysis/review/diary intact and never return an incomplete total. */
class RegionalNutritionEngine(
    private val interpreter: MealInterpreter,
    private val resolver: NutritionResolver,
    private val estimate: suspend (String) -> FoodAnalysis
) {
    suspend fun analyze(rawText: String): FoodAnalysis {
        require(rawText.isNotBlank() && rawText.length <= 16000)
        val interpretation = try {
            interpreter.interpret(rawText).validated()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Old/simple logging survives providers that cannot follow the new schema.
            // Still guard material quantity ambiguity before allowing a legacy estimate.
            val local = runCatching { LocalMealInterpreter().parse(rawText) }.getOrNull()
            if (local?.ambiguities?.any { it.affectsNutrition } == true) throw MealClarificationRequired(local)
            return fallback(rawText, local, "Structured interpretation unavailable. Nutrition is an AI estimate.")
        }
        return analyzeInterpretation(
            interpretation = interpretation,
            fallbackLabel = rawText,
            fallbackEstimate = { estimate(rawText) }
        )
    }

    /**
     * Shared resolution boundary for non-text inputs such as grounded photo interpretation.
     *
     * The caller owns perception and the legacy fallback action; this method owns the invariant
     * that material ambiguity is clarified first and partial database matches never become a meal total.
     */
    internal suspend fun analyzeInterpretation(
        interpretation: MealInterpretation,
        fallbackLabel: String,
        fallbackEstimate: suspend () -> FoodAnalysis
    ): FoodAnalysis {
        val validated = interpretation.validated()
        require(fallbackLabel.isNotBlank() && fallbackLabel.length <= 16000)
        if (validated.ambiguities.any { it.affectsNutrition }) {
            throw MealClarificationRequired(validated)
        }
        val resolution = resolver.resolve(validated)
        if (!resolution.complete) {
            return fallback(
                rawInput = fallbackLabel,
                meal = validated,
                warning = "AI estimate: no complete verified food and portion match. Recipes and portions may vary.",
                estimateAction = fallbackEstimate
            )
        }
        return try {
            resolution.toFoodAnalysis()
        } catch (_: IllegalArgumentException) {
            fallback(
                rawInput = fallbackLabel,
                meal = validated,
                warning = "AI estimate: verified nutrition values could not be combined safely.",
                estimateAction = fallbackEstimate
            )
        }
    }

    private suspend fun fallback(
        rawInput: String,
        meal: MealInterpretation?,
        warning: String,
        estimateAction: suspend () -> FoodAnalysis = { estimate(rawInput) }
    ): FoodAnalysis {
        val analysis = estimateAction()
        fun provenance(name: String) = NutritionProvenance(
            source = NutritionSourceKind.AI_ESTIMATE,
            sourceName = "AI estimate",
            evidence = NutritionEvidence.AI_ESTIMATE,
            estimated = true,
            confidence = InterpretationConfidence.LOW,
            originalWording = rawInput,
            canonicalName = name
        )
        return analysis.copy(
            mealInterpretation = meal,
            nutritionProvenance = listOf(provenance(analysis.name)),
            nutritionWarnings = listOf(warning),
            ingredients = analysis.ingredients.map { it.copy(nutritionProvenance = provenance(it.name)) }
        )
    }
}

internal fun NutritionResolutionResult.toFoodAnalysis(): FoodAnalysis {
    require(complete) { "Cannot log partial nutrition as a meal total" }

    fun safeSum(values: Iterable<Double>, label: String): Double {
        var total = 0.0
        values.forEach { value ->
            require(value.isFinite() && value >= 0) { "Invalid $label value" }
            total += value
            require(total.isFinite() && total >= 0) { "Unsafe $label total" }
        }
        return total
    }

    val totalCaloriesDouble = safeSum(matches.map { it.nutrition.calories }, "calorie")
    require(totalCaloriesDouble <= Int.MAX_VALUE.toDouble()) { "Calorie total exceeds supported range" }
    val totalCalories = totalCaloriesDouble.roundToInt()
    val totalProtein = safeSum(matches.map { it.nutrition.protein }, "protein")
    val totalCarbs = safeSum(matches.map { it.nutrition.carbs }, "carbohydrate")
    val totalFat = safeSum(matches.map { it.nutrition.fat }, "fat")

    val allGramsKnown = matches.all { it.grams != null }
    val totalGrams = if (allGramsKnown) {
        safeSum(matches.map { requireNotNull(it.grams) }, "serving mass")
    } else {
        1.0
    }

    // Existing ingredient editing requires real mass; retain per-food provenance even without it.
    val ingredients = if (allGramsKnown) matches.map { match ->
        MealIngredient(match.candidate.canonicalName, match.grams!!, match.nutrition.calories.roundToInt(),
            match.nutrition.protein, match.nutrition.carbs, match.nutrition.fat,
            nutritionProvenance = match.provenance)
    } else emptyList()
    val commonMicronutrients: Map<String, NutrientAmount> = if (matches.isEmpty()) {
        emptyMap()
    } else {
        val commonKeys = matches
            .map { it.nutrition.micronutrients.keys }
            .reduce { left, right -> left intersect right }
        buildMap {
            commonKeys.forEach { key ->
                val values = matches.mapNotNull { it.nutrition.micronutrients[key] }
                val unit = values.firstOrNull()?.unit ?: return@forEach
                if (values.size != matches.size || values.any { it.unit != unit }) return@forEach
                val total = safeSum(values.map { it.amount }, "micronutrient $key")
                put(key, NutrientAmount(total, unit))
            }
        }
    }
    fun nutrient(key: String, unit: String): Double? =
        commonMicronutrients[key]?.takeIf { it.unit == unit }?.amount

    val legacyMicronutrientKeys = setOf(
        "sugar", "addedSugar", "fiber", "saturatedFat", "monounsaturatedFat",
        "polyunsaturatedFat", "cholesterol", "caffeine", "sodium", "potassium",
        "transFat", "calcium", "iron", "magnesium", "zinc", "vitaminA",
        "vitaminC", "vitaminD", "vitaminB12", "vitaminE", "vitaminK",
        "folate", "omega3"
    )
    return FoodAnalysis(
        name = matches.joinToString(", ") { it.candidate.canonicalName },
        calories = totalCalories,
        protein = totalProtein,
        carbs = totalCarbs,
        fat = totalFat,
        servingSizeGrams = totalGrams,
        servingSizeIsKnown = allGramsKnown,
        sugar = nutrient("sugar", "g"),
        addedSugar = nutrient("addedSugar", "g"),
        fiber = nutrient("fiber", "g"),
        saturatedFat = nutrient("saturatedFat", "g"),
        monounsaturatedFat = nutrient("monounsaturatedFat", "g"),
        polyunsaturatedFat = nutrient("polyunsaturatedFat", "g"),
        cholesterol = nutrient("cholesterol", "mg"),
        caffeine = nutrient("caffeine", "mg"),
        sourceNutrients = commonMicronutrients.filterKeys { it !in legacyMicronutrientKeys },
        sodium = nutrient("sodium", "mg"),
        potassium = nutrient("potassium", "mg"),
        transFat = nutrient("transFat", "g"),
        calcium = nutrient("calcium", "mg"),
        iron = nutrient("iron", "mg"),
        magnesium = nutrient("magnesium", "mg"),
        zinc = nutrient("zinc", "mg"),
        vitaminA = nutrient("vitaminA", "ug"),
        vitaminC = nutrient("vitaminC", "mg"),
        vitaminD = nutrient("vitaminD", "ug"),
        vitaminB12 = nutrient("vitaminB12", "ug"),
        vitaminE = nutrient("vitaminE", "mg"),
        vitaminK = nutrient("vitaminK", "ug"),
        folate = nutrient("folate", "ug"),
        omega3 = nutrient("omega3", "g"),
        ingredients = ingredients,
        mealInterpretation = interpretation,
        nutritionProvenance = matches.map { it.provenance },
        nutritionWarnings = buildList {
            if (matches.any { it.provenance.estimated }) {
                add("Includes estimated nutrition values; check source details.")
            }
            if (matches.any { it.provenance.userEdited }) {
                add("Nutrition values were edited after source lookup.")
            }
            if (matches.any { it.provenance.portionEstimated }) {
                add("One or more portion sizes are estimated or derived.")
            }
        }
    )
}
