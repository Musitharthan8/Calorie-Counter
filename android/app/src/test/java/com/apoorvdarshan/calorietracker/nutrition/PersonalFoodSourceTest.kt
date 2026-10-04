package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalFoodSourceTest {
    private fun food(name: String, calories: Int) = FoodEntry(
        name = name,
        calories = calories,
        protein = 10.0,
        carbs = 20.0,
        fat = 5.0,
        servingSizeGrams = 200.0,
        source = FoodSource.TEXT_INPUT
    )

    @Test fun usualAndMyPrefixesCanResolveSavedFoodWithoutRenamingIt() = runBlocking {
        val source = PersonalFoodSource { listOf(food("kopi C kosong", 60)) }
        val mention = FoodMention(
            id = "1",
            name = "my usual kopi C kosong",
            originalWording = "my usual kopi C kosong",
            quantity = FoodQuantity(1.0, "serving"),
            personalFoodName = "my usual kopi C kosong"
        )

        val candidates = source.search(mention)
        assertEquals(1, candidates.size)
        assertEquals("kopi C kosong", candidates.single().canonicalName)
        val resolved = NutritionResolver(listOf(source)).resolve(
            MealInterpretation(mention.originalWording, listOf(mention)))
        assertTrue(resolved.complete)
        assertEquals(60.0, resolved.matches.single().nutrition.calories, 0.0)
    }

    @Test fun explicitPreferenceOrderWinsWhenHistoryContainsSameNamedVariants() = runBlocking {
        val favourite = food("Amma's chicken curry", 300)
        val olderLearnedVariant = food("Amma's chicken curry", 420)
        val source = PersonalFoodSource { listOf(favourite, olderLearnedVariant) }

        val candidates = source.search(
            FoodMention(
                id = "1",
                name = "Amma's chicken curry",
                originalWording = "1 serving Amma's chicken curry",
                quantity = FoodQuantity(1.0, "serving")
            )
        )

        assertEquals(1, candidates.size)
        assertEquals(300.0, candidates.single().nutrition.calories, 0.0)
        assertEquals(favourite.id.toString(), candidates.single().sourceFoodId)
    }

    @Test fun savedDatabaseFoodKeepsOriginalAttributionWhenReused() = runBlocking {
        val provenance = NutritionProvenance(
            source = NutritionSourceKind.USDA,
            sourceName = "USDA FoodData Central",
            foodId = "123",
            evidence = NutritionEvidence.DATABASE,
            estimated = false,
            confidence = InterpretationConfidence.HIGH,
            originalWording = "1 banana",
            canonicalName = "banana",
            sourceUrl = "https://fdc.nal.usda.gov/",
            datasetVersion = "fixture-1",
            license = "CC0 1.0",
            attribution = "USDA FoodData Central"
        )
        val saved = food("banana", 105).copy(
            nutritionProvenance = listOf(provenance),
            sourceNutrients = mapOf("phosphorus" to NutrientAmount(22.0, "mg"))
        )
        val source = PersonalFoodSource { listOf(saved) }
        val mention = FoodMention(
            id = "1",
            name = "banana",
            originalWording = "1 banana",
            quantity = FoodQuantity(1.0, "serving")
        )

        val resolved = NutritionResolver(listOf(source)).resolve(
            MealInterpretation(mention.originalWording, listOf(mention))
        )

        assertTrue(resolved.complete)
        val match = resolved.matches.single()
        assertEquals(NutritionSourceKind.PERSONAL, match.provenance.source)
        assertEquals(NutritionEvidence.SAVED_FOOD, match.provenance.evidence)
        assertEquals("fixture-1", match.provenance.datasetVersion)
        assertEquals("CC0 1.0", match.provenance.license)
        assertEquals("USDA FoodData Central", match.provenance.attribution)
        assertTrue(match.provenance.sourceName.contains("USDA FoodData Central"))
        assertEquals(NutrientAmount(22.0, "mg"), match.nutrition.micronutrients["phosphorus"])
    }

    @Test fun unrelatedPersonalNamesNeverFuzzyMatch() = runBlocking {
        val source = PersonalFoodSource { listOf(food("kopi C kosong", 60)) }
        val candidates = source.search(
            FoodMention(
                id = "1",
                name = "kopi O",
                originalWording = "kopi O",
                quantity = FoodQuantity(1.0, "serving")
            )
        )
        assertTrue(candidates.isEmpty())
    }
}
