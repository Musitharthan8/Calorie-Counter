package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsNutritionSourceTest {
    private fun hit(
        name: String = "Protein Bar",
        brand: String? = "BrandX",
        servingGrams: Double? = 50.0
    ) = OpenFoodFactsNutritionSource.SearchHit(
        barcode = "1234567890123",
        name = name,
        brand = brand,
        caloriesPer100g = 400.0,
        proteinPer100g = 20.0,
        carbsPer100g = 40.0,
        fatPer100g = 10.0,
        servingGrams = servingGrams
    )

    @Test fun strongBrandedMatchResolvesAndScalesByServing() = runBlocking {
        val source = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ -> listOf(hit()) }
        )
        val mention = FoodMention(
            id = "1",
            name = "Protein Bar",
            originalWording = "2 servings BrandX Protein Bar",
            quantity = FoodQuantity(2.0, "serving"),
            brand = "BrandX",
            confidence = InterpretationConfidence.HIGH
        )
        val result = NutritionResolver(listOf(source))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertTrue(result.complete)
        val match = result.matches.single()
        assertEquals(NutritionSourceKind.OPEN_FOOD_FACTS, match.provenance.source)
        assertEquals(400.0, match.nutrition.calories, 0.0)
        assertEquals(100.0, match.grams ?: 0.0, 0.0)
        assertFalse(match.provenance.estimated)
    }

    @Test fun unknownServingKeepsPer100gBasisForExplicitGramInput() = runBlocking {
        val source = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ -> listOf(hit(servingGrams = null)) }
        )
        val mention = FoodMention(
            id = "1",
            name = "Protein Bar",
            originalWording = "50 g BrandX Protein Bar",
            quantity = FoodQuantity(50.0, "g"),
            brand = "BrandX",
            confidence = InterpretationConfidence.HIGH
        )
        val result = NutritionResolver(listOf(source))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertTrue(result.complete)
        assertEquals(200.0, result.matches.single().nutrition.calories, 0.0)
        assertEquals(50.0, result.matches.single().grams ?: 0.0, 0.0)
    }

    @Test fun wrongBrandAndWeakNamesAreRejectedInsteadOfForced() = runBlocking {
        val wrongBrand = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ -> listOf(hit(brand = "OtherBrand")) }
        )
        val brandedMention = FoodMention(
            id = "1",
            name = "Protein Bar",
            originalWording = "1 serving BrandX Protein Bar",
            quantity = FoodQuantity(1.0, "serving"),
            brand = "BrandX"
        )
        assertTrue(wrongBrand.search(brandedMention).isEmpty())

        val weakName = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ -> listOf(hit(name = "Chocolate Drink", brand = null)) }
        )
        assertTrue(weakName.search(brandedMention.copy(brand = null)).isEmpty())
    }

    @Test fun unbrandedTextNeverAutoSelectsACommunityProduct() = runBlocking {
        var calls = 0
        val source = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ ->
                calls += 1
                listOf(hit())
            }
        )
        val mention = FoodMention(
            id = "1",
            name = "Protein Bar",
            originalWording = "1 protein bar",
            quantity = FoodQuantity(1.0, "serving")
        )

        assertTrue(source.search(mention).isEmpty())
        assertEquals(0, calls)
    }

    @Test fun repeatedLookupUsesLocalCache() = runBlocking {
        var calls = 0
        val source = OpenFoodFactsNutritionSource(
            searchOverride = { _, _, _ ->
                calls += 1
                listOf(hit())
            }
        )
        val mention = FoodMention(
            "1",
            "Protein Bar",
            "1 BrandX Protein Bar",
            FoodQuantity(1.0, "serving"),
            brand = "BrandX"
        )
        source.search(mention)
        source.search(mention)
        assertEquals(1, calls)
    }
}
