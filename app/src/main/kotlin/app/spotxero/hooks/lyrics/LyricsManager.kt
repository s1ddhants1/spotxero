package app.spotxero.hooks.lyrics

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.util.Log
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LyricsManager {

    private const val TAG = "SpotXero.LyricsManager"
    private const val MAX_CACHE_SIZE = 50

    @Volatile
    var currentTrackTitle: String? = null
        private set

    @Volatile
    var currentTrackArtist: String? = null
        private set

    @Volatile
    var currentTrackAlbum: String? = null
        private set

    @Volatile
    var currentTrackDurationSec: Long? = null
        private set

    @Volatile
    var currentTrackId: String? = null
        private set

    @Volatile
    var currentTrackColor: Int? = null
        private set

    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LyricsPrefetch").apply { isDaemon = true }
    }

    data class CachedLyrics(
        val lyrics: ParsedLyrics,
        val providerDisplayName: String,
        val backgroundColor: Int? = null,
        val language: String = "en",
        val isRtl: Boolean = false
    )

    private val parsedCache: MutableMap<String, CachedLyrics> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedLyrics>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private val inFlightFetches = ConcurrentHashMap<String, CompletableFuture<CachedLyrics?>>()

    fun updateCurrentTrack(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        if (!title.isNullOrBlank()) {
            val durationSec = if (durationMs > 0) durationMs / 1000 else null
            currentTrackTitle = title
            currentTrackArtist = artist
            currentTrackAlbum = album
            currentTrackDurationSec = durationSec
            currentTrackId = mediaId?.substringAfterLast(":")?.takeIf { it.isNotBlank() }

            val artBitmap = runCatching {
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }.getOrNull()
            currentTrackColor = artBitmap?.let { runCatching { extractDarkColor(it) }.getOrNull() }

            Log.d(TAG, "Current track updated: $title by $artist (id=$currentTrackId, $durationSec s, color=$currentTrackColor)")

            val cacheKey = buildCacheKey(title, artist ?: "", album)
            val trackKey = currentTrackId?.let { buildTrackKey(it) }
            if (PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false) && parsedCache[cacheKey] == null && (trackKey == null || parsedCache[trackKey] == null)) {
                val future = CompletableFuture<CachedLyrics?>()
                val existing = inFlightFetches.putIfAbsent(cacheKey, future)
                if (existing == null) {
                    prefetchExecutor.execute {
                        try {
                            val result = fetchAndCache(title, artist ?: "", album, durationSec, currentTrackId, cacheKey, trackKey)
                            future.complete(result)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Prefetch failed: ${t.message}", t)
                            future.complete(null)
                        } finally {
                            inFlightFetches.remove(cacheKey, future)
                        }
                    }
                }
            }
        }
    }

    private fun buildTranslationKey(): String {
        return if (PreferencesManager.isEnabled(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, false)) {
            val prompt = PreferencesManager.aiTranslationCustomPrompt.trim()
            val baseUrl = PreferencesManager.aiTranslationBaseUrl.trim()
            val formality = PreferencesManager.aiTranslationDeeplFormality.ifBlank { "default" }
            val promptHash = if (prompt.isNotEmpty() || baseUrl.isNotEmpty()) {
                "$baseUrl|$prompt".hashCode().toString()
            } else {
                "default"
            }
            "${PreferencesManager.aiTranslationTargetLang}:${PreferencesManager.aiTranslationTranslateMode}:${PreferencesManager.isAiTranslationDualLine}:${PreferencesManager.aiTranslationProvider}:${PreferencesManager.aiTranslationModel}:$formality:$promptHash"
        } else {
            "orig"
        }
    }

    private fun buildProviderKey(): String {
        val order = PreferencesManager.lyricsProvidersOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val activeProviders = order.filter { providerId ->
            when (providerId) {
                "spicylyrics" -> PreferencesManager.isSpicylyricsEnabled
                "betterlyrics" -> PreferencesManager.isBetterlyricsEnabled
                "lrcred" -> PreferencesManager.isLrcredEnabled
                "lrclib" -> PreferencesManager.isLrclibEnabled
                "kugou" -> PreferencesManager.isKugouEnabled
                "netease" -> PreferencesManager.isNeteaseEnabled
                "genius" -> PreferencesManager.isGeniusEnabled
                else -> false
            }
        }
        return activeProviders.joinToString(",")
    }

    private fun buildCacheKey(title: String, artist: String, album: String? = null): String {
        val transKey = buildTranslationKey()
        val provKey = buildProviderKey()
        val albumPart = album?.trim()?.takeIf { it.isNotEmpty() }?.let { "::$it" } ?: ""
        return "$title::$artist$albumPart::$provKey::$transKey".lowercase()
    }

    private fun buildTrackKey(trackId: String): String {
        val transKey = buildTranslationKey()
        val provKey = buildProviderKey()
        return "track:$trackId::$provKey::$transKey".lowercase()
    }

    fun getLyrics(trackId: String? = null): CachedLyrics? {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)) return null
        val trackKey = trackId?.let { buildTrackKey(it) }
        if (trackKey != null) {
            parsedCache[trackKey]?.let { return it }
        }

        var title = currentTrackTitle
        var artist = currentTrackArtist ?: ""
        var album = currentTrackAlbum
        var durationSec = currentTrackDurationSec

        if (trackId != null && (title == null || currentTrackId != trackId)) {
            val oembedMeta = fetchTrackMetadataFromOembed(trackId)
            if (oembedMeta != null) {
                title = oembedMeta.first
                artist = oembedMeta.second
                album = null
                durationSec = null
            } else if (currentTrackId != trackId) {
                return null
            }
        }

        if (title.isNullOrBlank()) return null

        val cacheKey = buildCacheKey(title, artist, album)
        parsedCache[cacheKey]?.let {
            if (trackKey != null) parsedCache[trackKey] = it
            return it
        }

        var inFlight = inFlightFetches[cacheKey]
        if (inFlight == null) {
            val newFuture = CompletableFuture<CachedLyrics?>()
            val existing = inFlightFetches.putIfAbsent(cacheKey, newFuture)
            if (existing == null) {
                return try {
                    val result = fetchAndCache(title, artist, album, durationSec, trackId, cacheKey, trackKey)
                    newFuture.complete(result)
                    result
                } catch (t: Throwable) {
                    newFuture.complete(null)
                    throw t
                } finally {
                    inFlightFetches.remove(cacheKey, newFuture)
                }
            } else {
                inFlight = existing
            }
        }

        val result = runCatching { inFlight.get(15, TimeUnit.SECONDS) }.getOrNull()
        if (result != null) {
            if (trackKey != null) parsedCache[trackKey] = result
            return result
        }
        return parsedCache[cacheKey]?.also {
            if (trackKey != null) parsedCache[trackKey] = it
        }
    }

    fun getLyricsProto(trackId: String? = null): ByteArray? {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)) return null
        val trackKey = trackId?.let { buildTrackKey(it) }
        if (trackKey != null) {
            val persistent = PersistentLyricsCache.getProtobuf(trackKey)
            if (persistent != null) return persistent
        }
        val cached = getLyrics(trackId) ?: return null
        val proto = LyricsProtobuf.buildColorLyricsResponse(
            lyrics = cached.lyrics,
            providerName = cached.providerDisplayName,
            providerDisplayName = cached.providerDisplayName,
            backgroundColor = cached.backgroundColor ?: currentTrackColor,
            language = cached.language,
            isRtl = cached.isRtl
        )
        if (trackKey != null) {
            PersistentLyricsCache.putProtobuf(trackKey, proto)
        }
        return proto
    }

    private fun fetchAndCache(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long?,
        trackId: String?,
        cacheKey: String,
        trackKey: String?
    ): CachedLyrics? {
        val order = PreferencesManager.lyricsProvidersOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        Log.i(TAG, "fetchAndCache: requesting lyrics for '$title' by '$artist' (duration=${durationSec}s), order=$order")

        for (providerId in order) {
            val provider: LyricsProvider? = when (providerId) {
                "spicylyrics" -> if (PreferencesManager.isSpicylyricsEnabled) SpicyLyricsProvider else null
                "betterlyrics" -> if (PreferencesManager.isBetterlyricsEnabled) BetterLyricsProvider else null
                "lrcred" -> if (PreferencesManager.isLrcredEnabled) LrcRedProvider else null
                "lrclib" -> if (PreferencesManager.isLrclibEnabled) LrclibProvider else null
                "kugou" -> if (PreferencesManager.isKugouEnabled) KuGouLyricsProvider else null
                "netease" -> if (PreferencesManager.isNeteaseEnabled) NeteaseProvider else null
                "genius" -> if (PreferencesManager.isGeniusEnabled) GeniusProvider else null
                else -> null
            }

            if (provider == null) {
                Log.d(TAG, "Provider $providerId is disabled or not found")
                continue
            }

            try {
                Log.i(TAG, "Querying provider: ${provider.displayName} ($providerId)")
                val lyrics = provider.fetchLyrics(
                    trackName = title,
                    artistName = artist,
                    albumName = album,
                    durationSec = durationSec,
                    trackId = trackId ?: currentTrackId
                )
                Log.i(TAG, "Provider ${provider.displayName} returned ${lyrics?.lines?.size ?: 0} lines")
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    val aiTransEnabled = PreferencesManager.isEnabled(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, false)
                    val finalLyrics = if (aiTransEnabled) {
                        Log.i(TAG, "AI translation is enabled. Translating ${lyrics.lines.size} lines...")
                        AiLyricsTranslator.translateLyrics(lyrics, cacheKey)
                    } else {
                        Log.i(TAG, "AI translation is disabled.")
                        lyrics
                    }
                    val lang = if (aiTransEnabled) {
                        PreferencesManager.aiTranslationTargetLang.ifBlank { "en" }
                    } else {
                        if (LyricsProtobuf.isRtlText(lyrics.lines.joinToString(" ") { it.words })) "ar" else "en"
                    }
                    val isRtl = LyricsProtobuf.isRtlLanguage(lang) || LyricsProtobuf.isRtlText(finalLyrics.lines.joinToString(" ") { it.words })
                    val color = currentTrackColor
                    val cached = CachedLyrics(finalLyrics, provider.displayName, color, lang, isRtl)
                    Log.i(TAG, "Fetched ${lyrics.lines.size} lines from ${provider.displayName}")
                    parsedCache[cacheKey] = cached
                    val proto = LyricsProtobuf.buildColorLyricsResponse(
                        finalLyrics,
                        provider.displayName,
                        backgroundColor = color,
                        language = lang,
                        isRtl = isRtl
                    )
                    PersistentLyricsCache.putProtobuf(cacheKey, proto)
                    if (trackKey != null) {
                        parsedCache[trackKey] = cached
                        PersistentLyricsCache.putProtobuf(trackKey, proto)
                    }
                    return cached
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Provider ${provider.displayName} error: ${t.message}", t)
            }
        }

        Log.w(TAG, "No lyrics found from any configured provider for '$title' by '$artist'")
        return null
    }

    private fun extractDarkColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return -16777216
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                totalR += (pixel shr 16) and 0xFF
                totalG += (pixel shr 8) and 0xFF
                totalB += pixel and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return -16777216
        var r = (totalR / count).toDouble()
        var g = (totalG / count).toDouble()
        var b = (totalB / count).toDouble()
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        val maxLuminance = 60.0
        if (luminance > maxLuminance) {
            val factor = maxLuminance / luminance
            r *= factor
            g *= factor
            b *= factor
        }
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    private fun fetchTrackMetadataFromOembed(trackId: String): Pair<String, String>? {
        return try {
            val url = URL("https://open.spotify.com/oembed?url=spotify:track:$trackId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(text)
                val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
                val artist = json.optString("author_name").takeIf { it.isNotBlank() } ?: ""
                title to artist
            } else null
        } catch (_: Throwable) {
            null
        }
    }
}
