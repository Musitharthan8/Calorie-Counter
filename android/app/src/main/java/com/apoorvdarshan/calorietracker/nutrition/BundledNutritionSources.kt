package com.apoorvdarshan.calorietracker.nutrition

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registers optional offline datasets without touching APK assets during app construction.
 *
 * Missing assets are normal during development and must never stop food logging. Manifest/database
 * probing is deferred until the source is actually searched and runs on Dispatchers.IO.
 */
internal object BundledNutritionSources {
    private data class AssetDataset(
        val expectedSource: NutritionSourceKind,
        val databasePath: String,
        val manifestPath: String
    )

    private val datasets = listOf(
        AssetDataset(
            expectedSource = NutritionSourceKind.USDA,
            databasePath = "nutrition/usda/usda_foods.sqlite",
            manifestPath = "nutrition/usda/usda_foods.manifest.json"
        ),
        AssetDataset(
            expectedSource = NutritionSourceKind.INDB,
            databasePath = "nutrition/indb/indb_foods.sqlite",
            manifestPath = "nutrition/indb/indb_foods.manifest.json"
        )
    )

    fun create(context: Context): List<NutritionSource> = datasets.map { dataset ->
        LazyBundledNutritionSource(
            context = context.applicationContext,
            expectedSource = dataset.expectedSource,
            databasePath = dataset.databasePath,
            manifestPath = dataset.manifestPath
        )
    }
}

/**
 * Caches both successful and unavailable initialization so a missing optional asset is not probed
 * repeatedly on every meal. Resolver-level source isolation still handles runtime read failures.
 */
private class LazyBundledNutritionSource(
    private val context: Context,
    private val expectedSource: NutritionSourceKind,
    private val databasePath: String,
    private val manifestPath: String
) : NutritionSource {
    private val initialization = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var delegate: NutritionSource? = null

    override suspend fun search(mention: FoodMention): List<NutritionCandidate> =
        withContext(Dispatchers.IO) {
            sourceOrNull()?.search(mention).orEmpty()
        }

    private suspend fun sourceOrNull(): NutritionSource? {
        if (initialized) return delegate
        return initialization.withLock {
            if (initialized) return@withLock delegate
            delegate = createDelegate()
            initialized = true
            delegate
        }
    }

    private fun createDelegate(): NutritionSource? {
        // Probe both assets before constructing a source. This is intentionally on Dispatchers.IO.
        val bothPresent = runCatching {
            context.assets.open(manifestPath).use { }
            context.assets.open(databasePath).use { }
            true
        }.getOrDefault(false)
        if (!bothPresent) return null

        val index = runCatching {
            SqliteOfflineFoodIndex(
                context = context,
                databaseAssetPath = databasePath,
                manifestAssetPath = manifestPath
            )
        }.getOrNull() ?: return null

        val manifest = runCatching { index.manifest }.getOrNull()
        if (manifest == null ||
            manifest.source != expectedSource ||
            manifest.sha256 == null
        ) {
            index.close()
            return null
        }

        return CanonicalOfflineNutritionSource(
            sourceKind = expectedSource,
            manifest = manifest,
            index = index
        )
    }
}
