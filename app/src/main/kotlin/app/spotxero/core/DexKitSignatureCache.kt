package app.spotxero.core

import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File

object DexKitSignatureCache {
    private const val CACHE_FILE_NAME = "spotify_dexkit_cache.json"
    private const val CACHE_VERSION = 31

    private data class CachedSignatures(
        val versionCode: Long,
        val values: Map<String, String>
    )

    @Volatile
    private var cachedSignatures: CachedSignatures? = null

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

    fun loadFromFile(appInfo: ApplicationInfo, versionCode: Long): Map<String, String>? {
        cachedSignatures?.takeIf { it.versionCode == versionCode }?.let { return it.values }

        val cacheFile = cacheFileFor(appInfo)
        val loaded = loadFromDisk(cacheFile, versionCode)
        if (loaded != null) {
            cachedSignatures = CachedSignatures(versionCode, loaded)
        }
        return loaded
    }

    fun saveToFile(appInfo: ApplicationInfo, versionCode: Long, signatures: Map<String, String>) {
        val values = signatures.toMap()
        cachedSignatures = CachedSignatures(versionCode, values)
        saveToDisk(cacheFileFor(appInfo), versionCode, values)
    }

    private fun cacheFileFor(appInfo: ApplicationInfo): File {
        val cacheDir = File(appInfo.dataDir, "spotxero_cache").apply { mkdirs() }
        return File(cacheDir, CACHE_FILE_NAME)
    }
}

