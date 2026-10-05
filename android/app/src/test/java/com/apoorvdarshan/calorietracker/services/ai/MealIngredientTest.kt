package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.totals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealIngredientTest {
    @Test
    fun ingredientListReplacesDisagreedMealMacros() {
        val analysis = FoodAnalysis(
            name = "Plate",
            calories = 820,
            protein = 40.0,
            carbs = 90.0,
            fat = 30.0,
            servingSizeGrams = 500.0,
            ingredients = listOf(
                MealIngredient("Chicken", 150.0, 250, 40.0, 0.0, 8.0),
                MealIngredient("Rice", 200.0, 300, 6.0, 66.0, 1.0),
                MealIngredient("Sauce", 40.0, 90, 1.0, 4.0, 8.0)
            )
        ).withIngredientMacroTotals()

        assertEquals(640, analysis.calories)
        assertEquals(47.0, analysis.protein, 0.001)
        assertEquals(70.0, analysis.carbs, 0.001)
        assertEquals(17.0, analysis.fat, 0.001)
    }

    @Test
    fun emptyIngredientListKeepsMealMacros() {
        val analysis = FoodAnalysis(
            name = "Banana",
            calories = 105,
            protein = 1.0,
            carbs = 27.0,
            fat = 0.0,
            servingSizeGrams = 118.0
        ).withIngredientMacroTotals()

        assertEquals(105, analysis.calories)
        assertEquals(1.0, analysis.protein, 0.001)
    }

    @Test
    fun scalingAndTotalsRecalculateMealMacros() {
        val ingredients = listOf(
            MealIngredient("Rice", 150.0, 195, 4.0, 42.0, 0.5),
            MealIngredient("Chicken", 100.0, 165, 31.0, 0.0, 3.6)
        ).map { it.scaled(0.5) }
        val totals = ingredients.totals()

        assertEquals(125.0, totals.grams, 0.001)
        assertEquals(181, totals.calories)
        assertEquals(17.5, totals.protein, 0.001)
    }

    @Test
    fun ingredientScalingPreservesAndScalesMicronutrientEvidence() {
        val provenance = com.apoorvdarshan.calorietracker.nutrition.NutritionProvenance(
            source = com.apoorvdarshan.calorietracker.nutrition.NutritionSourceKind.USDA,
            sourceName = "USDA fixture",
            evidence = com.apoorvdarshan.calorietracker.nutrition.NutritionEvidence.DATABASE,
            estimated = false,
            confidence = com.apoorvdarshan.calorietracker.nutrition.InterpretationConfidence.HIGH,
            originalWording = "100 g rice",
            canonicalName = "rice"
        )
        val ingredient = MealIngredient(
            name = "Rice",
            grams = 100.0,
            calories = 130,
            protein = 2.0,
            carbs = 28.0,
            fat = 0.3,
            nutritionProvenance = provenance,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(3.0, "mg")
            )
        )

        val doubled = ingredient.scaled(2.0)

        assertEquals(200.0, doubled.grams, 0.0)
        assertEquals(6.0, doubled.micronutrients!!.getValue("sodium").amount, 0.0)
        assertEquals(provenance, doubled.nutritionProvenance)
    }

    @Test
    fun ingredientMathRejectsOverflowAndNonFiniteScaling() {
        val ingredient = MealIngredient("Food", 100.0, Int.MAX_VALUE, 10.0, 10.0, 10.0)

        assertTrue(runCatching { ingredient.scaled(2.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ingredient.scaled(Double.POSITIVE_INFINITY) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { listOf(ingredient, ingredient).totals() }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun oldFoodEntryJsonWithoutIngredientsStillDecodes() {
        val json = """{"name":"Apple","calories":95,"protein":0.5,"carbs":25.0,"fat":0.3,"source":"manual"}"""
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString<FoodEntry>(json)

        assertTrue(entry.ingredients.isEmpty())
        assertEquals("Apple", entry.name)
        assertEquals(FoodSource.MANUAL, entry.source)
        assertFalse(entry.progressiveMeal)
    }

    @Test
    fun oldIngredientJsonWithoutMicronutrientSnapshotStillDecodes() {
        val format = Json { ignoreUnknownKeys = true }
        val ingredient = format.decodeFromString<MealIngredient>(
            """{"name":"Rice","grams":100.0,"calories":130,"protein":2.0,"carbs":28.0,"fat":0.3}"""
        )

        assertEquals("Rice", ingredient.name)
        assertEquals(null, ingredient.micronutrients)
        assertEquals(null, ingredient.nutritionProvenance)
    }

    @Test
    fun progressiveMealModeSurvivesFoodEntryRoundTrip() {
        val format = Json { ignoreUnknownKeys = true }
        val original = FoodEntry(
            name = "Progressive bowl",
            calories = 400,
            protein = 30.0,
            carbs = 45.0,
            fat = 12.0,
            source = FoodSource.SNAP_FOOD,
            progressiveMeal = true
        )

        val decoded = format.decodeFromString<FoodEntry>(format.encodeToString(original))

        assertTrue(decoded.progressiveMeal)
    }

    @Test
    fun productMetadataSurvivesFoodEntryRoundTripAndDuplication() {
        val format = Json { ignoreUnknownKeys = true }
        val metadata = FoodProductMetadata(
            barcode = "3017620422003",
            packageQuantity = "400 g",
            ingredientsText = "Sugar, palm oil, hazelnuts",
            allergens = listOf("Milk", "Hazelnuts"),
            traces = listOf("Soy"),
            nutriScore = "E",
            novaGroup = 4,
            ecoScore = "D",
            labels = listOf("Vegetarian"),
            categories = listOf("Spreads"),
            imageUrl = "https://images.openfoodfacts.org/product.jpg"
        )
        val original = FoodEntry(
            name = "Hazelnut spread",
            calories = 539,
            protein = 6.3,
            carbs = 57.5,
            fat = 30.9,
            source = FoodSource.BARCODE,
            productMetadata = metadata
        )

        val decoded = format.decodeFromString<FoodEntry>(format.encodeToString(original))
        val duplicated = decoded.duplicatedForLogging(Instant.parse("2026-08-30T12:00:00Z"))

        assertEquals(metadata, decoded.productMetadata)
        assertEquals(metadata, duplicated.productMetadata)
    }

    @Test
    fun foodParserRejectsMissingOrNegativeRequiredNutrition() {
        val missingFat = """{"name":"Meal","calories":500,"protein":20,"carbs":50}"""
        val negativeProtein = """{"name":"Meal","calories":500,"protein":-1,"carbs":50,"fat":20}"""

        assertTrue(runCatching { FoodJsonParser.parseFoodResponse(missingFat) }.exceptionOrNull() === AiError.InvalidResponse)
        assertTrue(runCatching { FoodJsonParser.parseFoodResponse(negativeProtein) }.exceptionOrNull() === AiError.InvalidResponse)
    }

    @Test
    fun foodParserRejectsOverflowingCaloriesAndInvalidServingMass() {
        val hugeCalories = """{"name":"Meal","calories":999999999999,"protein":20,"carbs":50,"fat":20}"""
        val badServing = """{"name":"Meal","calories":500,"protein":20,"carbs":50,"fat":20,"serving_size_grams":-10}"""

        assertTrue(runCatching { FoodJsonParser.parseFoodResponse(hugeCalories) }.exceptionOrNull() === AiError.InvalidResponse)
        assertTrue(runCatching { FoodJsonParser.parseFoodResponse(badServing) }.exceptionOrNull() === AiError.InvalidResponse)
    }

    @Test
    fun invalidIngredientPayloadCannotSaturateMealCalories() {
        val json = """
            {
              "name":"Meal",
              "calories":500,
              "protein":20,
              "carbs":50,
              "fat":20,
              "ingredients":[
                {"name":"bad","grams":100,"calories":999999999999,"protein":1,"carbs":1,"fat":1}
              ]
            }
        """.trimIndent()

        val analysis = FoodJsonParser.parseFoodResponse(json).analysis

        assertTrue(analysis.ingredients.isEmpty())
        assertEquals(500, analysis.calories)
    }

    @Test
    fun nutritionLabelParserRejectsNegativeValuesAndInvalidServingMass() {
        val negativeFat = """
            {"name":"Label","calories_per_100g":100,"protein_per_100g":5,"carbs_per_100g":15,"fat_per_100g":-1}
        """.trimIndent()
        val badServing = """
            {"name":"Label","calories_per_100g":100,"protein_per_100g":5,"carbs_per_100g":15,"fat_per_100g":2,"serving_size_grams":0}
        """.trimIndent()

        assertTrue(runCatching { FoodJsonParser.parseLabelResponse(negativeFat) }.exceptionOrNull() === AiError.InvalidResponse)
        assertTrue(runCatching { FoodJsonParser.parseLabelResponse(badServing) }.exceptionOrNull() === AiError.InvalidResponse)
    }

    @Test
    fun nutritionLabelScalingRejectsUnsafeTotals() {
        val label = NutritionLabelAnalysis(
            name = "Label",
            caloriesPer100g = Int.MAX_VALUE.toDouble(),
            proteinPer100g = 1.0,
            carbsPer100g = 1.0,
            fatPer100g = 1.0
        )

        assertTrue(runCatching { label.scaled(200.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { label.scaled(Double.POSITIVE_INFINITY) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun progressiveMealPromptUsesChronologicalScaleDifferences() {
        val prompt = multiPhotoAnalysisPrompt(
            progressiveMeal = true,
            description = "The plate stays on the scale"
        )

        assertTrue(prompt.contains("chronological progressive-meal sequence"))
        assertTrue(prompt.contains("current scale total minus the previous scale total"))
        assertTrue(prompt.contains("The plate stays on the scale"))
        assertFalse(prompt.contains("Treat the photos as multiple views"))
    }

    @Test
    fun standardMultiPhotoPromptKeepsMultipleViewBehavior() {
        val prompt = multiPhotoAnalysisPrompt(progressiveMeal = false)

        assertTrue(prompt.contains("Treat the photos as multiple views"))
        assertFalse(prompt.contains("chronological progressive-meal sequence"))
    }
}
