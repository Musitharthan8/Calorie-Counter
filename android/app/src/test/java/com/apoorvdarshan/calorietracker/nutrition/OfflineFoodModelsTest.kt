package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFoodModelsTest {
    private val manifest = NutritionDatasetManifest(
        source = NutritionSourceKind.USDA,
        datasetName = "USDA test fixture",
        datasetVersion = "fixture-1",
        sourceUrl = "https://fdc.nal.usda.gov/",
        license = "CC0 1.0",
        attribution = "USDA FoodData Central",
        generatedAtUtc = "2026-10-04T00:00:00Z",
        recordCount = 1,
        sha256 = "a".repeat(64)
    )

    private fun record(
        name: String = "Banana, raw",
        aliases: Set<String> = setOf("banana"),
        portions: List<CanonicalFoodPortion> = listOf(
            CanonicalFoodPortion(
                amount = 1.0,
                unit = "medium",
                grams = 118.0,
                description = "1 medium banana",
                isDefault = true
            )
        ),
        brand: String? = null
    ) = CanonicalFoodRecord(
        source = NutritionSourceKind.USDA,
        datasetName = manifest.datasetName,
        datasetVersion = manifest.datasetVersion,
        sourceFoodId = "173944",
        canonicalName = name,
        aliases = aliases,
        brand = brand,
        nutritionPer100g = NutrientValues(
            calories = 89.0,
            protein = 1.09,
            carbs = 22.84,
            fat = 0.33,
            micronutrients = mapOf("potassium" to NutrientAmount(358.0, "mg"))
        ),
        portions = portions,
        sourceUrl = "https://fdc.nal.usda.gov/fdc-app.html#/food-details/173944/nutrients"
    )

    private fun source(records: List<CanonicalFoodRecord>) =
        CanonicalOfflineNutritionSource(
            sourceKind = NutritionSourceKind.USDA,
            manifest = manifest.copy(recordCount = records.size),
            index = OfflineFoodIndex { _, _ -> records }
        )

    @Test fun explicitGramsScaleFromPer100gAndCarryDatasetProvenance() = runBlocking {
        val mention = FoodMention(
            id = "1",
            name = "banana",
            originalWording = "50 g banana",
            quantity = FoodQuantity(50.0, "g"),
            confidence = InterpretationConfidence.HIGH
        )
        val result = NutritionResolver(listOf(source(listOf(record()))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertTrue(result.complete)
        val match = result.matches.single()
        assertEquals(44.5, match.nutrition.calories, 0.0001)
        assertEquals(50.0, match.grams ?: 0.0, 0.0001)
        assertEquals("fixture-1", match.provenance.datasetVersion)
        assertEquals("CC0 1.0", match.provenance.license)
        assertEquals("USDA FoodData Central", match.provenance.attribution)
        assertEquals(179.0, match.nutrition.micronutrients.getValue("potassium").amount, 0.0001)
    }

    @Test fun sourceDefaultPortionCanBackAnExplicitServingWithoutInventingItsName() = runBlocking {
        val mention = FoodMention(
            id = "1",
            name = "banana",
            originalWording = "1 banana",
            quantity = FoodQuantity(1.0, "serving"),
            confidence = InterpretationConfidence.HIGH
        )
        val result = NutritionResolver(listOf(source(listOf(record()))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertTrue(result.complete)
        val match = result.matches.single()
        assertEquals(118.0, match.grams ?: 0.0, 0.0001)
        assertEquals(89.0 * 1.18, match.nutrition.calories, 0.0001)
    }

    @Test fun explicitSourcePortionScalesWithoutDensityGuessing() = runBlocking {
        val porridge = record(
            name = "Rice porridge",
            aliases = setOf("congee"),
            portions = listOf(CanonicalFoodPortion(1.0, "cup", 240.0, isDefault = true))
        )
        val mention = FoodMention(
            id = "1",
            name = "congee",
            originalWording = "2 cups congee",
            quantity = FoodQuantity(2.0, "cup")
        )
        val result = NutritionResolver(listOf(source(listOf(porridge))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertTrue(result.complete)
        assertEquals(480.0, result.matches.single().grams ?: 0.0, 0.0001)
    }

    @Test fun unsupportedHouseholdUnitIsRejectedRatherThanAssumed() = runBlocking {
        val noPortion = record(portions = emptyList())
        val mention = FoodMention(
            id = "1",
            name = "banana",
            originalWording = "1 cup banana",
            quantity = FoodQuantity(1.0, "cup")
        )
        val result = NutritionResolver(listOf(source(listOf(noPortion))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertFalse(result.complete)
    }

    @Test fun fuzzyDiscoveryDoesNotBecomeAConfidentNutritionMatch() = runBlocking {
        val mention = FoodMention(
            id = "1",
            name = "banana smoothie",
            originalWording = "1 banana smoothie",
            quantity = FoodQuantity(1.0, "serving")
        )
        val result = NutritionResolver(listOf(source(listOf(record()))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertFalse(result.complete)
    }

    @Test fun truncatedExactCandidateSetsFailClosedInsteadOfHidingAmbiguity() = runBlocking {
        val records = (1..4).map { index ->
            record(name = "Rice", aliases = setOf("rice")).copy(sourceFoodId = "rice-$index")
        }
        val guardedSource = CanonicalOfflineNutritionSource(
            sourceKind = NutritionSourceKind.USDA,
            manifest = manifest.copy(recordCount = records.size),
            index = OfflineFoodIndex { _, limit -> records.take(limit) },
            searchLimit = 2
        )
        val mention = FoodMention(
            id = "1",
            name = "rice",
            originalWording = "100 g rice",
            quantity = FoodQuantity(100.0, "g")
        )

        assertTrue(guardedSource.search(mention).isEmpty())
    }

    @Test fun filteringCannotHideTheTruncationSentinel() = runBlocking {
        val requested = record(name = "Greek yoghurt", aliases = emptySet(), brand = "Brand A")
        val records = listOf(
            requested,
            requested.copy(sourceFoodId = "other-brand", brand = "Brand B"),
            record(name = "Unrelated discovery", aliases = emptySet()).copy(sourceFoodId = "sentinel"),
            requested.copy(sourceFoodId = "hidden-variant")
        )
        val guardedSource = CanonicalOfflineNutritionSource(
            sourceKind = NutritionSourceKind.USDA,
            manifest = manifest.copy(recordCount = records.size),
            index = OfflineFoodIndex { _, limit -> records.take(limit) },
            searchLimit = 2
        )
        val mention = FoodMention(
            id = "1", name = "Greek yoghurt", originalWording = "100 g Brand A Greek yoghurt",
            quantity = FoodQuantity(100.0, "g"), brand = "Brand A"
        )
        assertTrue(guardedSource.search(mention).isEmpty())
        assertFalse(NutritionResolver(listOf(guardedSource))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention))).complete)
    }

    @Test fun explicitBrandMustMatchSourceBrand() = runBlocking {
        val branded = record(name = "Greek yoghurt", aliases = setOf("greek yoghurt"), brand = "Brand A")
        val mention = FoodMention(
            id = "1",
            name = "greek yoghurt",
            originalWording = "1 Brand B greek yoghurt",
            quantity = FoodQuantity(1.0, "serving"),
            brand = "Brand B"
        )
        val result = NutritionResolver(listOf(source(listOf(branded))))
            .resolve(MealInterpretation(mention.originalWording, listOf(mention)))

        assertFalse(result.complete)
    }
}
