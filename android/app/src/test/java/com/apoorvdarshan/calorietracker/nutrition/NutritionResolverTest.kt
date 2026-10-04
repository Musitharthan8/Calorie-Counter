package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NutritionResolverTest {
    private fun candidate(source: NutritionSourceKind = NutritionSourceKind.SG_FOOD_ID) = NutritionCandidate(
        "kopi", source = source, sourceName = "Test fixture only", sourceFoodId = source.name,
        evidence = NutritionEvidence.DATABASE, nutrition = NutrientValues(80.0, 2.0, 14.0, 2.0),
        referenceQuantity = FoodQuantity(1.0, "cup"), referenceGrams = 200.0)
    private fun meal(food: FoodMention = FoodMention("1", "kopi", "2 cups kopi", FoodQuantity(2.0, "cup"))) =
        MealInterpretation("2 cups kopi", listOf(food))

    @Test fun hierarchyAndScalingUseStructuredValues() = runBlocking {
        val result = NutritionResolver(listOf(InMemoryNutritionSource(listOf(candidate(NutritionSourceKind.USDA), candidate())))).resolve(meal())
        assertTrue(result.complete)
        assertEquals(NutritionSourceKind.SG_FOOD_ID, result.matches.single().provenance.source)
        assertEquals(160.0, result.matches.single().nutrition.calories, 0.0)
        assertEquals(400.0, result.matches.single().grams!!, 0.0)
    }

    @Test fun poorMatchesUnknownUnitsAndModifiersAreNeverAccepted() = runBlocking {
        val resolver = NutritionResolver(listOf(InMemoryNutritionSource(listOf(candidate()))))
        val original = meal().foods.single()
        listOf(original.copy(name = "kopi O"), original.copy(brand = "KFC"),
            original.copy(preparation = "iced"), original.copy(modifiers = listOf("no sugar")),
            original.copy(quantity = FoodQuantity(1.0, "bucket")), original.copy(quantity = null))
            .forEach { assertFalse(resolver.resolve(meal(it)).complete) }
    }

    @Test fun aliasesWorkButEqualCandidatesNeedReview() = runBlocking {
        val c = candidate().copy(aliases = setOf("local kopi"))
        val mention = meal().foods.single().copy(name = "local kopi")
        assertTrue(NutritionResolver(listOf(InMemoryNutritionSource(listOf(c)))).resolve(meal(mention)).complete)
        assertFalse(NutritionResolver(listOf(InMemoryNutritionSource(listOf(c, c.copy(sourceFoodId = "other"))))).resolve(meal(mention)).complete)
    }

    @Test fun gramsAreScaledButVolumeNeverAssumesWaterDensity() = runBlocking {
        val resolver = NutritionResolver(listOf(InMemoryNutritionSource(listOf(candidate()))))
        val mention = meal().foods.single()
        assertEquals(40.0, resolver.resolve(meal(mention.copy(quantity = FoodQuantity(100.0, "g")))).matches.single().nutrition.calories, 0.0)
        assertFalse(resolver.resolve(meal(mention.copy(quantity = FoodQuantity(100.0, "ml")))).complete)
    }

    @Test fun compositesAndAmbiguityCannotProducePartialMealTotals() = runBlocking {
        val resolver = NutritionResolver(listOf(InMemoryNutritionSource(listOf(candidate()))))
        val root = meal().foods.single()
        val composite = MealInterpretation("kopi with milk", listOf(root, root.copy(id = "2", name = "milk", parentId = root.id)))
        assertFalse(resolver.resolve(composite).complete)
        val ambiguous = meal().copy(ambiguities = listOf(MealAmbiguity("2", "Two of which?", listOf("1"))))
        assertTrue(resolver.resolve(ambiguous).matches.isEmpty())
    }

    @Test fun sourceFailuresAreIsolatedButCancellationPropagates() = runBlocking {
        val failed = NutritionSource { error("offline") }
        val result = NutritionResolver(listOf(failed, InMemoryNutritionSource(listOf(candidate())))).resolve(meal())
        assertTrue(result.complete)
        assertEquals(1, result.sourceFailures.size)
        try {
            NutritionResolver(listOf(NutritionSource { throw CancellationException() })).resolve(meal())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
