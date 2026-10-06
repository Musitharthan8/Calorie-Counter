package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SingaporeBrandNutritionSourceTest {
    private fun mention(name: String = "Hot Fudge Sundae", region: String? = "Singapore",
                        brand: String? = "McDonald's", quantity: FoodQuantity? = FoodQuantity(1.0)) =
        FoodMention("1", name, name, quantity, brand, region)

    @Test fun officialServingScalesAndKeepsMissingNutrientsUnknown() = runBlocking {
        val food = mention(quantity = FoodQuantity(2.0, "sundaes"))
        val result = NutritionResolver(listOf(SingaporeBrandNutritionSource()))
            .resolve(MealInterpretation("2 McDonald's Singapore hot fudge sundaes", listOf(food)))
        assertTrue(result.complete)
        val match = result.matches.single()
        assertEquals(628.0, match.nutrition.calories, 0.0)
        assertEquals(336.0, match.nutrition.micronutrients.getValue("sodium").amount, 0.0)
        assertNull(match.grams)
        assertFalse(match.nutrition.micronutrients.containsKey("vitaminA"))
        assertEquals(NutritionSourceKind.BRAND, match.provenance.source)
        assertFalse(match.provenance.estimated)
        assertTrue(match.provenance.sourceUrl!!.endsWith("hot-fudge-sundae"))
        val analysis = result.toFoodAnalysis()
        assertNull(analysis.vitaminA)
        assertNull(analysis.caffeine)
        assertEquals(336.0, analysis.sodium!!, 0.0)
        assertTrue(analysis.nutritionWarnings.any { it.contains("Serving weight is unavailable") })
    }

    @Test fun wrongCountryBrandUnknownPortionAndSeasonalProductDoNotMatch() = runBlocking {
        val source = SingaporeBrandNutritionSource()
        listOf(mention(region = "USA"), mention(region = null), mention(brand = "KFC"),
            mention(brand = null), mention(quantity = FoodQuantity(200.0, "g")),
            mention(name = "Hershey's chocolate sundae"), mention(name = "medium coke zero"))
            .forEach { assertTrue(it.toString(), source.search(it).isEmpty()) }
        val noQuantity = mention(quantity = null)
        assertFalse(NutritionResolver(listOf(source)).resolve(MealInterpretation("sundae", listOf(noQuantity))).complete)
    }

    @Test fun qualifiedSingaporeNameAndSgRegionResolve() = runBlocking {
        val source = SingaporeBrandNutritionSource()
        assertEquals(1, source.search(mention("McDonald's Singapore Hot Fudge Sundae", null, null)).size)
        assertEquals(1, source.search(mention(region = "SG")).size)
        val meal = LocalMealInterpreter().parse("1 serving McDonald's Singapore Hot Fudge Sundae")
        assertTrue(NutritionResolver(listOf(source)).resolve(meal).complete)
    }

    @Test fun personalSourceStillTakesPriority() = runBlocking {
        val food = mention()
        val brand = SingaporeBrandNutritionSource().search(food).single()
        val personal = brand.copy(source = NutritionSourceKind.PERSONAL, sourceName = "My saved serving",
            nutrition = NutrientValues(300.0, 6.0, 45.0, 10.0))
        val result = NutritionResolver(listOf(SingaporeBrandNutritionSource(), InMemoryNutritionSource(listOf(personal))))
            .resolve(MealInterpretation("my sundae", listOf(food)))
        assertEquals(NutritionSourceKind.PERSONAL, result.matches.single().provenance.source)
    }
}
