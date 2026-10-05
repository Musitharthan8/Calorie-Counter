package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombinedMealTest {
    @Test
    fun combinedMediaSurvivesSerializationScalingAndLegacyIngredients() {
        val a = FoodEntry(name = "Apple", calories = 80, protein = 0.0, carbs = 20.0,
            fat = 0.0, timestamp = Instant.ofEpochMilli(1000), source = FoodSource.MANUAL, imageFilename = "apple.jpg",
            additionalImageFilenames = listOf("label.jpg"), emoji = "🍎")
        val b = a.copy(name = "Banana", imageFilename = "banana.jpg", emoji = "🍌")
        val combined = combineFoodEntries(listOf(a, b))
        assertEquals(listOf("apple.jpg", "label.jpg", "banana.jpg"), combined.allImageFilenames)
        assertEquals("banana.jpg", combined.ingredients[1].imageFilename)
        assertEquals("🍌", combined.ingredients[1].scaled(2.0).emoji)
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(FoodEntry.serializer(), json.encodeToString(FoodEntry.serializer(), combined))
        assertEquals(combined, restored)
        val legacy = json.decodeFromString(MealIngredient.serializer(),
            """{"name":"Old","grams":10,"calories":10,"protein":0,"carbs":0,"fat":0}""")
        assertTrue(legacy.allImageFilenames.isEmpty())
        assertEquals(listOf("apple.jpg", "label.jpg"), a.copy(imageFilename = null,
            additionalImageFilenames = emptyList(), ingredients = listOf(a.toMealIngredient())).allImageFilenames)
    }

    @Test
    fun foodEntryMapsToIngredientWithoutFlatteningNested() {
        val entry = FoodEntry(
            name = "Bowl",
            calories = 500,
            protein = 30.0,
            carbs = 40.0,
            fat = 20.0,
            source = FoodSource.MANUAL,
            servingSizeGrams = 350.0,
            ingredients = listOf(
                MealIngredient("Rice", 200.0, 250, 5.0, 50.0, 1.0),
                MealIngredient("Chicken", 150.0, 250, 25.0, 0.0, 10.0)
            )
        )
        val ingredient = entry.toMealIngredient()
        assertEquals("Bowl", ingredient.name)
        assertEquals(350.0, ingredient.grams, 0.001)
        assertEquals(500, ingredient.calories)
        assertEquals(30.0, ingredient.protein, 0.001)
    }

    @Test
    fun analysisMapsToIngredient() {
        val analysis = FoodAnalysis(
            name = "Apple",
            calories = 95,
            protein = 0.5,
            carbs = 25.0,
            fat = 0.3,
            servingSizeGrams = 182.0
        )
        val ingredient = analysis.toMealIngredient()
        assertEquals("Apple", ingredient.name)
        assertEquals(182.0, ingredient.grams, 0.001)
        assertEquals(95, ingredient.calories)
    }

    @Test
    fun combinePreservesComponentMicronutrientsAndSourceAttribution() {
        val provenance = com.apoorvdarshan.calorietracker.nutrition.NutritionProvenance(
            source = com.apoorvdarshan.calorietracker.nutrition.NutritionSourceKind.USDA,
            sourceName = "USDA fixture",
            evidence = com.apoorvdarshan.calorietracker.nutrition.NutritionEvidence.DATABASE,
            estimated = false,
            confidence = com.apoorvdarshan.calorietracker.nutrition.InterpretationConfidence.HIGH,
            originalWording = "100 g food",
            canonicalName = "food"
        )
        val rice = FoodEntry(
            name = "Rice",
            calories = 130,
            protein = 2.0,
            carbs = 28.0,
            fat = 0.3,
            source = FoodSource.TEXT_INPUT,
            servingSizeGrams = 100.0,
            sodium = 3.0,
            sourceNutrients = mapOf(
                "phosphorus" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(40.0, "mg")
            ),
            nutritionProvenance = listOf(provenance.copy(canonicalName = "rice"))
        )
        val chicken = FoodEntry(
            name = "Chicken",
            calories = 165,
            protein = 31.0,
            carbs = 0.0,
            fat = 3.6,
            source = FoodSource.TEXT_INPUT,
            servingSizeGrams = 100.0,
            sodium = 70.0,
            sourceNutrients = mapOf(
                "phosphorus" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(190.0, "mg")
            ),
            nutritionProvenance = listOf(provenance.copy(canonicalName = "chicken"))
        )

        val combined = combineFoodEntries(listOf(rice, chicken))

        assertEquals(73.0, combined.sodium!!, 0.0)
        assertEquals(
            230.0,
            combined.sourceNutrients.getValue("phosphorus").amount,
            0.0
        )
        assertEquals(2, combined.nutritionProvenance.size)
        assertEquals(3.0, combined.ingredients[0].micronutrients!!.getValue("sodium").amount, 0.0)
        assertEquals(70.0, combined.ingredients[1].micronutrients!!.getValue("sodium").amount, 0.0)
        assertEquals(
            com.apoorvdarshan.calorietracker.nutrition.NutritionEvidence.DATABASE,
            combined.ingredients[0].nutritionProvenance!!.evidence
        )
    }

    @Test
    fun combineDoesNotInventMicronutrientsWhenAComponentIsUnknown() {
        val known = FoodEntry(
            name = "Known",
            calories = 100,
            protein = 5.0,
            carbs = 10.0,
            fat = 3.0,
            source = FoodSource.TEXT_INPUT,
            servingSizeGrams = 100.0,
            sodium = 200.0
        )
        val unknown = FoodEntry(
            name = "Unknown",
            calories = 120,
            protein = 6.0,
            carbs = 12.0,
            fat = 4.0,
            source = FoodSource.MANUAL,
            servingSizeGrams = 100.0
        )

        val combined = combineFoodEntries(listOf(known, unknown))

        assertEquals(null, combined.sodium)
        assertTrue(combined.sourceNutrients.isEmpty())
        assertEquals(null, combined.ingredients[1].micronutrients)
    }

    @Test
    fun combineTotalsAndUsesLatestMealMetadata() {
        val older = FoodEntry(
            name = "Eggs",
            calories = 140,
            protein = 12.0,
            carbs = 1.0,
            fat = 10.0,
            timestamp = Instant.parse("2026-09-01T08:00:00Z"),
            source = FoodSource.MANUAL,
            mealType = MealType.BREAKFAST,
            servingSizeGrams = 100.0
        )
        val newer = FoodEntry(
            name = "Toast",
            calories = 120,
            protein = 4.0,
            carbs = 20.0,
            fat = 2.0,
            timestamp = Instant.parse("2026-09-01T08:05:00Z"),
            source = FoodSource.TEXT_INPUT,
            mealType = MealType.LUNCH,
            servingSizeGrams = 50.0
        )
        val combined = combineFoodEntries(listOf(older, newer))
        assertEquals("Eggs + Toast", combined.name)
        assertEquals(260, combined.calories)
        assertEquals(16.0, combined.protein, 0.001)
        assertEquals(21.0, combined.carbs, 0.001)
        assertEquals(12.0, combined.fat, 0.001)
        assertEquals(150.0, combined.servingSizeGrams!!, 0.001)
        assertEquals(MealType.LUNCH, combined.mealType)
        assertEquals(Instant.parse("2026-09-01T08:05:00Z"), combined.timestamp)
        assertEquals(2, combined.ingredients.size)
        assertTrue(combined.id != older.id && combined.id != newer.id)
    }

    @Test
    fun withIngredientsRecomputesParentTotals() {
        val entry = FoodEntry(
            name = "Meal",
            calories = 0,
            protein = 0.0,
            carbs = 0.0,
            fat = 0.0,
            source = FoodSource.MANUAL
        )
        val updated = entry.withIngredients(
            listOf(
                MealIngredient("A", 50.0, 80, 5.0, 6.0, 3.0),
                MealIngredient("B", 70.0, 120, 8.0, 10.0, 4.0)
            )
        )
        assertEquals(200, updated.calories)
        assertEquals(13.0, updated.protein, 0.001)
        assertEquals(16.0, updated.carbs, 0.001)
        assertEquals(7.0, updated.fat, 0.001)
        assertEquals(120.0, updated.servingSizeGrams!!, 0.001)
        assertEquals(2, updated.ingredients.size)
    }
}
