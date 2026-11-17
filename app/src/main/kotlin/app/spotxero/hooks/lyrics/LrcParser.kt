package app.spotxero.hooks.lyrics

import java.util.regex.Pattern

data class LyricSyllable(
    val startTimeMs: Long,
    val numChars: Int
)

data class LyricLine(
    val startTimeMs: Long,
    val words: String,
    val syllables: List<LyricSyllable> = emptyList()
)

enum class SyncType {
    UNSYNCED,
    LINE_SYNCED,
    SYLLABLE_SYNCED
}

data class ParsedLyrics(
    val syncType: SyncType,
    val lines: List<LyricLine>
) {
    val isSynced: Boolean
        get() = syncType != SyncType.UNSYNCED
}

object LrcParser {

    private val LINE_TIME_TAG_PATTERN = Pattern.compile("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME_TAG_PATTERN = Pattern.compile("""<(\d{1,2}:\d{2}(?:[.:]\d{1,3})?|\d{1,7})>|\((\d{1,2}:\d{2}(?:[.:]\d{1,3})?)\)""")

    fun parse(lrcContent: String?): ParsedLyrics? {
        if (lrcContent.isNullOrBlank()) return null

        val lines = lrcContent.lines()
        val parsedLines = mutableListOf<LyricLine>()
        var hasValidTimestamp = false
        var hasSyllableSync = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val matcher = LINE_TIME_TAG_PATTERN.matcher(line)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val totalMs = parseTimeString(
                    minStr = matcher.group(1),
                    secStr = matcher.group(2),
                    msStr = matcher.group(3)
                )
                timestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                hasValidTimestamp = true
                val remainingText = line.substring(lastMatchEnd).trim()
                val (cleanWords, syllables) = parseWordTimestamps(remainingText, timestamps.first())

                if (syllables.isNotEmpty()) {
                    hasSyllableSync = true
                }

                for (ts in timestamps) {
                    parsedLines.add(
                        LyricLine(
                            startTimeMs = ts,
                            words = cleanWords,
                            syllables = syllables
                        )
                    )
                }
            } else if (!line.startsWith("[") || !line.endsWith("]")) {
                val (cleanWords, syllables) = parseWordTimestamps(line, 0L)
                if (syllables.isNotEmpty()) {
                    hasValidTimestamp = true
                    hasSyllableSync = true
                    val start = syllables.firstOrNull()?.startTimeMs ?: 0L
                    parsedLines.add(LyricLine(startTimeMs = start, words = cleanWords, syllables = syllables))
                } else {
                    parsedLines.add(LyricLine(startTimeMs = 0L, words = line))
                }
            }
        }

        if (parsedLines.isEmpty()) return null

        return if (hasValidTimestamp) {
            parsedLines.sortBy { it.startTimeMs }
            val syncType = if (hasSyllableSync) SyncType.SYLLABLE_SYNCED else SyncType.LINE_SYNCED
            ParsedLyrics(syncType = syncType, lines = parsedLines)
        } else {
            ParsedLyrics(syncType = SyncType.UNSYNCED, lines = parsedLines)
        }
    }

    private fun parseTimeString(minStr: String?, secStr: String?, msStr: String?): Long {
        val min = minStr?.toLongOrNull() ?: 0L
        val sec = secStr?.toLongOrNull() ?: 0L
        val ms = when {
            msStr.isNullOrEmpty() -> 0L
            msStr.length == 1 -> (msStr.toLongOrNull() ?: 0L) * 100
            msStr.length == 2 -> (msStr.toLongOrNull() ?: 0L) * 10
            else -> msStr.take(3).toLongOrNull() ?: 0L
        }
        return (min * 60 + sec) * 1000 + ms
    }

    fun parseTimestampTag(tag: String): Long {
        val trimmed = tag.trim()
        val colon = trimmed.indexOf(':')
        if (colon < 0) {
            return trimmed.toLongOrNull() ?: 0L
        }
        val min = trimmed.substring(0, colon).toLongOrNull() ?: 0L
        val rest = trimmed.substring(colon + 1)
        val dot = rest.indexOfFirst { it == '.' || it == ':' }
        val (sec, ms) = if (dot >= 0) {
            val s = rest.substring(0, dot).toLongOrNull() ?: 0L
            val frac = rest.substring(dot + 1)
            val m = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> (frac.toLongOrNull() ?: 0L) * 100
                frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10
                else -> frac.take(3).toLongOrNull() ?: 0L
            }
            s to m
        } else {
            (rest.toLongOrNull() ?: 0L) to 0L
        }
        return (min * 60 + sec) * 1000 + ms
    }

    private fun parseWordTimestamps(rawText: String, lineStartTimeMs: Long): Pair<String, List<LyricSyllable>> {
        val matcher = WORD_TIME_TAG_PATTERN.matcher(rawText)
        if (!matcher.find()) {
            return rawText to emptyList()
        }

        matcher.reset()
        val syllables = mutableListOf<LyricSyllable>()
        val wordsBuilder = StringBuilder()

        var currentWordStart = 0
        var currentWordTimestamp = lineStartTimeMs
        var hasWord = false

        while (matcher.find()) {
            val tagStart = matcher.start()
            val tagEnd = matcher.end()
            val timeTag = matcher.group(1)?.takeIf { it.isNotEmpty() } ?: matcher.group(2).orEmpty()
            val ts = parseTimestampTag(timeTag)

            if (tagStart > currentWordStart) {
                val wordPart = rawText.substring(currentWordStart, tagStart)
                if (hasWord) {
                    wordsBuilder.append(wordPart)
                    syllables.add(LyricSyllable(startTimeMs = currentWordTimestamp, numChars = wordPart.length))
                } else if (wordPart.isNotBlank()) {
                    wordsBuilder.append(wordPart)
                    syllables.add(LyricSyllable(startTimeMs = currentWordTimestamp, numChars = wordPart.length))
                }
            }

            currentWordTimestamp = ts
            currentWordStart = tagEnd
            hasWord = true
        }

        if (currentWordStart < rawText.length) {
            val trailing = rawText.substring(currentWordStart)
            if (trailing.isNotEmpty()) {
                wordsBuilder.append(trailing)
                syllables.add(LyricSyllable(startTimeMs = currentWordTimestamp, numChars = trailing.length))
            }
        }

        val cleanWords = wordsBuilder.toString().trim()
        return (if (cleanWords.isNotEmpty()) cleanWords else rawText) to syllables
    }

    private val TTML_P_PATTERN = Pattern.compile("""<p\s+[^>]*begin="([^"]+)"[^>]*>(.*?)</p>""", Pattern.DOTALL)
    private val TTML_SPAN_PATTERN = Pattern.compile("""<span\s+[^>]*begin="([^"]+)"[^>]*>(.*?)</span>""", Pattern.DOTALL)

    fun parseTtml(ttmlContent: String?): ParsedLyrics? {
        if (ttmlContent.isNullOrBlank()) return null
        val pMatcher = TTML_P_PATTERN.matcher(ttmlContent)
        val parsedLines = mutableListOf<LyricLine>()
        var hasSyllableSync = false

        while (pMatcher.find()) {
            val beginStr = pMatcher.group(1).orEmpty()
            val inner = pMatcher.group(2).orEmpty()
            val lineStartMs = parseTtmlTime(beginStr)

            val spanMatcher = TTML_SPAN_PATTERN.matcher(inner)
            val syllables = mutableListOf<LyricSyllable>()
            val lineWordsBuilder = StringBuilder()

            while (spanMatcher.find()) {
                val spanBeginStr = spanMatcher.group(1).orEmpty()
                val rawWord = spanMatcher.group(2).orEmpty()
                val cleanWord = rawWord.replace(Regex("""<[^>]+>"""), "")
                if (cleanWord.isNotEmpty()) {
                    val spanStartMs = parseTtmlTime(spanBeginStr)
                    val leadingSpace = if (lineWordsBuilder.isNotEmpty() && !lineWordsBuilder.endsWith(" ") && !cleanWord.startsWith(" ")) " " else ""
                    lineWordsBuilder.append(leadingSpace).append(cleanWord)
                    syllables.add(LyricSyllable(startTimeMs = spanStartMs, numChars = cleanWord.length))
                }
            }

            val finalWords = if (lineWordsBuilder.isNotEmpty()) {
                hasSyllableSync = true
                lineWordsBuilder.toString().trim()
            } else {
                inner.replace(Regex("""<[^>]+>"""), "").trim()
            }

            if (finalWords.isNotBlank()) {
                parsedLines.add(
                    LyricLine(
                        startTimeMs = lineStartMs,
                        words = finalWords,
                        syllables = syllables
                    )
                )
            }
        }

        if (parsedLines.isEmpty()) return null
        parsedLines.sortBy { it.startTimeMs }
        val syncType = if (hasSyllableSync) SyncType.SYLLABLE_SYNCED else SyncType.LINE_SYNCED
        return ParsedLyrics(syncType = syncType, lines = parsedLines)
    }

    private fun parseTtmlTime(timeStr: String): Long {
        val trimmed = timeStr.trim()
        if (trimmed.isEmpty()) return 0L
        if (trimmed.contains(':')) {
            return parseTimestampTag(trimmed)
        }
        val sec = trimmed.toDoubleOrNull() ?: 0.0
        return (sec * 1000).toLong()
    }
}

