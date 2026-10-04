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
