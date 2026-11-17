package app.spotxero.hooks.lyrics

import android.media.MediaMetadata
import android.util.Log
import app.spotxero.core.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors

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

    private val prefetchExecutor = Executors.newSingleThreadExecutor()

    private val lyricsCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    fun updateCurrentTrack(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        if (!title.isNullOrBlank()) {
            currentTrackTitle = title
            currentTrackArtist = artist
            currentTrackAlbum = album
            currentTrackDurationSec = if (durationMs > 0) durationMs / 1000 else null
            currentTrackId = mediaId?.substringAfterLast(":")?.takeIf { it.isNotBlank() }

            Log.d(TAG, "Current track updated: $title by $artist (id=$currentTrackId, $currentTrackDurationSec s)")

            val cacheKey = "$title::$artist".lowercase()
            val trackKey = currentTrackId?.let { "track:$it" }
            if (lyricsCache[cacheKey] == null && (trackKey == null || lyricsCache[trackKey] == null)) {
                prefetchExecutor.execute {
                    runCatching {
                        fetchAndCache(title, artist ?: "", album, currentTrackDurationSec, cacheKey, trackKey)
                    }
                }
            }
        }
    }

    fun getLyricsJson(trackId: String? = null): String? {
        val trackKey = trackId?.let { "track:$it" }
        if (trackKey != null) {
            lyricsCache[trackKey]?.let { return it }
        }

        var title = currentTrackTitle
        var artist = currentTrackArtist ?: ""
        var album = currentTrackAlbum
        var durationSec = currentTrackDurationSec

        // If trackId is provided but does not match current track or title is missing, try oEmbed fallback
        if (trackId != null && (title == null || currentTrackId != trackId)) {
            val oembedMeta = fetchTrackMetadataFromOembed(trackId)
            if (oembedMeta != null) {
                title = oembedMeta.first
                artist = oembedMeta.second
            }
        }

        if (title.isNullOrBlank()) return null

        val cacheKey = "$title::$artist".lowercase()
        lyricsCache[cacheKey]?.let {
            if (trackKey != null) lyricsCache[trackKey] = it
            return it
        }

        return fetchAndCache(title, artist, album, durationSec, cacheKey, trackKey)
    }

    private fun fetchAndCache(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long?,
        cacheKey: String,
        trackKey: String?
    ): String? {
        val order = PreferencesManager.lyricsProvidersOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        for (providerId in order) {
            val provider: LyricsProvider? = when (providerId) {
                "local" -> if (PreferencesManager.isLocalLyricsEnabled) LocalLrcProvider else null
                "betterlyrics" -> if (PreferencesManager.isBetterlyricsEnabled) BetterLyricsProvider else null
                "lrcred" -> if (PreferencesManager.isLrcredEnabled) LrcRedProvider else null
                "lrclib" -> if (PreferencesManager.isLrclibEnabled) LrclibProvider else null
                "kugou" -> if (PreferencesManager.isKugouEnabled) KuGouLyricsProvider else null
                "netease" -> if (PreferencesManager.isNeteaseEnabled) NeteaseProvider else null
                "genius" -> if (PreferencesManager.isGeniusEnabled) GeniusProvider else null
                else -> null
            }

            if (provider == null) continue

            try {
                val lyrics = provider.fetchLyrics(
                    trackName = title,
                    artistName = artist,
                    albumName = album,
                    durationSec = durationSec
                )
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    val finalLyrics = if (PreferencesManager.isAiLyricsTranslationEnabled) {
                        AiLyricsTranslator.translateLyrics(lyrics, cacheKey)
                    } else {
                        lyrics
                    }
                    val synthesized = buildSpotifyLyricsJson(finalLyrics, provider.displayName)
                    lyricsCache[cacheKey] = synthesized
                    if (trackKey != null) {
                        lyricsCache[trackKey] = synthesized
                    }
                    return synthesized
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Provider ${provider.displayName} error: ${t.message}")
            }
        }

        return null
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

    fun buildSpotifyLyricsJson(lyrics: ParsedLyrics, providerName: String): String {
        val root = JSONObject()
        val lyricsObj = JSONObject()

        val syncTypeName = when (lyrics.syncType) {
            SyncType.SYLLABLE_SYNCED -> "LINE_SYNCED" // Spotify client requires LINE_SYNCED or UNSYNCED
            SyncType.LINE_SYNCED -> "LINE_SYNCED"
            SyncType.UNSYNCED -> "UNSYNCED"
        }
        lyricsObj.put("syncType", syncTypeName)

        val linesArray = JSONArray()
        for (line in lyrics.lines) {
            val lineObj = JSONObject()
            lineObj.put("startTimeMs", line.startTimeMs.toString())
            lineObj.put("words", line.words)

            val syllablesArray = JSONArray()
            for (syllable in line.syllables) {
                val sylObj = JSONObject()
                sylObj.put("startTimeMs", syllable.startTimeMs.toString())
                sylObj.put("numChars", syllable.numChars)
                syllablesArray.put(sylObj)
            }
            lineObj.put("syllables", syllablesArray)

            linesArray.put(lineObj)
        }
        lyricsObj.put("lines", linesArray)
        lyricsObj.put("provider", providerName)
        lyricsObj.put("providerLyricsId", "0")
        lyricsObj.put("providerDisplayName", providerName)
        lyricsObj.put("syncLyricsUri", "")
        lyricsObj.put("isDenseTypeface", false)
        lyricsObj.put("alternatives", JSONArray())
        lyricsObj.put("language", "en")
        lyricsObj.put("isRtlLanguage", false)
        lyricsObj.put("fullscreenAction", "FULLSCREEN_LYRICS")
        lyricsObj.put("showUpsell", false)

        root.put("lyrics", lyricsObj)

        val colorsObj = JSONObject()
        colorsObj.put("background", -16777216)
        colorsObj.put("text", -1)
        colorsObj.put("highlightText", -16711936)
        root.put("colors", colorsObj)
        root.put("hasVocalRemoval", false)

        return root.toString()
    }
}
