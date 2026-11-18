package app.spotxero.hooks.lyrics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object PersistentLyricsCache {

    private const val TAG = "SpotXero.LyricsCache"
    private const val MAX_L1_SIZE = 60
    private const val MAX_DISK_ENTRIES = 300

    @Volatile
    private var appContext: Context? = null

    // L1 caches
    private val protoMemoryCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(MAX_L1_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                return size > MAX_L1_SIZE
            }
        }
    )

    private val linesMemoryCache = ConcurrentHashMap<String, List<String>>()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
            Log.d(TAG, "Initialized persistent cache directory: ${getCacheDir()?.absolutePath}")
        }
    }

    private fun resolveContext(): Context? {
        return appContext ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null) currentApp else {
                val at = atClass.getMethod("currentActivityThread").invoke(null)
                atClass.getMethod("getApplication").invoke(at) as? Context
            }
        }.getOrNull()?.also { appContext = it }
    }

    private fun getCacheDir(): File? {
        val ctx = resolveContext() ?: return null
        return File(ctx.cacheDir, "spotxero_lyrics").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun hashKey(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getProtobuf(key: String): ByteArray? {
        // 1. Check L1 memory cache
        protoMemoryCache[key]?.let { return it }

        // 2. Check L2 disk cache
        val dir = getCacheDir() ?: return null
        val file = File(dir, "${hashKey(key)}.proto")
        if (file.exists() && file.isFile && file.length() > 0) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                file.setLastModified(System.currentTimeMillis())
                protoMemoryCache[key] = bytes
                Log.d(TAG, "L2 disk cache hit for protobuf key: $key (${bytes.size} bytes)")
                return bytes
            }
        }
        return null
    }

    fun putProtobuf(key: String, bytes: ByteArray) {
        protoMemoryCache[key] = bytes
        val dir = getCacheDir() ?: return
        try {
            pruneDiskCacheIfNeeded(dir)
            val file = File(dir, "${hashKey(key)}.proto")
            file.writeBytes(bytes)
            Log.d(TAG, "Saved protobuf to L2 disk cache: ${file.name} (${bytes.size} bytes)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist protobuf to disk: ${t.message}")
        }
    }

    fun getLines(key: String): List<String>? {
        // 1. Check L1 memory cache
        linesMemoryCache[key]?.let { return it }

        // 2. Check L2 disk cache
        val dir = getCacheDir() ?: return null
        val file = File(dir, "${hashKey(key)}.json")
        if (file.exists() && file.isFile && file.length() > 0) {
            val parsed = runCatching {
                val jsonStr = file.readText(Charsets.UTF_8)
                val arr = JSONArray(jsonStr)
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrNull()

            if (parsed != null && parsed.isNotEmpty()) {
                file.setLastModified(System.currentTimeMillis())
                linesMemoryCache[key] = parsed
                Log.d(TAG, "L2 disk cache hit for lines key: $key (${parsed.size} lines)")
                return parsed
            }
        }
        return null
    }

    fun putLines(key: String, lines: List<String>) {
        linesMemoryCache[key] = lines
        val dir = getCacheDir() ?: return
        try {
            pruneDiskCacheIfNeeded(dir)
            val file = File(dir, "${hashKey(key)}.json")
            val arr = JSONArray()
            lines.forEach { arr.put(it) }
            file.writeText(arr.toString(), Charsets.UTF_8)
            Log.d(TAG, "Saved lines to L2 disk cache: ${file.name} (${lines.size} lines)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist lines to disk: ${t.message}")
        }
    }

    private fun pruneDiskCacheIfNeeded(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size > MAX_DISK_ENTRIES) {
            val sorted = files.sortedBy { it.lastModified() }
            val removeCount = files.size - MAX_DISK_ENTRIES + 50
            for (i in 0 until removeCount.coerceAtMost(sorted.size)) {
                sorted[i].delete()
            }
            Log.d(TAG, "Pruned $removeCount oldest disk cache entries")
        }
    }
}
