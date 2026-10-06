package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.coroutines.CancellationException

/** Conservative matching: source priority cannot rescue a wrong food or a made-up portion. */
class NutritionResolver(private val sources: List<NutritionSource>) {
    suspend fun resolve(meal: MealInterpretation): NutritionResolutionResult {
        meal.validated()
        val matches = mutableListOf<NutritionMatch>()
        val unresolved = mutableListOf<FoodMention>()
        val failures = mutableListOf<String>()
        val grouped = meal.foods.filter { it.parentId != null }.flatMap { listOf(it.id, it.parentId!!) }.toSet()
        for (mention in meal.foods) {
            // A composite needs a verified recipe/decomposition policy before summing any children.
            if (mention.id in grouped || meal.ambiguities.any { it.affectsNutrition &&
                    (it.mentionIds.isEmpty() || mention.id in it.mentionIds) }) {
                unresolved += mention
                continue
            }
            val candidates = mutableListOf<NutritionCandidate>()
            sources.forEachIndexed { index, source ->
                try { candidates += source.search(mention) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failures += "Source ${index + 1} unavailable" }
            }
            val ranked = candidates.mapNotNull { candidate -> match(mention, candidate) }
                .sortedWith(compareBy<NutritionMatch> { it.candidate.source.ordinal }.thenByDescending { it.score })
            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)
            if (best == null || (runnerUp != null && runnerUp.candidate.source == best.candidate.source &&
                    best.score - runnerUp.score < 0.05)) {
                unresolved += mention
            } else matches += best
        }
        return NutritionResolutionResult(meal, matches, unresolved, failures.distinct())
    }

    internal fun match(mention: FoodMention, candidate: NutritionCandidate): NutritionMatch? {
        val name = normaliseFoodName(mention.name)
        val exact = name == normaliseFoodName(candidate.canonicalName)
        if (!exact && candidate.aliases.none { normaliseFoodName(it) == name }) return null
        if (candidate.reliability < 0.7) return null
        if (mention.brand != null && !mention.brand.equals(candidate.brand, true)) return null
        if (mention.preparation != null && !mention.preparation.equals(candidate.preparation, true)) return null
        if (mention.region != null && candidate.region != null && !mention.region.equals(candidate.region, true)) return null
        if (mention.modifiers.any { modifier -> candidate.supportedModifiers.none { it.equals(modifier, true) } }) return null
        val quantity = mention.quantity ?: if (candidate.source == NutritionSourceKind.PERSONAL)
            FoodQuantity(1.0, "serving", explicit = false) else return null
        val unit = LocalMealInterpreter.normaliseUnit(quantity.unit)
        val referenceUnit = LocalMealInterpreter.normaliseUnit(candidate.referenceQuantity.unit)
        val factor = when {
            unit == referenceUnit -> quantity.amount / candidate.referenceQuantity.amount
            unit == "g" && candidate.referenceGrams != null -> quantity.amount / candidate.referenceGrams
            unit == "kg" && candidate.referenceGrams != null -> quantity.amount * 1000 / candidate.referenceGrams
            unit == "litre" && referenceUnit == "ml" -> quantity.amount * 1000 / candidate.referenceQuantity.amount
            else -> return null // No assumed cup size, food density, bucket size or piece weight.
        }
        if (!factor.isFinite() || factor <= 0) return null
        val scaled = listOf(candidate.nutrition.calories, candidate.nutrition.protein,
            candidate.nutrition.carbs, candidate.nutrition.fat).map { it * factor }
        if (scaled.any { !it.isFinite() } || scaled.first() > Int.MAX_VALUE) return null
        if (candidate.nutrition.micronutrients.values.any { !(it.amount * factor).isFinite() }) return null
        val grams = candidate.referenceGrams?.times(factor)
        if (grams != null && (!grams.isFinite() || grams <= 0)) return null
        val confidence = when {
            mention.confidence == InterpretationConfidence.LOW -> InterpretationConfidence.LOW
            candidate.estimated || candidate.userEdited || candidate.referencePortionEstimated ||
                !quantity.explicit || mention.confidence != InterpretationConfidence.HIGH -> InterpretationConfidence.MEDIUM
            else -> InterpretationConfidence.HIGH
        }
        val score = (if (exact) 1.0 else 0.9) * candidate.reliability +
            (if (mention.region != null && mention.region.equals(candidate.region, true)) 0.05 else 0.0) +
            (if (unit == referenceUnit) 0.05 else 0.0)
        return NutritionMatch(mention, candidate, candidate.nutrition.scaled(factor),
            grams, score,
            NutritionProvenance(
                source = candidate.source,
                sourceName = candidate.sourceName,
                foodId = candidate.sourceFoodId,
                evidence = candidate.evidence,
                estimated = candidate.estimated || candidate.source == NutritionSourceKind.AI_ESTIMATE,
                confidence = confidence,
                originalWording = mention.originalWording,
                canonicalName = candidate.canonicalName,
                sourceUrl = candidate.sourceUrl,
                datasetVersion = candidate.datasetVersion,
                license = candidate.license,
                attribution = candidate.attribution,
                portionEstimated = !quantity.explicit || candidate.referencePortionEstimated,
                userEdited = candidate.userEdited
            ))
    }
}
