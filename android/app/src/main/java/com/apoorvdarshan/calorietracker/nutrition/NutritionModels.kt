package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.serialization.Serializable

/** Order is the product's preferred source hierarchy, not a claim of dataset availability. */
@Serializable
enum class NutritionSourceKind {
    PERSONAL, SG_FOOD_ID, MY_FCD, IFCT, BRAND, OPEN_FOOD_FACTS, USDA, AI_ESTIMATE
}

@Serializable
enum class NutritionEvidence { DATABASE, PRODUCT_LABEL, RECIPE, SAVED_FOOD, AI_ESTIMATE }

@Serializable
data class NutritionProvenance(
    val source: NutritionSourceKind,
    val sourceName: String,
    val foodId: String? = null,
    val evidence: NutritionEvidence,
    val estimated: Boolean,
    val confidence: InterpretationConfidence,
    val originalWording: String,
    val canonicalName: String,
    val sourceUrl: String? = null,
    val datasetVersion: String? = null,
    val license: String? = null,
    val attribution: String? = null
)

/** Values describe one explicit reference portion. Optional nutrients retain their units. */
@Serializable
data class NutrientValues(
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val micronutrients: Map<String, NutrientAmount> = emptyMap()
) {
    init { require(listOf(calories, protein, carbs, fat).all { it.isFinite() && it >= 0 }) }
    fun scaled(factor: Double): NutrientValues {
        require(factor.isFinite() && factor > 0)
        return copy(calories = calories * factor, protein = protein * factor,
            carbs = carbs * factor, fat = fat * factor,
            micronutrients = micronutrients.mapValues { (_, v) -> v.copy(amount = v.amount * factor) })
    }
}

@Serializable
data class NutrientAmount(val amount: Double, val unit: String) {
    init { require(amount.isFinite() && amount >= 0 && unit in setOf("g", "mg", "ug")) }
}

data class NutritionCandidate(
    val canonicalName: String,
    val aliases: Set<String> = emptySet(),
    val source: NutritionSourceKind,
    val sourceName: String,
    val sourceFoodId: String? = null,
    val sourceUrl: String? = null,
    val datasetVersion: String? = null,
    val license: String? = null,
    val attribution: String? = null,
    val evidence: NutritionEvidence,
    val nutrition: NutrientValues,
    val referenceQuantity: FoodQuantity,
    val referenceGrams: Double? = null,
    val brand: String? = null,
    val region: String? = null,
    val preparation: String? = null,
    val supportedModifiers: Set<String> = emptySet(),
    val reliability: Double = 1.0,
    val estimated: Boolean = false
) {
    init {
        require(canonicalName.isNotBlank() && sourceName.isNotBlank())
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(referenceGrams == null || referenceGrams.isFinite() && referenceGrams > 0)
    }
}

fun interface NutritionSource {
    suspend fun search(mention: FoodMention): List<NutritionCandidate>
}

data class NutritionMatch(
    val mention: FoodMention,
    val candidate: NutritionCandidate,
    val nutrition: NutrientValues,
    val grams: Double?,
    val score: Double,
    val provenance: NutritionProvenance
)

data class NutritionResolutionResult(
    val interpretation: MealInterpretation,
    val matches: List<NutritionMatch>,
    val unresolved: List<FoodMention>,
    val sourceFailures: List<String> = emptyList()
) {
    val complete: Boolean get() = unresolved.isEmpty() && matches.isNotEmpty() &&
        interpretation.ambiguities.none { it.affectsNutrition }
}

/** An offline/imported dataset adapter. Empty datasets are explicitly unavailable, never fabricated. */
class InMemoryNutritionSource(private val foods: List<NutritionCandidate>) : NutritionSource {
    override suspend fun search(mention: FoodMention): List<NutritionCandidate> = foods.filter {
        (it.aliases + it.canonicalName).any { name -> normaliseFoodName(name) == normaliseFoodName(mention.name) }
    }
}

internal fun normaliseFoodName(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)
    .replace('’', '\'').replace(Regex("\\s+"), " ")
