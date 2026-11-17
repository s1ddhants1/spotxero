package app.spotxero.hooks.lyrics

import android.media.MediaMetadata
import android.util.Log
import app.spotxero.core.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object LyricsManager {

    private const val TAG = "SpotXero.LyricsManager"

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

    private val lyricsCache = ConcurrentHashMap<String, String>()

    fun updateCurrentTrack(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (!title.isNullOrBlank()) {
            currentTrackTitle = title
            currentTrackArtist = artist
            currentTrackAlbum = album
            currentTrackDurationSec = if (durationMs > 0) durationMs / 1000 else null
            Log.d(TAG, "Current track updated: $title by $artist ($currentTrackDurationSec s)")
        }
    }

    fun getLyricsJson(trackId: String? = null): String? {
        val title = currentTrackTitle ?: return null
        val artist = currentTrackArtist ?: ""
        val cacheKey = "$title::$artist".lowercase()

        lyricsCache[cacheKey]?.let { return it }

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
                    albumName = currentTrackAlbum,
                    durationSec = currentTrackDurationSec
                )
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    val finalLyrics = if (PreferencesManager.isAiLyricsTranslationEnabled) {
                        AiLyricsTranslator.translateLyrics(lyrics, cacheKey)
                    } else {
                        lyrics
                    }
                    val synthesized = buildSpotifyLyricsJson(finalLyrics, provider.displayName)
                    lyricsCache[cacheKey] = synthesized
                    return synthesized
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Provider ${provider.displayName} error: ${t.message}")
            }
        }

        return null
    }

    fun buildSpotifyLyricsJson(lyrics: ParsedLyrics, providerName: String): String {
        val root = JSONObject()
        val lyricsObj = JSONObject()

        val syncTypeName = when (lyrics.syncType) {
            SyncType.SYLLABLE_SYNCED -> "SYLLABLE_SYNCED"
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
        lyricsObj.put("providerLyricsId", "")
        lyricsObj.put("providerDisplayName", providerName)

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
