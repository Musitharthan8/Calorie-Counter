package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RegionalNutritionEngineTest {
    private fun estimate() = FoodAnalysis("banana", 105, 1.0, 27.0, 0.0, 118.0)

    @Test fun simpleTextFallbackRemainsLoggableAndExplicitlyEstimated() = runBlocking {
        val engine = RegionalNutritionEngine(LocalMealInterpreter(), NutritionResolver(emptyList())) { estimate() }
        val result = engine.analyze("1 banana")
        assertEquals(105, result.calories)
        assertEquals(NutritionSourceKind.AI_ESTIMATE, result.nutritionProvenance.single().source)
        assertTrue(result.nutritionProvenance.single().estimated)
        assertTrue(result.nutritionWarnings.isNotEmpty())
    }

    @Test fun malformedAiOutputFallsBackButCancellationDoesNot() = runBlocking {
        var calls = 0
        val engine = RegionalNutritionEngine(MealInterpreter { error("bad JSON") }, NutritionResolver(emptyList())) {
            calls++; estimate()
        }
        assertEquals(105, engine.analyze("1 banana").calories)
        assertEquals(1, calls)
        try {
            RegionalNutritionEngine(MealInterpreter { throw CancellationException() }, NutritionResolver(emptyList())) {
                fail("Must not estimate after cancellation"); estimate()
            }.analyze("1 banana")
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun materialAmbiguitiesDoNotReachCalorieEstimation() = runBlocking {
        val engine = RegionalNutritionEngine(LocalMealInterpreter(), NutritionResolver(emptyList())) {
            fail("Do not guess bucket size or servings"); estimate()
        }
        try {
            engine.analyze(MealInterpretationTest.COMPLEX_MEAL)
            fail("Expected clarification")
        } catch (clarification: MealClarificationRequired) {
            assertEquals(MealInterpretationTest.COMPLEX_MEAL, clarification.interpretation.rawText)
            assertEquals(2, clarification.interpretation.ambiguities.size)
        }
    }

    @Test fun localSavedFoodResolvesWithoutAiNutritionAndSurvivesJson() = runBlocking {
        val saved = FoodEntry(name = "Amma's chicken curry", calories = 300, protein = 20.0,
            carbs = 10.0, fat = 18.0, servingSizeGrams = 200.0, source = FoodSource.TEXT_INPUT)
        val source = PersonalFoodSource { listOf(saved) }
        val engine = RegionalNutritionEngine(LocalMealInterpreter(), NutritionResolver(listOf(source))) {
            fail("Saved food should resolve locally"); estimate()
        }
        val analysis = engine.analyze("2 servings Amma's chicken curry")
        assertEquals(600, analysis.calories)
        assertEquals(saved.id.toString(), analysis.nutritionProvenance.single().foodId)
        assertEquals(400.0, analysis.servingSizeGrams, 0.0)
        val restored = Json.decodeFromString<FoodAnalysis>(Json.encodeToString(analysis))
        assertEquals(analysis, restored)
        assertEquals(NutritionEvidence.SAVED_FOOD, restored.ingredients.single().nutritionProvenance!!.evidence)
    }

    @Test fun oldEntriesDecodeAndNewProvenanceSurvivesRelogging() {
        val old = Json.decodeFromString<FoodEntry>("""{"name":"banana","calories":105,"protein":1,"carbs":27,"fat":0,"source":"text"}""")
        assertTrue(old.nutritionProvenance.isEmpty())
        val provenance = NutritionProvenance(NutritionSourceKind.USDA, "Fixture", "test-id",
            NutritionEvidence.DATABASE, false, InterpretationConfidence.HIGH, "1 banana", "banana")
        val current = old.copy(
            nutritionProvenance = listOf(provenance),
            sourceNutrients = mapOf("phosphorus" to NutrientAmount(25.0, "mg"))
        )
        val restored = Json.decodeFromString<FoodEntry>(Json.encodeToString(current))
        val relogged = restored.duplicatedForLogging(Instant.now())
        assertEquals(provenance, relogged.nutritionProvenance.single())
        assertEquals(NutrientAmount(25.0, "mg"), relogged.sourceNutrients["phosphorus"])
    }

    @Test fun databaseMicronutrientsMapToLegacyFieldsAndPreserveUnsupportedSourceNutrients() = runBlocking {
        val candidate = NutritionCandidate(
            canonicalName = "dal",
            source = NutritionSourceKind.INDB,
            sourceName = "INDB fixture",
            sourceFoodId = "indb-1",
            evidence = NutritionEvidence.DATABASE,
            nutrition = NutrientValues(
                calories = 120.0,
                protein = 7.0,
                carbs = 18.0,
                fat = 3.0,
                micronutrients = mapOf(
                    "saturatedFat" to NutrientAmount(0.8, "g"),
                    "magnesium" to NutrientAmount(36.0, "mg"),
                    "vitaminA" to NutrientAmount(15.0, "ug"),
                    "freeSugar" to NutrientAmount(1.2, "g"),
                    "phosphorus" to NutrientAmount(95.0, "mg"),
                    "vitaminB6" to NutrientAmount(0.2, "mg"),
                    "niacin" to NutrientAmount(0.7, "mg")
                )
            ),
            referenceQuantity = FoodQuantity(100.0, "g"),
            referenceGrams = 100.0
        )
        val interpretation = MealInterpretation(
            rawText = "100 g dal",
            foods = listOf(
                FoodMention(
                    id = "food-1",
                    name = "dal",
                    originalWording = "100 g dal",
                    quantity = FoodQuantity(100.0, "g"),
                    confidence = InterpretationConfidence.HIGH
                )
            )
        )
        val engine = RegionalNutritionEngine(
            interpreter = LocalMealInterpreter(),
            resolver = NutritionResolver(listOf(InMemoryNutritionSource(listOf(candidate))))
        ) { fail("Complete database grounding must not fall back"); estimate() }

        val analysis = engine.analyzeInterpretation(
            interpretation = interpretation,
            fallbackLabel = interpretation.rawText,
            fallbackEstimate = { fail("Complete database grounding must not fall back"); estimate() }
        )

        assertEquals(0.8, analysis.saturatedFat ?: -1.0, 0.0)
        assertEquals(36.0, analysis.magnesium ?: -1.0, 0.0)
        assertEquals(15.0, analysis.vitaminA ?: -1.0, 0.0)
        assertNull(analysis.sugar)
        assertEquals(NutrientAmount(1.2, "g"), analysis.sourceNutrients["freeSugar"])
        assertEquals(NutrientAmount(95.0, "mg"), analysis.sourceNutrients["phosphorus"])
        assertEquals(NutrientAmount(0.2, "mg"), analysis.sourceNutrients["vitaminB6"])
        assertEquals(NutrientAmount(0.7, "mg"), analysis.sourceNutrients["niacin"])
        val ingredientMicros = analysis.ingredients.single().micronutrients
        assertNotNull(ingredientMicros)
        assertEquals(NutrientAmount(36.0, "mg"), ingredientMicros!!["magnesium"])
        assertEquals(NutrientAmount(95.0, "mg"), ingredientMicros["phosphorus"])

        val restored = Json.decodeFromString<FoodAnalysis>(Json.encodeToString(analysis))
        assertEquals(analysis.sourceNutrients, restored.sourceNutrients)
    }

    @Test fun unsafeAggregateTotalsFallBackInsteadOfOverflowing() = runBlocking {
        fun hugeCandidate(name: String) = NutritionCandidate(
            canonicalName = name,
            source = NutritionSourceKind.USDA,
            sourceName = "USDA fixture",
            sourceFoodId = name,
            evidence = NutritionEvidence.DATABASE,
            nutrition = NutrientValues(
                calories = 1_500_000_000.0,
                protein = 1.0,
                carbs = 1.0,
                fat = 1.0
            ),
            referenceQuantity = FoodQuantity(1.0, "serving")
        )
        val interpretation = MealInterpretation(
            rawText = "1 first food and 1 second food",
            foods = listOf(
                FoodMention("food-1", "first food", "1 first food", FoodQuantity(1.0, "serving")),
                FoodMention("food-2", "second food", "1 second food", FoodQuantity(1.0, "serving"))
            )
        )
        val engine = RegionalNutritionEngine(
            interpreter = LocalMealInterpreter(),
            resolver = NutritionResolver(
                listOf(InMemoryNutritionSource(listOf(
                    hugeCandidate("first food"),
                    hugeCandidate("second food")
                )))
            )
        ) { fail("Text fallback should not be used directly"); estimate() }

        val analysis = engine.analyzeInterpretation(
            interpretation = interpretation,
            fallbackLabel = interpretation.rawText,
            fallbackEstimate = {
                FoodAnalysis("fallback", 500, 20.0, 40.0, 20.0, 1.0)
            }
        )

        assertEquals(500, analysis.calories)
        assertEquals(NutritionSourceKind.AI_ESTIMATE, analysis.nutritionProvenance.single().source)
        assertTrue(analysis.nutritionWarnings.single().contains("combined safely"))
    }

    @Test fun unknownMicronutrientsAreNotInventedAsZeroAndPartialResultsCannotAdapt() = runBlocking {
        val result = NutritionResolver(emptyList()).resolve(LocalMealInterpreter().parse("1 banana"))
        assertTrue(runCatching { result.toFoodAnalysis() }.isFailure)
    }
}
