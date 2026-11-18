package app.spotxero.hooks.lyrics

import java.io.ByteArrayOutputStream

data class ParsedColorLyrics(
    val lyrics: ParsedLyrics,
    val provider: String,
    val providerDisplayName: String,
    val colorDataBytes: ByteArray?,
    val hasVocalRemoval: Boolean
)

object LyricsProtobuf {

    private const val WIRE_TYPE_VARINT = 0
    private const val WIRE_TYPE_LENGTH_DELIMITED = 2

    /**
     * Serializes lyrics into Spotify's ColorLyricsResponse protobuf wire format.
     * Schema matching com.spotify.lyrics.serviceretrofit.proto.ColorLyricsResponse:
     * - Field 1: LyricsResponse lyrics (length-delimited)
     * - Field 2: ColorData colors (length-delimited)
     * - Field 3: bool hasVocalRemoval (varint)
     */
    fun buildColorLyricsResponse(
        lyrics: ParsedLyrics,
        providerName: String,
        providerDisplayName: String = "$providerName (SpotXero)",
        customColorData: ByteArray? = null,
        hasVocalRemoval: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // Field 1: LyricsResponse
        val lyricsResponseBytes = buildLyricsResponse(lyrics, providerName, providerDisplayName)
        writeTag(out, 1, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, lyricsResponseBytes.size.toLong())
        out.write(lyricsResponseBytes)

        // Field 2: ColorData
        val colorDataBytes = customColorData ?: buildColorData()
        writeTag(out, 2, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, colorDataBytes.size.toLong())
        out.write(colorDataBytes)

        // Field 3: hasVocalRemoval
        writeTag(out, 3, WIRE_TYPE_VARINT)
        writeVarint(out, if (hasVocalRemoval) 1L else 0L)

        return out.toByteArray()
    }

    /**
     * Parses a ColorLyricsResponse protobuf payload into ParsedColorLyrics.
     */
    fun parseColorLyricsResponse(bytes: ByteArray): ParsedColorLyrics? {
        val reader = ProtoReader(bytes)
        var lyricsBytes: ByteArray? = null
        var colorDataBytes: ByteArray? = null
        var hasVocalRemoval = false

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        lyricsBytes = reader.readBytes()
                    } else reader.skip(wireType)
                }
                2 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        colorDataBytes = reader.readBytes()
                    } else reader.skip(wireType)
                }
                3 -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        hasVocalRemoval = reader.readVarint() != 0L
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }

        if (lyricsBytes == null) return null
        return parseLyricsResponse(lyricsBytes, colorDataBytes, hasVocalRemoval)
    }

    private fun parseLyricsResponse(
        bytes: ByteArray,
        colorDataBytes: ByteArray?,
        hasVocalRemoval: Boolean
    ): ParsedColorLyrics? {
        val reader = ProtoReader(bytes)
        var syncType = SyncType.UNSYNCED
        val lines = mutableListOf<LyricLine>()
        var provider = "Spotify"
        var providerDisplayName = "Spotify"

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        syncType = when (reader.readVarint()) {
                            1L -> SyncType.LINE_SYNCED
                            2L -> SyncType.SYLLABLE_SYNCED
                            else -> SyncType.UNSYNCED
                        }
                    } else reader.skip(wireType)
                }
                2 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        val lineBytes = reader.readBytes()
                        parseLyricLine(lineBytes)?.let { lines.add(it) }
                    } else reader.skip(wireType)
                }
                3 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        provider = reader.readString()
                    } else reader.skip(wireType)
                }
                5 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        providerDisplayName = reader.readString()
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }

        if (lines.isEmpty()) return null
        return ParsedColorLyrics(
            lyrics = ParsedLyrics(syncType, lines),
            provider = provider,
            providerDisplayName = providerDisplayName,
            colorDataBytes = colorDataBytes,
            hasVocalRemoval = hasVocalRemoval
        )
    }

    private fun parseLyricLine(bytes: ByteArray): LyricLine? {
        val reader = ProtoReader(bytes)
        var startTimeMs = 0L
        var words = ""
        val syllables = mutableListOf<LyricSyllable>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        startTimeMs = reader.readVarint()
                    } else reader.skip(wireType)
                }
                2 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        words = reader.readString()
                    } else reader.skip(wireType)
                }
                3 -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        val sylBytes = reader.readBytes()
                        parseSyllable(sylBytes)?.let { syllables.add(it) }
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }
        return LyricLine(startTimeMs = startTimeMs, words = words, syllables = syllables)
    }

    private fun parseSyllable(bytes: ByteArray): LyricSyllable? {
        val reader = ProtoReader(bytes)
        var startTimeMs = 0L
        var numChars = 0
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        startTimeMs = reader.readVarint()
                    } else reader.skip(wireType)
                }
                2 -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        numChars = reader.readVarint().toInt()
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }
        return LyricSyllable(startTimeMs = startTimeMs, numChars = numChars)
    }

    private fun buildColorData(): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: background = -16777216 (0xFF000000)
        writeTag(out, 1, WIRE_TYPE_VARINT)
        writeVarint(out, -16777216L)

        // 2: text = -1 (0xFFFFFFFF)
        writeTag(out, 2, WIRE_TYPE_VARINT)
        writeVarint(out, -1L)

        // 3: highlightText = -16711936 (0xFF00FF00)
        writeTag(out, 3, WIRE_TYPE_VARINT)
        writeVarint(out, -16711936L)

        return out.toByteArray()
    }

    private fun buildLyricsResponse(
        lyrics: ParsedLyrics,
        providerName: String,
        providerDisplayName: String
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1: syncType (enum: 0=UNSYNCED, 1=LINE_SYNCED, 2=SYLLABLE_SYNCED)
        val syncTypeValue = when (lyrics.syncType) {
            SyncType.UNSYNCED -> 0L
            SyncType.LINE_SYNCED -> 1L
            SyncType.SYLLABLE_SYNCED -> 2L
        }
        writeTag(out, 1, WIRE_TYPE_VARINT)
        writeVarint(out, syncTypeValue)

        // 2: lines (repeated LyricsLine)
        for (line in lyrics.lines) {
            val lineBytes = buildLyricsLine(line)
            writeTag(out, 2, WIRE_TYPE_LENGTH_DELIMITED)
            writeVarint(out, lineBytes.size.toLong())
            out.write(lineBytes)
        }

        // 3: provider (string)
        writeString(out, 3, providerName)

        // 4: providerLyricsId (string)
        writeString(out, 4, "0")

        // 5: providerDisplayName (string)
        writeString(out, 5, providerDisplayName)

        // 7: syncLyricsUri (string)
        writeString(out, 7, "")

        // 10: language (string)
        writeString(out, 10, "en")

        // 11: isRtlLanguage (bool)
        writeTag(out, 11, WIRE_TYPE_VARINT)
        writeVarint(out, 0)

        // 14: capStatus (enum: 1 = UNCAPPED)
        writeTag(out, 14, WIRE_TYPE_VARINT)
        writeVarint(out, 1)

        return out.toByteArray()
    }

    private fun buildLyricsLine(line: LyricLine): ByteArray {
        val out = ByteArrayOutputStream()

        // 1: startTimeMs (int64)
        writeTag(out, 1, WIRE_TYPE_VARINT)
        writeVarint(out, line.startTimeMs)

        // 2: words (string)
        writeString(out, 2, line.words)

        // 3: syllables (repeated Syllable)
        for (syl in line.syllables) {
            val sylBytes = buildSyllable(syl)
            writeTag(out, 3, WIRE_TYPE_LENGTH_DELIMITED)
            writeVarint(out, sylBytes.size.toLong())
            out.write(sylBytes)
        }

        return out.toByteArray()
    }

    private fun buildSyllable(syl: LyricSyllable): ByteArray {
        val out = ByteArrayOutputStream()

        // 1: startTimeMs (int64)
        writeTag(out, 1, WIRE_TYPE_VARINT)
        writeVarint(out, syl.startTimeMs)

        // 2: numChars (int64)
        writeTag(out, 2, WIRE_TYPE_VARINT)
        writeVarint(out, syl.numChars.toLong())

        return out.toByteArray()
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            } else {
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }
    }

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private class ProtoReader(
        private val bytes: ByteArray,
        private var offset: Int = 0,
        private val limit: Int = bytes.size
    ) {
        fun hasRemaining(): Boolean = offset < limit

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (offset < limit && shift < 64) {
                val b = bytes[offset++].toInt()
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            return result
        }

        fun readTag(): Int {
            if (!hasRemaining()) return 0
            return readVarint().toInt()
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len <= 0) return ByteArray(0)
            val end = (offset + len).coerceAtMost(limit)
            val slice = bytes.copyOfRange(offset, end)
            offset = end
            return slice
        }

        fun readString(): String {
            val len = readVarint().toInt()
            if (len <= 0) return ""
            val end = (offset + len).coerceAtMost(limit)
            val s = String(bytes, offset, end - offset, Charsets.UTF_8)
            offset = end
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> offset = (offset + 8).coerceAtMost(limit)
                2 -> {
                    val len = readVarint().toInt()
                    if (len > 0) offset = (offset + len).coerceAtMost(limit)
                }
                5 -> offset = (offset + 4).coerceAtMost(limit)
                else -> {
                    offset = limit
                }
            }
        }
    }
}
