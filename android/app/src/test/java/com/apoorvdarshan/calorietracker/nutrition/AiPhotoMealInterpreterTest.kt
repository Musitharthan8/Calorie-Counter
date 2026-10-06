package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiPhotoMealInterpreterTest {

    @Test fun photoPerceptionRequestsNoNutritionAndMarksVisualGramsEstimated() = runBlocking {
        val interpreter = AiPhotoMealInterpreter { prompt, images ->
            assertEquals(1, images.size)
            assertTrue(prompt.contains("DO NOT return calories"))
            assertTrue(prompt.contains("A visually estimated gram value is an estimate"))
            assertTrue(prompt.contains("components visible"))
            assertTrue(prompt.contains("no skin"))
            """
            {
              "rawText":"model supplied text is ignored",
              "foods":[
                {
                  "id":"food-1",
                  "name":"chicken rice",
                  "originalWording":"visible chicken rice",
                  "quantity":{"amount":320.0,"unit":"g","explicit":false},
                  "brand":null,
                  "region":null,
                  "preparation":null,
                  "modifiers":["no skin"],
                  "parentId":null,
                  "personalFoodName":null,
                  "confidence":"MEDIUM"
                }
              ],
              "ambiguities":[],
              "mealContext":null,
              "confidence":"MEDIUM"
            }
            """.trimIndent()
        }

        val result = interpreter.interpret(
            imageBytesList = listOf(byteArrayOf(1, 2, 3)),
            description = "chicken rice, no skin"
        )

        assertEquals("chicken rice, no skin", result.rawText)
        assertEquals("chicken rice", result.foods.single().name)
        assertEquals(320.0, result.foods.single().quantity?.amount ?: 0.0, 0.0)
        assertEquals("g", result.foods.single().quantity?.unit)
        assertFalse(result.foods.single().quantity?.explicit ?: true)
    }

    @Test fun progressivePromptPreservesScaleDifferenceRules() {
        val prompt = AiPhotoMealInterpreter.prompt(
            description = null,
            progressiveMeal = true
        )
        assertTrue(prompt.contains("chronological progressive-meal sequence"))
        assertTrue(prompt.contains("difference between consecutive readings"))
        assertTrue(prompt.contains("visibly reset/tared"))
    }

    @Test fun promptForbidsAggregatePlusVisibleComponentDoubleCounting() {
        val prompt = AiPhotoMealInterpreter.prompt(
            description = "banana leaf meal",
            progressiveMeal = false
        )
        assertTrue(prompt.contains("output the COMPONENTS ONLY"))
        assertTrue(prompt.contains("Do not also add a calorie-bearing aggregate"))
        assertTrue(prompt.contains("banana leaf meal"))
    }

    @Test fun groundedPhotoInterpretationCanResolveWithoutUsingLegacyPhotoNutrition() = runBlocking {
        val candidate = NutritionCandidate(
            canonicalName = "banana",
            aliases = setOf("banana"),
            source = NutritionSourceKind.USDA,
            sourceName = "USDA fixture",
            sourceFoodId = "fixture-banana",
            datasetVersion = "fixture-1",
            license = "CC0 1.0",
            attribution = "USDA fixture",
            evidence = NutritionEvidence.DATABASE,
            nutrition = NutrientValues(89.0, 1.1, 22.8, 0.3),
            referenceQuantity = FoodQuantity(100.0, "g"),
            referenceGrams = 100.0
        )
        val interpretation = MealInterpretation(
            rawText = "Photo meal",
            foods = listOf(
                FoodMention(
                    id = "food-1",
                    name = "banana",
                    originalWording = "visible banana",
                    quantity = FoodQuantity(118.0, "g", explicit = false),
                    confidence = InterpretationConfidence.MEDIUM
                )
            )
        )
        val engine = RegionalNutritionEngine(
            interpreter = MealInterpreter { throw AssertionError("Text interpreter is not used for photo resolution") },
            resolver = NutritionResolver(
                listOf(InMemoryNutritionSource(listOf(candidate)))
            ),
            estimate = { throw AssertionError("Text estimate is not used for grounded photo resolution") }
        )

        val result = engine.analyzeInterpretation(
            interpretation = interpretation,
            fallbackLabel = "Photo meal",
            fallbackEstimate = {
                fail("Legacy photo nutrition must not run when grounding is complete")
                FoodAnalysis("unreachable", 0, 0.0, 0.0, 0.0, 1.0)
            }
        )

        assertEquals((89.0 * 1.18).toInt(), result.calories)
        assertEquals(NutritionSourceKind.USDA, result.nutritionProvenance.single().source)
        assertEquals(InterpretationConfidence.MEDIUM, result.nutritionProvenance.single().confidence)
    }

    @Test fun unresolvedPhotoFallsBackAndKeepsInterpretation() = runBlocking {
        val interpretation = MealInterpretation(
            rawText = "Photo meal",
            foods = listOf(
                FoodMention(
                    id = "food-1",
                    name = "mystery curry",
                    originalWording = "visible mystery curry",
                    quantity = FoodQuantity(180.0, "g", explicit = false)
                )
            )
        )
        val engine = RegionalNutritionEngine(
            interpreter = MealInterpreter { throw AssertionError("unused") },
            resolver = NutritionResolver(emptyList()),
            estimate = { throw AssertionError("unused") }
        )

        val result = engine.analyzeInterpretation(
            interpretation = interpretation,
            fallbackLabel = "Photo meal",
            fallbackEstimate = {
                FoodAnalysis("mystery curry", 420, 18.0, 32.0, 24.0, 180.0)
            }
        )

        assertEquals(420, result.calories)
        assertEquals(interpretation, result.mealInterpretation)
        assertEquals(NutritionSourceKind.AI_ESTIMATE, result.nutritionProvenance.single().source)
        assertTrue(result.nutritionWarnings.single().contains("AI estimate"))
    }
}
