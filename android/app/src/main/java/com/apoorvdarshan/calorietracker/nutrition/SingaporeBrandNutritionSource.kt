package com.apoorvdarshan.calorietracker.nutrition

/** Small, auditable official-menu catalogue. Unknown nutrients and serving masses stay absent. */
class SingaporeBrandNutritionSource : NutritionSource {
    override suspend fun search(mention: FoodMention): List<NutritionCandidate> {
        val region = mention.region?.trim()?.lowercase(java.util.Locale.ROOT)
        val name = normaliseFoodName(mention.name)
        val singaporeName = name.startsWith("mcdonald's singapore ") || name.startsWith("mcdonalds singapore ")
        if (region !in setOf("singapore", "sg") && !(region == null && singaporeName)) return emptyList()
        val brand = mention.brand?.let(::normaliseFoodName)
        if (brand != null && brand !in setOf("mcdonald's", "mcdonalds", "mcdonald’s", "mcdonald's singapore")) return emptyList()
        return foods.mapNotNull { food ->
            val product = name.removePrefix("mcdonald's singapore ").removePrefix("mcdonalds singapore ")
                .removePrefix("mcdonald's ").removePrefix("mcdonalds ")
            val branded = brand != null || name != product
            if (!branded || product !in food.aliases + normaliseFoodName(food.canonicalName)) return@mapNotNull null
            val unit = mention.quantity?.unit?.let(LocalMealInterpreter::normaliseUnit)
                ?.let { if (it == "${food.portionUnit}s") food.portionUnit else it }
            if (unit != null && unit !in setOf("serving", food.portionUnit)) return@mapNotNull null
            food.candidate.copy(
                aliases = food.candidate.aliases + mention.name,
                brand = mention.brand ?: food.candidate.brand,
                region = mention.region ?: "Singapore",
                referenceQuantity = FoodQuantity(1.0, mention.quantity?.unit ?: "serving")
            )
        }
    }

    private data class MenuFood(val candidate: NutritionCandidate, val portionUnit: String) {
        val aliases get() = candidate.aliases
        val canonicalName get() = candidate.canonicalName
    }

    companion object {
        private fun food(name: String, slug: String, unit: String, calories: Double, protein: Double,
                         carbs: Double, fat: Double, saturatedFat: Double, cholesterol: Double,
                         fiber: Double, sodium: Double, aliases: Set<String> = emptySet()) = MenuFood(
            NutritionCandidate(
                canonicalName = name, aliases = aliases, source = NutritionSourceKind.BRAND,
                sourceName = "McDonald's Singapore official menu",
                sourceFoodId = "mcdonalds-sg:$slug",
                sourceUrl = "https://www.mcdonalds.com.sg/food-menu/$slug",
                datasetVersion = "Menu dated December 2023; checked 2026-10-07",
                attribution = "McDonald's Singapore",
                evidence = NutritionEvidence.PRODUCT_LABEL,
                nutrition = NutrientValues(calories, protein, carbs, fat, mapOf(
                    "saturatedFat" to NutrientAmount(saturatedFat, "g"),
                    "cholesterol" to NutrientAmount(cholesterol, "mg"),
                    "fiber" to NutrientAmount(fiber, "g"),
                    "sodium" to NutrientAmount(sodium, "mg")
                )),
                referenceQuantity = FoodQuantity(1.0, "serving"),
                brand = "McDonald's", region = "Singapore"
            ), unit
        )
        // No Hershey's seasonal-product alias: its formulation has not been verified.
        private val foods = listOf(
            food("Hot Fudge Sundae", "hot-fudge-sundae", "sundae", 314.0, 6.0, 49.0, 10.0,
                7.0, 3.0, 1.0, 168.0, setOf("chocolate sundae")),
            food("Vanilla Cone", "vanilla-cone", "cone", 133.0, 3.0, 22.0, 4.0,
                2.0, 2.0, 0.0, 56.0),
            food("Coca-Cola Zero Sugar (Small)", "coca-cola-zero-small", "serving", 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 29.0, setOf("small coke zero", "small coca-cola zero sugar"))
        )
    }
}
