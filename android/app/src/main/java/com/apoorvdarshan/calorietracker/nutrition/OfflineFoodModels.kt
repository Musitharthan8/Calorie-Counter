package com.apoorvdarshan.calorietracker.nutrition

import kotlinx.serialization.Serializable

/**
 * Metadata for one imported nutrition dataset.
 *
 * The manifest is intentionally separate from individual food records so every offline source can
 * carry reproducible version/licence information without duplicating it thousands of times.
 */
@Serializable
data class NutritionDatasetManifest(
    val source: NutritionSourceKind,
    val datasetName: String,
    val datasetVersion: String,
    val sourceUrl: String,
    val license: String,
    val attribution: String,
    val generatedAtUtc: String,
    val recordCount: Int,
    val sha256: String? = null
) {
    init {
        require(datasetName.isNotBlank())
        require(datasetVersion.isNotBlank())
        require(sourceUrl.isNotBlank())
        require(license.isNotBlank())
        require(attribution.isNotBlank())
        require(generatedAtUtc.isNotBlank())
        require(recordCount >= 0)
        require(sha256 == null || sha256.matches(Regex("[0-9a-fA-F]{64}")))
    }
}

@Serializable
data class CanonicalFoodPortion(
    /** Human-facing amount, e.g. 1 piece, 0.5 cup. */
    val amount: Double = 1.0,
    val unit: String,
    /** Gram mass for [amount] of [unit], only when the source explicitly supplies it. */
    val grams: Double,
    val description: String? = null,
    val isDefault: Boolean = false,
    /** True when gram mass was mathematically inferred rather than directly supplied upstream. */
    val isDerived: Boolean = false
) {
    init {
        require(amount.isFinite() && amount > 0)
        require(unit.isNotBlank())
        require(grams.isFinite() && grams > 0)
    }

    val gramsPerUnit: Double get() = grams / amount
}

/**
 * App-facing record shared by every imported national/generic nutrition source.
 *
 * Nutrition is normalized to 100 g at import time. A source-specific importer owns unit conversion;
 * runtime code should never need to know whether the upstream file used kJ, kcal, mg or micrograms.
 */
@Serializable
data class CanonicalFoodRecord(
    val source: NutritionSourceKind,
    val datasetName: String,
    val datasetVersion: String,
    val sourceFoodId: String,
    val canonicalName: String,
    val aliases: Set<String> = emptySet(),
    /** BCP-47-ish language tag -> source-provided display name. */
    val translations: Map<String, String> = emptyMap(),
    val region: String? = null,
    val brand: String? = null,
    val preparation: String? = null,
    val supportedModifiers: Set<String> = emptySet(),
    val nutritionPer100g: NutrientValues,
    val portions: List<CanonicalFoodPortion> = emptyList(),
    val sourceUrl: String? = null
) {
    init {
        require(datasetName.isNotBlank())
        require(datasetVersion.isNotBlank())
        require(sourceFoodId.isNotBlank())
        require(canonicalName.isNotBlank())
        require(source != NutritionSourceKind.PERSONAL)
        require(source != NutritionSourceKind.AI_ESTIMATE)
        require(portions.count { it.isDefault } <= 1)
    }

    val allNames: Set<String>
        get() = buildSet {
            add(canonicalName)
            addAll(aliases)
            addAll(translations.values)
        }
}

/** Search abstraction implemented by SQLite/assets later and faked directly in unit tests. */
fun interface OfflineFoodIndex {
    suspend fun search(query: String, limit: Int): List<CanonicalFoodRecord>
}

/**
 * Turns source-neutral offline records into [NutritionCandidate]s while keeping matching strict.
 *
 * Discovery may be fuzzy inside an index, but a result only becomes loggable when the user's
 * interpreted food name is an exact canonical name / alias / translation after normalization.
 * This keeps approximate search useful without turning a close search hit into fake certainty.
 */
class CanonicalOfflineNutritionSource(
    private val sourceKind: NutritionSourceKind,
    private val manifest: NutritionDatasetManifest,
    private val index: OfflineFoodIndex,
    private val searchLimit: Int = 8
) : NutritionSource {

    init {
        require(sourceKind == manifest.source)
        require(sourceKind !in setOf(
            NutritionSourceKind.PERSONAL,
            NutritionSourceKind.OPEN_FOOD_FACTS,
            NutritionSourceKind.AI_ESTIMATE
        ))
        require(searchLimit in 1..50)
    }

    override suspend fun search(mention: FoodMention): List<NutritionCandidate> {
        val query = mention.name.trim()
        if (query.isEmpty()) return emptyList()
        val discoveredRecords = index.search(query, searchLimit + 1)
        // Check the raw discovery count before applying name/brand gates. Filtering a full page
        // could hide the sentinel and make one surviving match look like a complete candidate set.
        if (discoveredRecords.size > searchLimit) return emptyList()
        val matchingRecords = discoveredRecords.asSequence()
            .filter { it.source == sourceKind }
            .filter { record -> record.exactlyNames(mention.name) }
            .filter { record ->
                mention.brand == null || record.brand?.equals(mention.brand, ignoreCase = true) == true
            }
            .take(searchLimit + 1)
            .toList()

        return matchingRecords.mapNotNull { record -> record.toCandidateFor(mention, manifest) }
    }

    private fun CanonicalFoodRecord.exactlyNames(name: String): Boolean {
        val requested = normaliseFoodName(name)
        return allNames.any { normaliseFoodName(it) == requested }
    }

    private fun CanonicalFoodRecord.toCandidateFor(
        mention: FoodMention,
        manifest: NutritionDatasetManifest
    ): NutritionCandidate? {
        val quantityUnit = mention.quantity?.unit?.let(LocalMealInterpreter::normaliseUnit)
        val portion = when {
            quantityUnit == null -> null
            quantityUnit in setOf("g", "kg") -> null
            else -> portions
                .filter { LocalMealInterpreter.normaliseUnit(it.unit) == quantityUnit }
                .let { matches ->
                    when {
                        matches.size == 1 -> matches.single()
                        matches.size > 1 -> matches.singleOrNull { it.isDefault }
                        quantityUnit == "serving" -> portions.singleOrNull { it.isDefault }
                        else -> null
                    }
                }
        }

        val referenceQuantity: FoodQuantity
        val referenceGrams: Double
        val nutrition: NutrientValues

        if (portion != null) {
            referenceQuantity = if (quantityUnit == "serving" &&
                LocalMealInterpreter.normaliseUnit(portion.unit) != "serving"
            ) {
                // "1 banana" / "1 yoghurt" is a count-like serving request. A source-declared
                // default portion may be named "medium", "container", etc.; expose that mass as
                // one serving without claiming the words themselves are equivalent units.
                FoodQuantity(1.0, "serving")
            } else {
                FoodQuantity(portion.amount, portion.unit)
            }
            referenceGrams = portion.grams
            nutrition = nutritionPer100g.scaled(portion.grams / 100.0)
        } else {
            // Runtime resolver can scale explicit g/kg against this basis. It will reject cups,
            // pieces, bowls, etc. unless an explicit source portion above matched the unit.
            referenceQuantity = FoodQuantity(100.0, "g")
            referenceGrams = 100.0
            nutrition = nutritionPer100g
        }

        val requested = normaliseFoodName(mention.name)
        val canonicalExact = requested == normaliseFoodName(canonicalName)
        val reliability = if (canonicalExact) 1.0 else 0.96

        return NutritionCandidate(
            canonicalName = canonicalName,
            aliases = aliases + translations.values,
            source = source,
            sourceName = datasetName,
            sourceFoodId = sourceFoodId,
            sourceUrl = sourceUrl ?: manifest.sourceUrl,
            datasetVersion = datasetVersion,
            license = manifest.license,
            attribution = manifest.attribution,
            evidence = NutritionEvidence.DATABASE,
            nutrition = nutrition,
            referenceQuantity = referenceQuantity,
            referenceGrams = referenceGrams,
            brand = brand,
            region = region,
            preparation = preparation,
            supportedModifiers = supportedModifiers,
            reliability = reliability,
            estimated = false,
            referencePortionEstimated = portion?.isDerived == true
        )
    }
}
