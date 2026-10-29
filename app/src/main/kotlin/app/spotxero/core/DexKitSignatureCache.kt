package app.spotxero.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

object DexKitSignatureCache {
    private const val CACHE_FILE_NAME = "spotify_dexkit_cache.json"
    private const val PREFS_NAME = "spotxero_dexkit_cache"
    private const val CACHE_VERSION = 22

    private data class CachedSignatures(
        val versionCode: Long,
        val values: Map<String, String>
    )

    @Volatile
    private var cachedSignatures: CachedSignatures? = null

    fun clearCache() {
        cachedSignatures = null
    }

    @Synchronized
    fun load(context: Context, versionCode: Long): Map<String, String>? {
        cachedSignatures?.takeIf { it.versionCode == versionCode }?.let { return it.values }

        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()

        val fromPreferences = loadFromPreferences(prefs, versionCode)
        if (fromPreferences != null) {
            cachedSignatures = CachedSignatures(versionCode, fromPreferences)
            return fromPreferences
        }

        val files = listOf(
            File(context.filesDir, CACHE_FILE_NAME),
            File(context.cacheDir, CACHE_FILE_NAME)
        )
        val fromDisk = files.firstNotNullOfOrNull { loadFromDisk(it, versionCode) }
        if (fromDisk != null) {
            cachedSignatures = CachedSignatures(versionCode, fromDisk)
            saveToPreferences(prefs, versionCode, fromDisk)
        }
        return fromDisk
    }

    @Synchronized
    fun save(context: Context, versionCode: Long, signatures: Map<String, String>) {
        val values = signatures.toMap()
        cachedSignatures = CachedSignatures(versionCode, values)
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        saveToPreferences(prefs, versionCode, values)
        saveToDisk(File(context.filesDir, CACHE_FILE_NAME), versionCode, values)
        saveToDisk(File(context.cacheDir, CACHE_FILE_NAME), versionCode, values)
    }

    private fun loadFromPreferences(
        prefs: SharedPreferences?,
        versionCode: Long
    ): Map<String, String>? {
        if (prefs == null) return null
        return runCatching {
            if (prefs.getLong("cached_version_code", -1L) != versionCode) return null
            if (prefs.getInt("cache_version", 1) != CACHE_VERSION) return null
            prefs.all.mapNotNull { (key, value) ->
                if (key == "cached_version_code" || key == "cache_version" || value !is String) {
                    null
                } else {
                    key to value
                }
            }.toMap().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun saveToPreferences(
        prefs: SharedPreferences?,
        versionCode: Long,
        signatures: Map<String, String>
    ) {
        if (prefs == null) return
        runCatching {
            prefs.edit().clear()
                .putLong("cached_version_code", versionCode)
                .putInt("cache_version", CACHE_VERSION)
                .also { editor -> signatures.forEach(editor::putString) }
                .apply()
        }
    }

    private fun loadFromDisk(file: File, versionCode: Long): Map<String, String>? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optLong("versionCode") != versionCode) return null
            if (json.optInt("cacheVersion", 1) != CACHE_VERSION) return null
            val values = json.getJSONObject("signatures")
            buildMap {
                values.keys().forEach { key -> put(key, values.getString(key)) }
            }
        }.getOrNull()
    }

    private fun saveToDisk(file: File, versionCode: Long, signatures: Map<String, String>) {
        runCatching {
            val values = JSONObject()
            signatures.forEach { (key, value) -> values.put(key, value) }
            JSONObject()
                .put("versionCode", versionCode)
                .put("cacheVersion", CACHE_VERSION)
                .put("signatures", values)
                .let { file.writeText(it.toString()) }
        }
    }

    fun loadFromFile(appInfo: android.content.pm.ApplicationInfo, versionCode: Long): Map<String, String>? {
        cachedSignatures?.takeIf { it.versionCode == versionCode }?.let { return it.values }

        val cacheFile = cacheFileFor(appInfo)
        val loaded = loadFromDisk(cacheFile, versionCode)
        if (loaded != null) {
            cachedSignatures = CachedSignatures(versionCode, loaded)
        }
        return loaded
    }

    fun saveToFile(appInfo: android.content.pm.ApplicationInfo, versionCode: Long, signatures: Map<String, String>) {
        val values = signatures.toMap()
        cachedSignatures = CachedSignatures(versionCode, values)
        saveToDisk(cacheFileFor(appInfo), versionCode, values)
    }

    private fun cacheFileFor(appInfo: android.content.pm.ApplicationInfo): File {
        val cacheDir = File(appInfo.dataDir, "spotxero_cache").apply { mkdirs() }
        return File(cacheDir, CACHE_FILE_NAME)
    }
}

