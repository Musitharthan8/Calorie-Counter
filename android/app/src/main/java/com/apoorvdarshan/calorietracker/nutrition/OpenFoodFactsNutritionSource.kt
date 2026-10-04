package com.apoorvdarshan.calorietracker.nutrition

import com.apoorvdarshan.calorietracker.BuildConfig
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.ai.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.ArrayDeque

/**
 * Evidence-backed branded-food source using Open Food Facts full-text search.
 *
 * The source is intentionally conservative:
 * - it never invents missing macros or serving sizes;
 * - it only exposes strong lexical/brand matches to the resolver;
 * - unknown serving sizes stay on a 100 g basis;
 * - a small cache and per-device request budget protect OFF's public search service.
 */
class OpenFoodFactsNutritionSource(
    private val client: OkHttpClient = FoodAnalysisService.defaultClient,
    private val searchBaseUrl: HttpUrl = DEFAULT_SEARCH_BASE,
    private val legacyBaseUrl: HttpUrl = DEFAULT_LEGACY_BASE,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val searchOverride: (suspend (String, String?, Int) -> List<SearchHit>)? = null
) : NutritionSource {

    internal data class SearchHit(
        val barcode: String,
        val name: String,
        val brand: String?,
        val caloriesPer100g: Double?,
        val proteinPer100g: Double?,
        val carbsPer100g: Double?,
        val fatPer100g: Double?,
        val servingGrams: Double?
    )

    private data class CacheEntry(val storedAtMs: Long, val candidates: List<NutritionCandidate>)

    private val cache = LinkedHashMap<String, CacheEntry>(CACHE_SIZE, 0.75f, true)
    private val recentSearches = ArrayDeque<Long>()

    override suspend fun search(mention: FoodMention): List<NutritionCandidate> {
        val query = mention.name.trim()
        if (query.length < 2) return emptyList()
        val cacheKey = normaliseFoodName(listOfNotNull(mention.brand, query).joinToString(" "))
        cached(cacheKey)?.let { return it }

        val hits = if (searchOverride != null) {
            searchOverride.invoke(query, mention.brand, MAX_RESULTS)
        } else {
            if (!acquireSearchBudget()) return emptyList()
            searchRemote(query, mention.brand, MAX_RESULTS)
        }

        val candidates = hits.mapNotNull { hit -> hit.toCandidate(mention) }
        putCache(cacheKey, candidates)
        return candidates
    }

    private fun SearchHit.toCandidate(mention: FoodMention): NutritionCandidate? {
        val calories = caloriesPer100g?.takeIf(::validNonNegative) ?: return null
        val protein = proteinPer100g?.takeIf(::validNonNegative) ?: return null
        val carbs = carbsPer100g?.takeIf(::validNonNegative) ?: return null
        val fat = fatPer100g?.takeIf(::validNonNegative) ?: return null
        val strength = lexicalStrength(mention, this) ?: return null

        val serving = servingGrams?.takeIf { it.isFinite() && it > 0 }
        val factor = serving?.div(100.0) ?: 1.0
        val values = NutrientValues(calories, protein, carbs, fat).scaled(factor)
        return NutritionCandidate(
            canonicalName = name,
            aliases = setOf(mention.name),
            source = NutritionSourceKind.OPEN_FOOD_FACTS,
            sourceName = "Open Food Facts",
            sourceFoodId = barcode,
            sourceUrl = "https://world.openfoodfacts.org/product/$barcode",
            evidence = NutritionEvidence.PRODUCT_LABEL,
            nutrition = values,
            referenceQuantity = if (serving != null) FoodQuantity(1.0, "serving")
                else FoodQuantity(100.0, "g"),
            referenceGrams = serving ?: 100.0,
            brand = brand,
            reliability = strength,
            estimated = false
        )
    }

    private fun lexicalStrength(mention: FoodMention, hit: SearchHit): Double? {
        val requestedBrand = mention.brand?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedBrand != null) {
            val actualBrand = hit.brand ?: return null
            if (normaliseFoodName(actualBrand) != normaliseFoodName(requestedBrand)) return null
        }

        val query = normaliseFoodName(mention.name)
        val name = normaliseFoodName(hit.name)
        if (query == name) return 0.98

        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return null
        val haystack = tokens(listOfNotNull(hit.brand, hit.name).joinToString(" "))
        val overlap = queryTokens.count { it in haystack }.toDouble() / queryTokens.size
        return when {
            overlap >= 1.0 -> 0.92
            queryTokens.size >= 2 && overlap >= 0.80 -> 0.82
            else -> null
        }
    }

    private suspend fun searchRemote(query: String, brand: String?, limit: Int): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val terms = listOfNotNull(brand?.trim()?.takeIf { it.isNotEmpty() }, query.trim())
                .distinct()
                .joinToString(" ")
            val sal = searchBaseUrl.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", terms)
                .addQueryParameter("fields", SEARCH_FIELDS)
                .addQueryParameter("page_size", limit.coerceIn(1, MAX_RESULTS).toString())
                .build()

            when (val primary = request(sal)) {
                is SearchReply.Success -> parseHits(primary.body, "hits", limit)
                SearchReply.RateLimited -> throw IOException("Open Food Facts search rate limited")
                SearchReply.Unavailable -> {
                    val legacy = legacyBaseUrl.newBuilder()
                        .addPathSegments("cgi/search.pl")
                        .addQueryParameter("search_terms", terms)
                        .addQueryParameter("search_simple", "1")
                        .addQueryParameter("action", "process")
                        .addQueryParameter("json", "1")
                        .addQueryParameter("fields", SEARCH_FIELDS)
                        .addQueryParameter("page_size", limit.coerceIn(1, MAX_RESULTS).toString())
                        .build()
                    when (val fallback = request(legacy)) {
                        is SearchReply.Success -> parseHits(fallback.body, "products", limit)
                        SearchReply.RateLimited -> throw IOException("Open Food Facts search rate limited")
                        SearchReply.Unavailable -> throw IOException("Open Food Facts search unavailable")
                    }
                }
            }
        }

    private sealed interface SearchReply {
        data class Success(val body: String) : SearchReply
        data object RateLimited : SearchReply
        data object Unavailable : SearchReply
    }

    private suspend fun request(url: HttpUrl): SearchReply {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).await().use { response ->
                when {
                    response.code == 429 -> SearchReply.RateLimited
                    response.code >= 500 || !response.isSuccessful -> SearchReply.Unavailable
                    else -> SearchReply.Success(response.body?.string().orEmpty())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            SearchReply.Unavailable
        }
    }

    private fun parseHits(raw: String, arrayKey: String, limit: Int): List<SearchHit> {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val array = root[arrayKey] as? JsonArray ?: return emptyList()
        return buildList {
            for (element in array) {
                if (size >= limit) break
                val product = element as? JsonObject ?: continue
                val code = product.string("code") ?: product.string("_id") ?: continue
                val name = product.string("product_name") ?: product.string("generic_name") ?: continue
                val brand = when (val rawBrands = product["brands"]) {
                    is JsonArray -> rawBrands.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> rawBrands.contentOrNull?.split(',')?.firstOrNull()
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
                val nutriments = product["nutriments"] as? JsonObject
                val calories = nutriments?.number("energy-kcal_100g")
                    ?: nutriments?.number("energy_100g")?.times(KJ_TO_KCAL)
                add(
                    SearchHit(
                        barcode = code,
                        name = name,
                        brand = brand,
                        caloriesPer100g = calories,
                        proteinPer100g = nutriments?.number("proteins_100g"),
                        carbsPer100g = nutriments?.number("carbohydrates_100g")
                            ?: nutriments?.number("carbohydrates-total_100g"),
                        fatPer100g = nutriments?.number("fat_100g"),
                        servingGrams = product.number("serving_quantity")?.takeIf { it > 0 }
                    )
                )
            }
        }
    }

    private fun cached(key: String): List<NutritionCandidate>? = synchronized(this) {
        val entry = cache[key] ?: return@synchronized null
        if (nowMs() - entry.storedAtMs > CACHE_TTL_MS) {
            cache.remove(key)
            null
        } else entry.candidates
    }

    private fun putCache(key: String, candidates: List<NutritionCandidate>) = synchronized(this) {
        cache[key] = CacheEntry(nowMs(), candidates)
        while (cache.size > CACHE_SIZE) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
        }
    }

    private fun acquireSearchBudget(): Boolean = synchronized(this) {
        val now = nowMs()
        while (recentSearches.isNotEmpty() && now - recentSearches.first() >= SEARCH_WINDOW_MS) {
            recentSearches.removeFirst()
        }
        if (recentSearches.size >= SEARCHES_PER_WINDOW) return@synchronized false
        recentSearches.addLast(now)
        true
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return (primitive.doubleOrNull
            ?: primitive.contentOrNull?.trim()?.replace(",", ".")?.toDoubleOrNull())
            ?.takeIf { it.isFinite() }
    }

    private fun validNonNegative(value: Double): Boolean = value.isFinite() && value >= 0

    private fun tokens(value: String): Set<String> = value
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length > 1 }
        .toSet()

    companion object {
        private val DEFAULT_SEARCH_BASE = "https://search.openfoodfacts.org/".toHttpUrl()
        private val DEFAULT_LEGACY_BASE = "https://world.openfoodfacts.org/".toHttpUrl()
        private const val SEARCH_FIELDS =
            "code,product_name,generic_name,brands,serving_quantity,nutriments"
        private const val MAX_RESULTS = 6
        private const val CACHE_SIZE = 64
        private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        // OFF documents 10 search requests/min/IP. Keep headroom for barcode/manual search.
        private const val SEARCHES_PER_WINDOW = 8
        private const val SEARCH_WINDOW_MS = 60_000L
        private const val KJ_TO_KCAL = 0.23900573614
        private val USER_AGENT: String
            get() = "CalorieCounter/${BuildConfig.VERSION_NAME} (Fud AI fork; Open Food Facts nutrition resolver)"
    }
}
