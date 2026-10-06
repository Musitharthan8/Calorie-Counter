package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.serialization.Serializable

@Serializable
enum class InterpretationConfidence { HIGH, MEDIUM, LOW }

@Serializable
data class FoodQuantity(val amount: Double, val unit: String = "serving", val explicit: Boolean = true) {
    init { require(amount.isFinite() && amount > 0) }
}

/** Names and user wording are deliberately separate. Never translate kopi into generic coffee. */
@Serializable
data class FoodMention(
    val id: String,
    val name: String,
    val originalWording: String,
    val quantity: FoodQuantity? = null,
    val brand: String? = null,
    val region: String? = null,
    val preparation: String? = null,
    val modifiers: List<String> = emptyList(),
    val parentId: String? = null,
    val personalFoodName: String? = null,
    val confidence: InterpretationConfidence = InterpretationConfidence.MEDIUM
)

@Serializable
data class MealAmbiguity(
    val wording: String,
    val question: String,
    val mentionIds: List<String>,
    val affectsNutrition: Boolean = true
)

@Serializable
data class MealInterpretation(
    val rawText: String,
    val foods: List<FoodMention>,
    val ambiguities: List<MealAmbiguity> = emptyList(),
    val mealContext: String? = null,
    val confidence: InterpretationConfidence = InterpretationConfidence.MEDIUM
) {
    fun validated(): MealInterpretation {
        require(rawText.isNotBlank() && rawText.length <= 16000)
        require(foods.isNotEmpty() && foods.size <= 40)
        val ids = foods.map { it.id }.toSet()
        require(ids.size == foods.size && ids.none { it.isBlank() })
        foods.forEach { food ->
            require(food.name.isNotBlank() && food.originalWording.isNotBlank())
            require(food.quantity?.unit?.isNotBlank() != false)
            require(food.parentId == null || food.parentId in ids)
            val visited = mutableSetOf(food.id)
            var parent = food.parentId
            while (parent != null) {
                require(visited.add(parent)) { "Cyclic food grouping" }
                parent = foods.first { it.id == parent }.parentId
            }
        }
        require(ambiguities.all { it.question.isNotBlank() && it.mentionIds.all(ids::contains) })
        return this
    }
}

fun interface MealInterpreter {
    suspend fun interpret(rawText: String): MealInterpretation
}

/** A clarification is recoverable: retain the original input and let the user edit it. */
class MealClarificationRequired(val interpretation: MealInterpretation) : Exception(
    interpretation.ambiguities.filter { it.affectsNutrition }.joinToString("\n") { it.question }
)
