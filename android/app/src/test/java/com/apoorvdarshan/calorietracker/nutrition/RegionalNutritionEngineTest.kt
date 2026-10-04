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
        val current = old.copy(nutritionProvenance = listOf(provenance))
        val restored = Json.decodeFromString<FoodEntry>(Json.encodeToString(current))
        assertEquals(provenance, restored.duplicatedForLogging(Instant.now()).nutritionProvenance.single())
    }

    @Test fun unknownMicronutrientsAreNotInventedAsZeroAndPartialResultsCannotAdapt() = runBlocking {
        val result = NutritionResolver(emptyList()).resolve(LocalMealInterpreter().parse("1 banana"))
        assertTrue(runCatching { result.toFoodAnalysis() }.isFailure)
    }
}
