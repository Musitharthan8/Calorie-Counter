package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.nutrition.InterpretationConfidence
import com.apoorvdarshan.calorietracker.nutrition.NutrientAmount
import com.apoorvdarshan.calorietracker.nutrition.NutritionEvidence
import com.apoorvdarshan.calorietracker.nutrition.NutritionProvenance
import com.apoorvdarshan.calorietracker.nutrition.NutritionSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealIngredientEvidenceTest {
    private fun sourceBackedRice() = MealIngredient(
        name = "Rice",
        grams = 100.0,
        calories = 100,
        protein = 2.0,
        carbs = 20.0,
        fat = 0.5,
        nutritionProvenance = NutritionProvenance(
            source = NutritionSourceKind.USDA,
            sourceName = "USDA fixture",
            evidence = NutritionEvidence.DATABASE,
            estimated = false,
            confidence = InterpretationConfidence.HIGH,
            originalWording = "100 g rice",
            canonicalName = "rice"
        ),
        micronutrients = mapOf(
            "sodium" to NutrientAmount(3.0, "mg")
        )
    )

    @Test
    fun oneDecimalDisplayRoundingKeepsEvidenceForPurePortionEdit() {
        val edited = sourceBackedRice().withUserEdits(
            name = "Rice",
            grams = 150.0,
            calories = 150,
            protein = 3.0,
            carbs = 30.0,
            fat = 0.8
        )

        assertNotNull(edited.micronutrients)
        assertEquals(4.5, edited.micronutrients!!.getValue("sodium").amount, 0.0)
        assertNotNull(edited.nutritionProvenance)
        assertTrue(edited.nutritionProvenance!!.userEdited)
    }

    @Test
    fun macroEditOutsideDisplayRoundingToleranceClearsEvidence() {
        val edited = sourceBackedRice().withUserEdits(
            name = "Rice",
            grams = 150.0,
            calories = 150,
            protein = 3.0,
            carbs = 30.0,
            fat = 0.9
        )

        assertNull(edited.micronutrients)
        assertNull(edited.nutritionProvenance)
    }

    @Test
    fun renamingIngredientClearsEvidence() {
        val edited = sourceBackedRice().withUserEdits(
            name = "Tofu",
            grams = 150.0,
            calories = 150,
            protein = 3.0,
            carbs = 30.0,
            fat = 0.8
        )

        assertNull(edited.micronutrients)
        assertNull(edited.nutritionProvenance)
    }
}
