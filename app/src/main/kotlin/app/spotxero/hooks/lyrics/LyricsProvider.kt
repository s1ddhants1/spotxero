package app.spotxero.hooks.lyrics

import android.net.Uri
import android.os.Environment
import android.text.Html
import android.util.Base64
import android.util.Log
import app.spotxero.core.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern
import kotlin.math.abs

interface LyricsProvider {
    val id: String
    val displayName: String
    fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics?
}

object LocalLrcProvider : LyricsProvider {
    override val id = "local"
    override val displayName = "Local Files"
    private const val TAG = "SpotXero.LocalLyrics"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        val searchDirs = listOfNotNull(
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Lyrics") }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) }.getOrNull(),
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lyrics") }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { File(Environment.getExternalStorageDirectory(), "Lyrics") }.getOrNull(),
            runCatching { File("/sdcard/Music/Lyrics") }.getOrNull(),
            runCatching { File("/sdcard/Lyrics") }.getOrNull(),
            runCatching { File("/sdcard/Download/Lyrics") }.getOrNull()
        ).filter { it.isDirectory && it.canRead() }

        val cleanTrack = sanitizeFilename(trackName)
        val cleanArtist = sanitizeFilename(artistName)

        val candidateNames = listOf(
            "$trackName.lrc",
            "$artistName - $trackName.lrc",
            "$trackName - $artistName.lrc",
            "$cleanTrack.lrc",
            "$cleanArtist - $cleanTrack.lrc",
            "$cleanTrack - $cleanArtist.lrc"
        ).distinct()

        for (dir in searchDirs) {
            val files = dir.listFiles() ?: continue
            for (candidate in candidateNames) {
                val matched = files.firstOrNull { it.isFile && it.name.equals(candidate, ignoreCase = true) }
                if (matched != null) {
                    try {
                        val content = matched.readText(Charsets.UTF_8)
                        val parsed = LrcParser.parse(content)
                        if (parsed != null && parsed.lines.isNotEmpty()) {
                            Log.i(TAG, "Loaded local lyrics: ${matched.absolutePath}")
                            return parsed
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error reading local lyrics ${matched.absolutePath}: ${t.message}")
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[/\\?%*:|"<>]+"""), "_").trim()
    }
}

object BetterLyricsProvider : LyricsProvider {
    override val id = "betterlyrics"
    override val displayName = "BetterLyrics"
    private const val TAG = "SpotXero.BetterLyrics"
    private const val BASE_URL = "https://lyrics-api.boidu.dev/getLyrics"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        val cleanTitle = trackName.trim()
        val cleanArtist = artistName.trim()
        val trimmedAlbum = albumName?.trim()?.takeIf { it.isNotEmpty() }

        fetchTtml(cleanTitle, cleanArtist, durationSec, trimmedAlbum)?.let { ttml ->
            val parsed = LrcParser.parseTtml(ttml)
            if (parsed != null && parsed.lines.isNotEmpty()) return parsed
        }

        if (trimmedAlbum != null) {
            fetchTtml(cleanTitle, cleanArtist, durationSec, null)?.let { ttml ->
                val parsed = LrcParser.parseTtml(ttml)
                if (parsed != null && parsed.lines.isNotEmpty()) return parsed
            }
        }
        return null
    }

    private fun fetchTtml(title: String, artist: String, durationSec: Long?, album: String?): String? {
        return try {
            val builder = Uri.parse(BASE_URL).buildUpon()
                .appendQueryParameter("s", title)
                .appendQueryParameter("a", artist)
            if (durationSec != null && durationSec > 0) {
                builder.appendQueryParameter("d", durationSec.toString())
            }
            if (!album.isNullOrBlank()) {
                builder.appendQueryParameter("al", album)
            }
            val headers = mapOf("User-Agent" to "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
            val resp = httpGet(builder.build().toString(), headers) ?: return null
            val json = JSONObject(resp)
            val ttml = json.optString("ttml")
            if (ttml.isNotBlank()) ttml else null
        } catch (t: Throwable) {
            Log.d(TAG, "BetterLyrics fetch failed: ${t.message}")
            null
        }
    }
}

object LrclibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayName = "LRCLIB"
    private const val TAG = "SpotXero.LRCLIB"
    private const val BASE_URL = "https://lrclib.net/api"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        try {
            val builder = Uri.parse("$BASE_URL/get").buildUpon()
                .appendQueryParameter("track_name", trackName)
                .appendQueryParameter("artist_name", artistName)
            if (!albumName.isNullOrBlank()) {
                builder.appendQueryParameter("album_name", albumName)
            }
            if (durationSec != null && durationSec > 0) {
                builder.appendQueryParameter("duration", durationSec.toString())
            }

            val resp = httpGet(builder.build().toString())
            if (resp != null) {
                val json = JSONObject(resp)
                val synced = json.optString("syncedLyrics")
                if (synced.isNotBlank()) {
                    val parsed = LrcParser.parse(synced)
                    if (parsed != null) return parsed
                }
                val plain = json.optString("plainLyrics")
                if (plain.isNotBlank()) {
                    val parsed = LrcParser.parse(plain)
                    if (parsed != null) return parsed
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "LRCLIB get failed: ${t.message}")
        }

        return try {
            val query = "$trackName $artistName".trim()
            val url = "$BASE_URL/search?q=" + URLEncoder.encode(query, "UTF-8")
            val resp = httpGet(url) ?: return null
            val array = JSONArray(resp)
            if (array.length() == 0) return null

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val synced = item.optString("syncedLyrics")
                if (synced.isNotBlank()) {
                    val parsed = LrcParser.parse(synced)
                    if (parsed != null) return parsed
                }
            }

            val first = array.getJSONObject(0)
            val plain = first.optString("plainLyrics")
            if (plain.isNotBlank()) {
                LrcParser.parse(plain)
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LRCLIB search failed: ${t.message}")
            null
        }
    }
}

object KuGouLyricsProvider : LyricsProvider {
    override val id = "kugou"
    override val displayName = "KuGou"
    private const val TAG = "SpotXero.KuGou"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        return try {
            val query = if (artistName.isNotBlank()) "$trackName - $artistName" else trackName
            val durationMs = if (durationSec != null && durationSec > 0) durationSec * 1000L else 0L

            val searchBuilder = Uri.parse("https://lyrics.kugou.com/search").buildUpon()
                .appendQueryParameter("ver", "1")
                .appendQueryParameter("man", "yes")
                .appendQueryParameter("client", "pc")
                .appendQueryParameter("keyword", query)
            if (durationMs > 0) {
                searchBuilder.appendQueryParameter("duration", durationMs.toString())
            }

            val searchResp = httpGet(searchBuilder.build().toString()) ?: return null
            val searchJson = JSONObject(searchResp)
            val candidates = searchJson.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            var bestId: Long = 0L
            var bestAccessKey = ""
            var minDiff = Long.MAX_VALUE

            for (i in 0 until candidates.length()) {
                val c = candidates.getJSONObject(i)
                val id = c.optLong("id")
                val key = c.optString("accesskey")
                val candDuration = c.optLong("duration")

                if (id <= 0 || key.isBlank()) continue

                if (durationMs > 0 && candDuration > 0) {
                    val diff = abs(candDuration - durationMs)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestId = id
                        bestAccessKey = key
                    }
                } else if (bestId == 0L) {
                    bestId = id
                    bestAccessKey = key
                }
            }

            if (bestId == 0L || bestAccessKey.isBlank()) return null

            val downloadUrl = Uri.parse("https://lyrics.kugou.com/download").buildUpon()
                .appendQueryParameter("fmt", "lrc")
                .appendQueryParameter("charset", "utf8")
                .appendQueryParameter("client", "pc")
                .appendQueryParameter("ver", "1")
                .appendQueryParameter("id", bestId.toString())
                .appendQueryParameter("accesskey", bestAccessKey)
                .build().toString()

            val downloadResp = httpGet(downloadUrl) ?: return null
            val downloadJson = JSONObject(downloadResp)
            val contentBase64 = downloadJson.optString("content")
            if (contentBase64.isBlank()) return null

            val decodedBytes = Base64.decode(contentBase64, Base64.DEFAULT)
            val lrcString = String(decodedBytes, Charsets.UTF_8)
            LrcParser.parse(lrcString)
        } catch (t: Throwable) {
            Log.w(TAG, "KuGou lyrics fetch failed: ${t.message}")
            null
        }
    }
}

object NeteaseProvider : LyricsProvider {
    override val id = "netease"
    override val displayName = "NetEase"
    private const val TAG = "SpotXero.NetEase"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        return try {
            val query = "$trackName $artistName".trim()
            val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=" +
                    URLEncoder.encode(query, "UTF-8") + "&type=1&offset=0&total=true&limit=1"

            val searchHeaders = mapOf("Cookie" to "os=pc")
            val searchResp = httpGet(searchUrl, searchHeaders) ?: return null
            val searchJson = JSONObject(searchResp)
            val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val songId = songs.getJSONObject(0).optLong("id")
            if (songId <= 0) return null

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            val lyricResp = httpGet(lyricUrl, searchHeaders) ?: return null
            val lyricJson = JSONObject(lyricResp)

            val lrcString = lyricJson.optJSONObject("lrc")?.optString("lyric")
            val tlyricString = lyricJson.optJSONObject("tlyric")?.optString("lyric")

            if (lrcString.isNullOrBlank()) return null

            val originalParsed = LrcParser.parse(lrcString) ?: return null

            val translationsEnabled = PreferencesManager.isLyricsTranslationsEnabled
            if (translationsEnabled && !tlyricString.isNullOrBlank()) {
                val transParsed = LrcParser.parse(tlyricString)
                if (transParsed != null && transParsed.lines.isNotEmpty()) {
                    return mergeTranslations(originalParsed, transParsed)
                }
            }

            originalParsed
        } catch (t: Throwable) {
            Log.w(TAG, "NetEase lyrics failed: ${t.message}")
            null
        }
    }

    private fun mergeTranslations(original: ParsedLyrics, translation: ParsedLyrics): ParsedLyrics {
        val mergedLines = mutableListOf<LyricLine>()
        val transLines = translation.lines

        for (orig in original.lines) {
            val matchingTrans = transLines.firstOrNull { abs(it.startTimeMs - orig.startTimeMs) <= 600L }
            if (matchingTrans != null && matchingTrans.words.isNotBlank()) {
                val combinedWords = "${orig.words}\n${matchingTrans.words}"
                mergedLines.add(
                    LyricLine(
                        startTimeMs = orig.startTimeMs,
                        words = combinedWords,
                        syllables = orig.syllables
                    )
                )
            } else {
                mergedLines.add(orig)
            }
        }

        return ParsedLyrics(syncType = original.syncType, lines = mergedLines)
    }
}

object LrcRedProvider : LyricsProvider {
    override val id = "lrcred"
    override val displayName = "lrc.red"
    private const val TAG = "SpotXero.LRCRed"
    private const val BASE_URL = "https://lrc.red"

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        val query = "$trackName $artistName".trim()
        val searchUrl = "$BASE_URL/search.json?q=" + URLEncoder.encode(query, "UTF-8")
        val headers = mapOf("X-Client-ID" to "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
        val resp = httpGet(searchUrl, headers) ?: return null

        return try {
            val json = JSONObject(resp)
            val hits = json.optJSONArray("hits") ?: return null
            if (hits.length() == 0) return null

            val cleanTrack = normalize(trackName)
            val cleanArtist = normalize(artistName)

            var bestIsrc: String? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until hits.length()) {
                val hit = hits.getJSONObject(i)
                val hitIsrc = hit.optString("isrc")
                if (hitIsrc.isBlank()) continue

                val hitTitle = normalize(hit.optString("title"))
                val hitArtist = normalize(hit.optString("artist"))
                val hitDuration = hit.optDouble("duration", 0.0)

                var score = 0
                if (hitTitle == cleanTrack) score += 10
                else if (hitTitle.contains(cleanTrack) || cleanTrack.contains(hitTitle)) score += 5

                if (hitArtist.contains(cleanArtist) || cleanArtist.contains(hitArtist)) score += 5

                if (durationSec != null && durationSec > 0 && hitDuration > 0) {
                    val diff = abs(hitDuration - durationSec)
                    if (diff <= 3) score += 5
                    else if (diff <= 8) score += 2
                    else if (diff > 15) score -= 5
                }

                if (score > bestScore) {
                    bestScore = score
                    bestIsrc = hitIsrc
                }
            }

            val isrcToFetch = bestIsrc ?: hits.getJSONObject(0).optString("isrc")
            if (isrcToFetch.isNullOrBlank()) return null

            // 1. Fetch .lrc directly (provides word-level syllable sync)
            val lrcUrl = "$BASE_URL/s/$isrcToFetch.lrc"
            val lrcContent = httpGet(lrcUrl, headers)
            if (!lrcContent.isNullOrBlank()) {
                val parsed = LrcParser.parse(lrcContent)
                if (parsed != null && parsed.lines.isNotEmpty()) {
                    return parsed
                }
            }

            // 2. Fallback to song json if direct .lrc fails
            val metaUrl = "$BASE_URL/s/$isrcToFetch.json"
            val metaResp = httpGet(metaUrl, headers)
            if (!metaResp.isNullOrBlank()) {
                val metaJson = JSONObject(metaResp)
                val files = metaJson.optJSONObject("files")
                val filesLrc = files?.optString("lrc")
                if (!filesLrc.isNullOrBlank()) {
                    val content = httpGet(filesLrc, headers)
                    if (!content.isNullOrBlank()) {
                        val parsed = LrcParser.parse(content)
                        if (parsed != null && parsed.lines.isNotEmpty()) return parsed
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "lrc.red fetch failed: ${t.message}")
            null
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("""\s*[\[(](?:feat(?:uring)?|ft|with)\.?\s+[^)\]]*[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }
}

object GeniusProvider : LyricsProvider {
    override val id = "genius"
    override val displayName = "Genius"
    private const val TAG = "SpotXero.Genius"
    private val CONTAINER_PATTERN = Pattern.compile("""<div[^>]*data-lyrics-container="true"[^>]*>(.*?)</div>""", Pattern.DOTALL)

    override fun fetchLyrics(trackName: String, artistName: String, albumName: String?, durationSec: Long?): ParsedLyrics? {
        val cleanTitle = removeExtraInfo(trackName)

        // 1. Attempt Genius song search and HTML scrape
        try {
            val query = "$artistName $cleanTitle".trim()
            val searchUrl = "https://genius.com/api/search/song?per_page=5&q=" + URLEncoder.encode(query, "UTF-8")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept" to "application/json"
            )

            val searchResp = httpGet(searchUrl, headers)
            if (!searchResp.isNullOrBlank()) {
                val json = JSONObject(searchResp)
                val sections = json.optJSONObject("response")?.optJSONArray("sections")
                val hits = sections?.optJSONObject(0)?.optJSONArray("hits")

                if (hits != null && hits.length() > 0) {
                    for (i in 0 until hits.length()) {
                        val hitResult = hits.getJSONObject(i).optJSONObject("result") ?: continue
                        val songUrl = hitResult.optString("url")
                        if (songUrl.isNotBlank()) {
                            val lyrics = fetchAndParseGeniusHtml(songUrl)
                            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                                return lyrics
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Genius scrape attempt failed: ${t.message}")
        }

        // 2. Fallback to public lyrics API (api.lyrics.ovh) which aggregates lyrics sources including Genius
        try {
            val ovhUrl = "https://api.lyrics.ovh/v1/" +
                    URLEncoder.encode(artistName, "UTF-8") + "/" +
                    URLEncoder.encode(cleanTitle, "UTF-8")
            val ovhResp = httpGet(ovhUrl)
            if (!ovhResp.isNullOrBlank()) {
                val json = JSONObject(ovhResp)
                val lyricsText = json.optString("lyrics")
                if (lyricsText.isNotBlank()) {
                    val lines = lyricsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { LyricLine(startTimeMs = 0L, words = it) }
                    if (lines.isNotEmpty()) {
                        return ParsedLyrics(syncType = SyncType.UNSYNCED, lines = lines)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Genius fallback failed: ${t.message}")
        }

        return null
    }

    private fun fetchAndParseGeniusHtml(songUrl: String): ParsedLyrics? {
        val htmlHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val html = httpGet(songUrl, htmlHeaders) ?: return null
        if (html.contains("cf-chl-opt") || html.contains("challenge-error-text")) {
            return null
        }

        val matcher = CONTAINER_PATTERN.matcher(html)
        val sb = StringBuilder()

        while (matcher.find()) {
            val content = matcher.group(1).orEmpty()
            sb.append(content).append("<br>")
        }

        if (sb.isEmpty()) return null

        val rawText = sb.toString()
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        val cleanText = Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY).toString()

        val lines = cleanText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.endsWith("]") }
            .map { LyricLine(startTimeMs = 0L, words = it) }

        return if (lines.isNotEmpty()) ParsedLyrics(syncType = SyncType.UNSYNCED, lines = lines) else null
    }

    private fun removeExtraInfo(title: String): String {
        return title
            .replace(Regex("""\s*[\[(](?:feat(?:uring)?|ft|with)\.?\s+[^)\]]*[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–—]\s*(?:feat(?:uring)?|ft)\.?\s+.+$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[\[(][^()\[\]]*(?:remaster|deluxe|version|mono|stereo|bonus)[^()\[\]]*[\])]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

private fun httpGet(urlString: String, headers: Map<String, String> = emptyMap(), redirectCount: Int = 0): String? {
    if (redirectCount > 3) return null
    return try {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            if (!location.isNullOrBlank()) {
                val nextUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                return httpGet(nextUrl, headers, redirectCount + 1)
            }
        }
        if (code in 200..299) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = reader.readLine()
            }
            reader.close()
            sb.toString()
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }
}

