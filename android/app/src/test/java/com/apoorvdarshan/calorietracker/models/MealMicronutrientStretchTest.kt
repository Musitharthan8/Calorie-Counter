package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealMicronutrientStretchTest {
    @Test
    fun factorUsesNewOverOldWhenBothPositive() {
        assertEquals(2.0, MealMicronutrientStretch.factor(100.0, 200.0)!!, 0.0)
        assertEquals(0.5, MealMicronutrientStretch.factor(200.0, 100.0)!!, 0.0)
    }

    @Test
    fun factorIsNullWhenOldGramsAreNotPositiveAndZeroWhenNewGramsAreNot() {
        assertNull(MealMicronutrientStretch.factor(0.0, 120.0))
        assertEquals(0.0, MealMicronutrientStretch.factor(120.0, 0.0)!!, 0.0)
        assertNull(MealMicronutrientStretch.factor(-10.0, 50.0))
        assertEquals(0.0, MealMicronutrientStretch.factor(50.0, -10.0)!!, 0.0)
    }

    @Test
    fun scaleDoublesAndHalvesOptionalValuesAndLeavesNulls() {
        assertEquals(20.0, MealMicronutrientStretch.scale(10.0, 2.0)!!, 0.0)
        assertEquals(5.0, MealMicronutrientStretch.scale(10.0, 0.5)!!, 0.0)
        assertNull(MealMicronutrientStretch.scale(null, 2.0))
    }

    @Test
    fun scaleStretchesSupplementalMapAndKeepsEmptyMaps() {
        val scaled = MealMicronutrientStretch.scale(
            mapOf("creatine" to 5.0, "beta_alanine" to 3.2),
            2.0
        )
        assertEquals(10.0, scaled.getValue("creatine"), 0.0)
        assertEquals(6.4, scaled.getValue("beta_alanine"), 0.0)
        assertEquals(emptyMap<String, Double>(), MealMicronutrientStretch.scale(emptyMap(), 2.0))
    }

    @Test
    fun snapshotStretchesPresentMicrosAndLeavesNulls() {
        val stretched = MealMicronutrientSnapshot(
            sugar = 8.0,
            fiber = 4.0,
            sodium = 200.0,
            vitaminC = 12.0,
            omega3 = 1.5,
            supplementalNutrients = mapOf("creatine" to 2.0)
        ).stretched(100.0, 50.0)

        assertEquals(4.0, stretched.sugar!!, 0.0)
        assertEquals(2.0, stretched.fiber!!, 0.0)
        assertEquals(100.0, stretched.sodium!!, 0.0)
        assertEquals(6.0, stretched.vitaminC!!, 0.0)
        assertEquals(0.75, stretched.omega3!!, 0.0)
        assertEquals(1.0, stretched.supplementalNutrients.getValue("creatine"), 0.0)
        assertNull(stretched.addedSugar)
        assertNull(stretched.iron)
    }

    @Test
    fun snapshotSkipsStretchWhenOldGramsAreZeroAndClearsWhenNewGramsAreZero() {
        val original = MealMicronutrientSnapshot(sugar = 8.0, sodium = 200.0)
        assertEquals(original, original.stretched(0.0, 150.0))
        val cleared = original.stretched(150.0, 0.0)
        assertEquals(0.0, cleared.sugar!!, 0.0)
        assertEquals(0.0, cleared.sodium!!, 0.0)
        assertNull(cleared.fiber)
    }

    @Test
    fun componentMicronutrientsRecomputeWhenIngredientProportionsChange() {
        val rice = MealIngredient(
            name = "Rice",
            grams = 150.0,
            calories = 195,
            protein = 4.0,
            carbs = 42.0,
            fat = 0.5,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(5.0, "mg"),
                "iron" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(1.0, "mg"),
                "phosphorus" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(40.0, "mg")
            )
        )
        val chicken = MealIngredient(
            name = "Chicken",
            grams = 100.0,
            calories = 165,
            protein = 31.0,
            carbs = 0.0,
            fat = 3.6,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(70.0, "mg"),
                "iron" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(0.5, "mg"),
                "phosphorus" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(190.0, "mg")
            )
        )
        val entry = FoodEntry(
            name = "Rice and chicken",
            calories = 360,
            protein = 35.0,
            carbs = 42.0,
            fat = 4.1,
            source = FoodSource.SNAP_FOOD,
            sodium = 75.0,
            iron = 1.5,
            sourceNutrients = mapOf(
                "phosphorus" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(230.0, "mg")
            ),
            servingSizeGrams = 250.0,
            ingredients = listOf(rice, chicken)
        )

        val doubledChicken = chicken.scaled(2.0)
        val updated = entry.applyingIngredientChanges(listOf(rice, doubledChicken))

        assertEquals(145.0, updated.sodium!!, 0.0001)
        assertEquals(2.0, updated.iron!!, 0.0001)
        assertEquals(
            420.0,
            updated.sourceNutrients.getValue("phosphorus").amount,
            0.0001
        )
        // Old total-mass stretching would have incorrectly produced 105 mg sodium and 322 mg phosphorus.
    }

    @Test
    fun componentMicronutrientAggregationKeepsMissingValuesUnknown() {
        val rice = MealIngredient(
            "Rice", 100.0, 130, 2.0, 28.0, 0.3,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(3.0, "mg"),
                "iron" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(1.0, "mg")
            )
        )
        val curry = MealIngredient(
            "Curry", 100.0, 160, 8.0, 10.0, 9.0,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(300.0, "mg")
            )
        )

        val totals = listOf(rice, curry).micronutrientTotalsOrNull()
        requireNotNull(totals)
        assertEquals(303.0, totals.snapshot.sodium!!, 0.0)
        assertNull(totals.snapshot.iron)
    }

    @Test
    fun missingComponentSnapshotsFallBackToLegacyMealStretch() {
        val rice = MealIngredient("Rice", 100.0, 130, 2.0, 28.0, 0.3)
        val curry = MealIngredient("Curry", 100.0, 160, 8.0, 10.0, 9.0)
        assertNull(listOf(rice, curry).micronutrientTotalsOrNull())

        val entry = FoodEntry(
            name = "Legacy meal",
            calories = 290,
            protein = 10.0,
            carbs = 38.0,
            fat = 9.3,
            source = FoodSource.SNAP_FOOD,
            sodium = 400.0,
            servingSizeGrams = 200.0,
            ingredients = listOf(rice, curry)
        )
        val updated = entry.applyingIngredientChanges(listOf(rice, curry.scaled(2.0)))
        assertEquals(600.0, updated.sodium!!, 0.0001)
    }

    @Test
    fun invalidatedComponentEvidenceClearsParentMicronutrientsInsteadOfStretchingThem() {
        val rice = MealIngredient(
            "Rice", 100.0, 130, 2.0, 28.0, 0.3,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(3.0, "mg")
            )
        )
        val chicken = MealIngredient(
            "Chicken", 100.0, 165, 31.0, 0.0, 3.6,
            micronutrients = mapOf(
                "sodium" to com.apoorvdarshan.calorietracker.nutrition.NutrientAmount(70.0, "mg")
            )
        )
        val entry = FoodEntry(
            name = "Grounded meal",
            calories = 295,
            protein = 33.0,
            carbs = 28.0,
            fat = 3.9,
            source = FoodSource.TEXT_INPUT,
            sodium = 73.0,
            servingSizeGrams = 200.0,
            ingredients = listOf(rice, chicken)
        )
        val manuallyChangedChicken = chicken.withUserEdits(
            name = "Tofu",
            grams = 100.0,
            calories = 165,
            protein = 31.0,
            carbs = 0.0,
            fat = 3.6
        )

        val updated = entry.applyingIngredientChanges(listOf(rice, manuallyChangedChicken))

        assertNull(updated.sodium)
        assertTrue(updated.sourceNutrients.isEmpty())
    }

    @Test
    fun applyingIngredientChangesRewritesBaseMicrosByIngredientGramsRatio() {
        val rice = MealIngredient("Rice", 150.0, 195, 4.0, 42.0, 0.5)
        val chicken = MealIngredient("Chicken", 100.0, 165, 31.0, 0.0, 3.6)
        val entry = FoodEntry(
            name = "Bowl",
            calories = 360,
            protein = 35.0,
            carbs = 42.0,
            fat = 4.1,
            source = FoodSource.SNAP_FOOD,
            sugar = 6.0,
            fiber = 3.0,
            sodium = 400.0,
            vitaminA = 80.0,
            omega3 = 0.4,
            supplementalNutrients = mapOf("creatine" to 1.0),
            servingSizeGrams = 250.0,
            ingredients = listOf(rice, chicken)
        )

        val doubledChicken = chicken.scaled(2.0)
        val updated = entry.applyingIngredientChanges(listOf(rice, doubledChicken))

        assertEquals(350.0, updated.ingredients.totals().grams, 0.001)
        assertEquals(195 + doubledChicken.calories, updated.calories)
        assertEquals(8.4, updated.sugar!!, 0.0001)
        assertEquals(4.2, updated.fiber!!, 0.0001)
        assertEquals(560.0, updated.sodium!!, 0.0001)
        assertEquals(112.0, updated.vitaminA!!, 0.0001)
        assertEquals(0.56, updated.omega3!!, 0.0001)
        assertEquals(1.4, updated.supplementalNutrients.getValue("creatine"), 0.0001)
        assertEquals(350.0, updated.servingSizeGrams!!, 0.001)
        assertNull(updated.addedSugar)
    }

    @Test
    fun applyingIngredientChangesLeavesMicrosWhenOldGramsAreZeroAndClearsWhenEmptied() {
        val chicken = MealIngredient("Chicken", 100.0, 165, 31.0, 0.0, 3.6)
        val withoutIngredients = FoodEntry(
            name = "Bowl",
            calories = 360,
            protein = 35.0,
            carbs = 42.0,
            fat = 4.1,
            source = FoodSource.MANUAL,
            sugar = 6.0,
            sodium = 400.0,
            servingSizeGrams = 250.0
        )
        val added = withoutIngredients.applyingIngredientChanges(listOf(chicken))
        assertEquals(6.0, added.sugar!!, 0.0)
        assertEquals(400.0, added.sodium!!, 0.0)
        assertEquals(100.0, added.servingSizeGrams!!, 0.0)

        val emptied = added.applyingIngredientChanges(emptyList())
        assertEquals(0.0, emptied.sugar!!, 0.0)
        assertEquals(0.0, emptied.sodium!!, 0.0)
        assertEquals(100.0, emptied.servingSizeGrams!!, 0.0)
        assertEquals(0, emptied.calories)
    }
}
