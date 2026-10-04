package com.apoorvdarshan.calorietracker.services.ai

import org.json.JSONObject

/**
 * A nutrition-free interpretation of a natural-language meal description.
 *
 * This deliberately separates "what did the user eat?" from "how many calories was it?".
 * The first pass may infer linguistic structure, brands, cuisine and quantities, but it must
 * never invent nutrition values. A later resolver can then match each [MealMention] against
 * evidence-backed food sources.
 */
data class MealInterpretation(
    val originalText: String,
    val items: List<MealMention>,
    val ambiguities: List<MealAmbiguity> = emptyList(),
    val localeHints: List<String> = emptyList()
) {
    val needsClarification: Boolean
        get() = ambiguities.any { it.requiresClarification }
}

data class MealMention(
    /** The food wording the user actually used, normalised only for whitespace. */
    val surfaceName: String,
    /** A search-friendly name. Cultural dish names should be preserved, not Westernised. */
    val canonicalName: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val servingCount: Double? = null,
    val brand: String? = null,
    val preparation: String? = null,
    val mealContext: String? = null,
    val notes: List<String> = emptyList()
)

data class MealAmbiguity(
    val phrase: String,
    val reason: String,
    val candidateTargets: List<String> = emptyList(),
    val requiresClarification: Boolean = true
)

/** Prompt used for the meal-understanding pass. It intentionally asks for zero nutrition. */
internal object MealInterpretationPrompt {
    fun build(description: String, localeHint: String? = null): String {
        val localeLine = localeHint
            ?.takeIf { it.isNotBlank() }
            ?.let { "User locale/context hint: $it" }
            ?: "User locale/context hint: unknown. Infer cuisine only when the wording strongly supports it."

        return """
            You are the meal-understanding stage of a food logging app.
            Your ONLY job is to convert the user's natural-language description into structured food mentions.

            IMPORTANT:
            - DO NOT estimate calories, macros, micronutrients, grams, or nutrition values.
            - DO NOT silently replace culturally specific dishes with generic Western equivalents.
            - Preserve names such as kopi, teh tarik, nasi lemak, banana leaf meal, sambar, rendang,
              tahu/tauhu sambal, prata/roti canai, thosai/dosa, cai png/economy rice, etc.
            - Keep brands and restaurant/product names when present.
            - Parse explicit quantities and units exactly when possible.
            - Resolve grammatical scope carefully. A trailing quantity may refer to only the nearest food,
              a dish group, or the whole meal. If that changes the likely nutrition materially and the scope
              is not clear, record an ambiguity instead of guessing.
            - Do not invent portion sizes. If the user says "a bucket", preserve unit="bucket" and leave the
              numeric quantity null unless a number was stated.
            - Separate genuinely distinct foods/components that could be matched to different nutrition records.
            - Do not explode a named dish into speculative recipe ingredients that the user did not state.
            - If a meal name contains explicit components (for example a banana leaf meal WITH sambar,
              tahu sambal and chicken rendang), keep the main meal and the explicitly named components as items.

            $localeLine

            Return ONLY JSON with this shape:
            {
              "items": [
                {
                  "surface_name": "kopi",
                  "canonical_name": "kopi",
                  "quantity": 2.0,
                  "unit": "cup",
                  "serving_count": null,
                  "brand": null,
                  "preparation": null,
                  "meal_context": null,
                  "notes": []
                }
              ],
              "ambiguities": [
                {
                  "phrase": "3 servings",
                  "reason": "The phrase could modify more than one preceding food/group.",
                  "candidate_targets": ["chicken rendang", "banana leaf meal"],
                  "requires_clarification": true
                }
              ],
              "locale_hints": ["Singapore", "Malaysia", "South Indian"]
            }

            Use null for unknown scalar fields and [] for empty arrays.

            User description:
            $description
        """.trimIndent()
    }
}

internal object MealInterpretationJsonParser {
    fun parse(originalText: String, response: String): MealInterpretation {
        val json = runCatching { JSONObject(FoodJsonParser.extractJson(response)) }.getOrNull()
            ?: throw AiError.InvalidResponse

        val itemsArray = json.optJSONArray("items") ?: throw AiError.InvalidResponse
        val items = buildList {
            for (index in 0 until minOf(itemsArray.length(), 40)) {
                val item = itemsArray.optJSONObject(index) ?: continue
                val surfaceName = item.nullableString("surface_name") ?: continue
                add(
                    MealMention(
                        surfaceName = surfaceName,
                        canonicalName = item.nullableString("canonical_name"),
                        quantity = item.nullableDouble("quantity"),
                        unit = item.nullableString("unit"),
                        servingCount = item.nullableDouble("serving_count"),
                        brand = item.nullableString("brand"),
                        preparation = item.nullableString("preparation"),
                        mealContext = item.nullableString("meal_context"),
                        notes = item.stringArray("notes")
                    )
                )
            }
        }
        if (items.isEmpty()) throw AiError.InvalidResponse

        val ambiguities = buildList {
            val array = json.optJSONArray("ambiguities") ?: return@buildList
            for (index in 0 until minOf(array.length(), 20)) {
                val item = array.optJSONObject(index) ?: continue
                val phrase = item.nullableString("phrase") ?: continue
                val reason = item.nullableString("reason") ?: continue
                add(
                    MealAmbiguity(
                        phrase = phrase,
                        reason = reason,
                        candidateTargets = item.stringArray("candidate_targets"),
                        requiresClarification = if (item.has("requires_clarification")) {
                            item.optBoolean("requires_clarification", true)
                        } else {
                            true
                        }
                    )
                )
            }
        }

        return MealInterpretation(
            originalText = originalText,
            items = items,
            ambiguities = ambiguities,
            localeHints = json.stringArray("locale_hints")
        )
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.nullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0 }
            is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            else -> null
        }
    }

    private fun JSONObject.stringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), 40)) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
