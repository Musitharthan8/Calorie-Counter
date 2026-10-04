package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealInterpretationTest {
    private val parser = LocalMealInterpreter()

    @Test fun multiculturalMealRetainsFoodsBrandAndUnattachedServings() {
        val result = parser.parse(COMPLEX_MEAL)
        assertEquals(listOf("kopi", "KFC chicken", "banana leaf meal", "sambar", "tahu sambal", "chicken rendang"), result.foods.map { it.name })
        assertEquals(FoodQuantity(2.0, "cup"), result.foods[0].quantity)
        assertEquals("KFC", result.foods[1].brand)
        assertEquals("bucket", result.foods[1].quantity?.unit)
        assertEquals(1.0, result.foods[2].quantity!!.amount, 0.0)
        assertEquals(result.foods[2].id, result.foods[3].parentId)
        assertEquals(result.foods[2].id, result.foods[5].parentId)
        assertEquals("lunch", result.mealContext)
        val trailing = result.ambiguities.first { it.wording == "3 servings" }
        assertTrue(trailing.mentionIds.containsAll(listOf(result.foods[2].id, result.foods[5].id)))
        assertNull(result.foods[5].quantity)
        assertEquals(2, result.ambiguities.size)
    }

    @Test fun simpleNaturalLanguageExamples() {
        val cases = listOf(
            "2 kaya toast and kopi C" to listOf("kaya toast", "kopi C"),
            "Had chicken rice, extra chicken, no skin" to listOf("chicken rice"),
            "3 prata with fish curry and one teh tarik" to listOf("prata", "fish curry", "teh tarik"),
            "one plate nasi padang with beef rendang, sambal goreng and telur" to listOf("nasi padang", "beef rendang", "sambal goreng", "telur"),
            "ate Amma's chicken curry with 2 cups rice" to listOf("Amma's chicken curry", "rice"),
            "half of my wife's fries and one burger" to listOf("fries", "burger"),
            "two pieces KFC Hot & Crispy chicken and mashed potato" to listOf("KFC Hot & Crispy chicken", "mashed potato"),
            "1 banana" to listOf("banana"),
            "250 ml milk" to listOf("milk"),
            "100 g grilled chicken breast" to listOf("grilled chicken breast")
        )
        cases.forEach { (text, names) -> assertEquals(text, names, parser.parse(text).foods.map { it.name }) }
        assertEquals(listOf("extra chicken", "no skin"), parser.parse(cases[1].first).foods.single().modifiers)
        assertEquals(0.5, parser.parse(cases[5].first).foods.first().quantity!!.amount, 0.0)
        assertEquals(FoodQuantity(250.0, "ml"), parser.parse("250 ml milk").foods.single().quantity)
        assertEquals(FoodQuantity(100.0, "g"), parser.parse("100 g grilled chicken breast").foods.single().quantity)
        assertEquals("Amma's chicken curry", parser.parse(cases[4].first).foods.first().personalFoodName)
    }


    @Test fun householdUnitsShareOneNormalizedVocabulary() {
        val cases = mapOf(
            "2 slices bread" to FoodQuantity(2.0, "slice"),
            "3 packets crackers" to FoodQuantity(3.0, "packet"),
            "1 can tuna" to FoodQuantity(1.0, "can"),
            "2 tablespoons sambal" to FoodQuantity(2.0, "tbsp"),
            "1 teaspoon sugar" to FoodQuantity(1.0, "tsp"),
            "250 millilitres milk" to FoodQuantity(250.0, "ml"),
            "1 liter water" to FoodQuantity(1.0, "litre")
        )
        cases.forEach { (text, expected) ->
            assertEquals(text, expected, parser.parse(text).foods.single().quantity)
        }
        assertEquals("slice", LocalMealInterpreter.normaliseUnit("slices"))
        assertEquals("container", LocalMealInterpreter.normaliseUnit("containers"))
        assertEquals("fl oz", LocalMealInterpreter.normaliseUnit("fl   oz"))
    }

    @Test fun namesAreNotRestrictedToKnownRegionalDishes() {
        listOf("kopi O", "milo dinosaur", "thosai", "idli", "cai png", "economic rice", "rasam", "sambal sotong",
            "ayam penyet", "briyani", "murtabak", "laksa", "mee rebus", "mee goreng", "char kway teow",
            "bak kut teh", "fishball noodles", "wanton mee", "mixed vegetable curry", "injera", "mofongo")
            .forEach { assertEquals(it, parser.parse("1 $it").foods.single().name) }
    }

    @Test fun aiContractRequiresNoNutritionAndPreservesCallerText() = runBlocking {
        val interpreter = AiMealInterpreter { prompt ->
            assertTrue(prompt.contains("Do not supply calories"))
            assertTrue(prompt.contains("kopi C"))
            """{"rawText":"model changed this","foods":[{"id":"1","name":"kopi C","originalWording":"2 kopi C","quantity":{"amount":2,"unit":"cup"},"confidence":"HIGH"}]}"""
        }
        val result = interpreter.interpret("2 kopi C")
        assertEquals("2 kopi C", result.rawText)
        assertEquals("kopi C", result.foods.single().name)
    }

    @Test fun aiCannotSilentlyResolveTrailingAmbiguity() = runBlocking {
        val interpreter = AiMealInterpreter {
            """{"rawText":"","foods":[{"id":"1","name":"rice","originalWording":"rice"},{"id":"2","name":"curry","originalWording":"curry"}]}"""
        }
        assertTrue(interpreter.interpret("rice and curry, 3 servings").ambiguities.isNotEmpty())
    }

    @Test fun invalidGraphsAndQuantitiesAreRejected() {
        listOf(
            """{"rawText":"x","foods":[{"id":"1","name":"rice","originalWording":"rice","quantity":{"amount":-1,"unit":"g"}}]}""",
            """{"rawText":"x","foods":[{"id":"1","name":"rice","originalWording":"rice","parentId":"1"}]}""",
            """{"rawText":"x","foods":[{"id":"1","name":"rice","originalWording":"rice","parentId":"missing"}]}"""
        ).forEach { response -> assertTrue(runCatching { AiMealInterpreter.decode("rice", response) }.isFailure) }
    }

    companion object {
        const val COMPLEX_MEAL = "I drank 2 cups of kopi and ate a bucket of KFC chicken for lunch and 1 banana leaf meal with sambar, tahu sambal and chicken rendang, 3 servings."
    }
}
