package com.apoorvdarshan.calorietracker.nutrition

import android.content.Context

/**
 * Registers versioned offline datasets only when both their database and manifest are packaged.
 *
 * Missing assets are normal during development and must never stop food logging. A malformed or
 * mismatched manifest is also isolated here; the existing Personal/OFF/AI paths remain available.
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

    fun create(context: Context): List<NutritionSource> = buildList {
        for (dataset in datasets) {
            if (!assetExists(context, dataset.databasePath) ||
                !assetExists(context, dataset.manifestPath)
            ) continue

            val index = runCatching {
                SqliteOfflineFoodIndex(
                    context = context,
                    databaseAssetPath = dataset.databasePath,
                    manifestAssetPath = dataset.manifestPath
                )
            }.getOrNull() ?: continue

            val manifest = runCatching { index.manifest }.getOrNull()
            if (manifest == null) {
                index.close()
                continue
            }
            if (manifest.source != dataset.expectedSource || manifest.sha256 == null) {
                index.close()
                continue
            }

            add(
                CanonicalOfflineNutritionSource(
                    sourceKind = dataset.expectedSource,
                    manifest = manifest,
                    index = index
                )
            )
        }
    }

    private fun assetExists(context: Context, path: String): Boolean =
        runCatching {
            context.assets.open(path).use { /* existence probe only */ }
            true
        }.getOrDefault(false)
}
