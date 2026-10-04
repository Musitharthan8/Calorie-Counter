package com.apoorvdarshan.calorietracker.nutrition

/**
 * Small offline grammar for quantities and food lists, not a nutrition database or an NLP replacement.
 * Unknown dish names survive intact. The AI interpreter handles richer language in production.
 */
class LocalMealInterpreter : MealInterpreter {
    override suspend fun interpret(rawText: String): MealInterpretation = parse(rawText)

    fun parse(rawText: String): MealInterpretation {
        require(rawText.isNotBlank() && rawText.length <= 16000)
        val foods = mutableListOf<FoodMention>()
        val ambiguities = mutableListOf<MealAmbiguity>()
        val context = Regex("\\b(?:for|at) (breakfast|lunch|dinner|supper)\\b", RegexOption.IGNORE_CASE)
            .find(rawText)?.groupValues?.get(1)
        val clean = rawText.replace('’', '\'').trim().trimEnd('.', '!', '?')
            .replace(Regex("\\b(?:for|at) (?:breakfast|lunch|dinner|supper)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bhot & crispy\\b", RegexOption.IGNORE_CASE), "Hot&Crispy")
        var parentId: String? = null
        // Keep separator identity so 'with' establishes a relationship, not another serving.
        val separators = Regex("\\s+with\\s+|\\s+and\\s+|,\\s*", RegexOption.IGNORE_CASE)
        var start = 0
        var separator = ""
        val parts = buildList {
            separators.findAll(clean).forEach { match ->
                add(separator to clean.substring(start, match.range.first))
                separator = match.value.trim().lowercase()
                start = match.range.last + 1
            }
            add(separator to clean.substring(start))
        }
        for ((sep, rawPart) in parts) {
            var part = rawPart.trim().replace("Hot&Crispy", "Hot & Crispy")
                .replace(Regex("^(?:(?:i|we)\\s+)?(?:drank|ate|had|eaten)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (part.isBlank()) continue
            if (Regex("^(extra |no |without )", RegexOption.IGNORE_CASE).containsMatchIn(part) && foods.isNotEmpty()) {
                val last = foods.last()
                foods[foods.lastIndex] = last.copy(modifiers = last.modifiers + part)
                continue
            }
            if (Regex("^(\\d+(?:\\.\\d+)?) servings?$", RegexOption.IGNORE_CASE).matches(part) && foods.isNotEmpty()) {
                ambiguities += MealAmbiguity(part, "Which food or meal does '$part' refer to?", foods.map { it.id })
                continue
            }
            if (sep == "with") parentId = foods.lastOrNull()?.id
            // An explicit new quantity after 'and' starts another dish rather than a component.
            if (sep == "and" && quantityPrefix.containsMatchIn(part)) parentId = null
            val original = part
            val match = quantityPrefix.find(part)
            var quantity: FoodQuantity? = null
            if (match != null) {
                val token = match.groupValues[1].lowercase()
                val amount = token.toDoubleOrNull() ?: numbers.getValue(token)
                part = part.substring(match.range.last + 1).trim()
                    .replace(Regex("^of\\s+", RegexOption.IGNORE_CASE), "")
                val unitMatch = unitPrefix.find(part)
                val unit = unitMatch?.groupValues?.get(1)?.lowercase()?.let(::normaliseUnit) ?: "serving"
                if (unitMatch != null) part = part.substring(unitMatch.range.last + 1).trim()
                    .replace(Regex("^of\\s+", RegexOption.IGNORE_CASE), "")
                quantity = FoodQuantity(amount, unit)
            }
            val brand = Regex("\\bKFC\\b", RegexOption.IGNORE_CASE).find(part)?.value?.uppercase()
            val personal = part.takeIf {
                it.startsWith("my ", true) || it.startsWith("usual ", true) || it.contains("'s ")
            }
            val name = part.replace(Regex("^my wife's\\s+", RegexOption.IGNORE_CASE), "")
            require(name.isNotBlank()) { "Missing food name" }
            val id = "food-${foods.size + 1}"
            foods += FoodMention(id, name, original, quantity, brand, parentId = parentId, personalFoodName = personal)
            if (quantity?.unit == "bucket") {
                ambiguities += MealAmbiguity(original, "How many pieces were in the bucket, and which type of chicken?", listOf(id))
            }
        }
        return MealInterpretation(rawText, foods, ambiguities, context).validated()
    }

    companion object {
        private val numbers = mapOf("a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0,
            "three" to 3.0, "four" to 4.0, "five" to 5.0, "half" to 0.5, "quarter" to 0.25)
        private val quantityPrefix = Regex("^(\\d+(?:\\.\\d+)?|one|two|three|four|five|half|quarter|an|a)\\s+", RegexOption.IGNORE_CASE)
        private val unitPrefix = Regex("^(cups?|pieces?|plates?|bowls?|buckets?|servings?|ml|g|grams?|kg|litres?)\\b\\s*", RegexOption.IGNORE_CASE)
        fun normaliseUnit(unit: String): String = when (val value = unit.trim().lowercase(java.util.Locale.ROOT)) {
            "grams", "gram" -> "g"
            "cups", "pieces", "plates", "bowls", "buckets", "servings", "litres" -> value.dropLast(1)
            else -> value
        }
    }
}
