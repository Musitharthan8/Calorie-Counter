package com.apoorvdarshan.calorietracker.nutrition

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Read-only runtime adapter for canonical offline nutrition assets.
 *
 * The database is copied from APK assets into no-backup storage because Android's SQLite API needs
 * a filesystem path. The sidecar manifest controls the versioned filename and optional SHA-256
 * verification so an interrupted/corrupted copy is never silently used for nutrition.
 */
internal class SqliteOfflineFoodIndex(
    context: Context,
    private val databaseAssetPath: String,
    private val manifestAssetPath: String
) : OfflineFoodIndex, AutoCloseable {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val manifest: NutritionDatasetManifest by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.assets.open(manifestAssetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<NutritionDatasetManifest>(reader.readText())
        }
    }

    private val databaseLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SQLiteDatabase.openDatabase(
            materializeDatabase().absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }
    private val database: SQLiteDatabase get() = databaseLazy.value

    override suspend fun search(query: String, limit: Int): List<CanonicalFoodRecord> =
        withContext(Dispatchers.IO) {
        val normalized = normalizeForIndex(query)
        if (normalized.isBlank()) return@withContext emptyList()
        val capped = limit.coerceIn(1, 64)
        val sql = """
            SELECT DISTINCT
                f.source_food_id,
                f.canonical_name,
                f.region,
                f.brand,
                f.preparation,
                f.calories,
                f.protein,
                f.carbs,
                f.fat,
                f.micronutrients_json,
                f.source_url,
                CASE WHEN f.normalized_name = ? THEN 0 ELSE 1 END AS match_rank
            FROM foods f
            LEFT JOIN aliases a ON a.source_food_id = f.source_food_id
            WHERE f.normalized_name = ? OR a.normalized_name = ?
            ORDER BY match_rank ASC, f.canonical_name ASC
            LIMIT ?
        """.trimIndent()

        val rows = mutableListOf<CanonicalFoodRecord>()
        database.rawQuery(
            sql,
            arrayOf(normalized, normalized, normalized, capped.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                readRecord(cursor)?.let(rows::add)
            }
        }
        rows
    }

    private fun readRecord(cursor: Cursor): CanonicalFoodRecord? {
        val id = cursor.string("source_food_id") ?: return null
        val name = cursor.string("canonical_name") ?: return null
        val calories = cursor.doubleOrNull("calories") ?: return null
        val protein = cursor.doubleOrNull("protein") ?: return null
        val carbs = cursor.doubleOrNull("carbs") ?: return null
        val fat = cursor.doubleOrNull("fat") ?: return null
        if (listOf(calories, protein, carbs, fat).any { !it.isFinite() || it < 0 }) return null

        val micronutrients = cursor.string("micronutrients_json")
            ?.let { raw ->
                runCatching { json.decodeFromString<Map<String, NutrientAmount>>(raw) }
                    .getOrDefault(emptyMap())
            }
            .orEmpty()

        val aliases = linkedSetOf<String>()
        val translations = linkedMapOf<String, String>()
        database.rawQuery(
            "SELECT name, language_tag FROM aliases WHERE source_food_id = ? ORDER BY name",
            arrayOf(id)
        ).use { aliasCursor ->
            while (aliasCursor.moveToNext()) {
                val alias = aliasCursor.getString(0)?.trim().orEmpty()
                if (alias.isEmpty()) continue
                val language = aliasCursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
                if (language == null) aliases += alias else translations.putIfAbsent(language, alias)
            }
        }

        val portions = mutableListOf<CanonicalFoodPortion>()
        database.rawQuery(
            """
                SELECT amount, unit, grams, description, is_default, is_derived
                FROM portions
                WHERE source_food_id = ?
                ORDER BY is_default DESC, unit ASC, amount ASC
            """.trimIndent(),
            arrayOf(id)
        ).use { portionCursor ->
            while (portionCursor.moveToNext()) {
                val amount = portionCursor.getDouble(0)
                val unit = portionCursor.getString(1)?.trim().orEmpty()
                val grams = portionCursor.getDouble(2)
                if (!amount.isFinite() || amount <= 0 || unit.isBlank() || !grams.isFinite() || grams <= 0) {
                    continue
                }
                portions += CanonicalFoodPortion(
                    amount = amount,
                    unit = unit,
                    grams = grams,
                    description = portionCursor.getString(3)?.trim()?.takeIf { it.isNotEmpty() },
                    isDefault = portionCursor.getInt(4) != 0,
                    isDerived = portionCursor.getInt(5) != 0
                )
            }
        }

        val loadedManifest = manifest
        return CanonicalFoodRecord(
            source = loadedManifest.source,
            datasetName = loadedManifest.datasetName,
            datasetVersion = loadedManifest.datasetVersion,
            sourceFoodId = id,
            canonicalName = name,
            aliases = aliases,
            translations = translations,
            region = cursor.string("region"),
            brand = cursor.string("brand"),
            preparation = cursor.string("preparation"),
            nutritionPer100g = NutrientValues(
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                micronutrients = micronutrients
            ),
            portions = portions,
            sourceUrl = cursor.string("source_url")
        )
    }

    private fun materializeDatabase(): File {
        val loadedManifest = manifest
        requireNotNull(loadedManifest.sha256) { "Bundled nutrition database requires a checksum" }
        val directory = File(appContext.noBackupFilesDir, "nutrition-indexes").apply { mkdirs() }
        val safeSource = loadedManifest.source.name.lowercase()
        val safeVersion = loadedManifest.datasetVersion
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "unknown" }
            .take(80)
        val target = File(directory, "${safeSource}-${safeVersion}.sqlite")

        fun validExisting(): Boolean =
            target.isFile && target.length() > 0L &&
                (loadedManifest.sha256 == null ||
                    sha256(target).equals(loadedManifest.sha256, ignoreCase = true))

        if (!validExisting()) {
            if (target.exists()) target.delete()
            val temporary = File(directory, "${target.name}.tmp-${System.nanoTime()}")
            try {
                appContext.assets.open(databaseAssetPath).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (loadedManifest.sha256 != null &&
                    !sha256(temporary).equals(loadedManifest.sha256, ignoreCase = true)
                ) {
                    throw IllegalStateException(
                        "Nutrition database checksum mismatch: ${loadedManifest.datasetName}"
                    )
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

        if (!validExisting()) {
            throw IllegalStateException(
                "Nutrition database could not be verified: ${loadedManifest.datasetName}"
            )
        }

        // Versioned filenames make old indexes harmless; prune only this source's superseded copies.
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("${safeSource}-") && it != target }
            ?.forEach { it.delete() }

        return target
    }

    override fun close() {
        if (databaseLazy.isInitialized()) {
            runCatching { database.close() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getDouble(index).takeIf { it.isFinite() }
    }

    companion object {
        internal fun normalizeForIndex(value: String): String = value
            .replace('’', '\'')
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
