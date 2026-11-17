package app.spotxero.hooks.lyrics

import java.io.ByteArrayOutputStream

object LyricsProtobuf {

    /**
     * Serializes lyrics into Spotify's ColorLyricsResponse protobuf wire format.
     * Schema matching com.spotify.lyrics.serviceretrofit.proto.ColorLyricsResponse:
     * - Field 1: LyricsResponse lyrics (length-delimited)
     * - Field 2: ColorData colors (length-delimited)
     * - Field 3: bool hasVocalRemoval (varint)
     */
    fun buildColorLyricsResponse(lyrics: ParsedLyrics, providerName: String): ByteArray {
        val out = ByteArrayOutputStream()

        // Field 1: LyricsResponse
        val lyricsResponseBytes = buildLyricsResponse(lyrics, providerName)
        writeTag(out, 1, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, lyricsResponseBytes.size.toLong())
        out.write(lyricsResponseBytes)

        // Field 2: ColorData
        val colorDataBytes = buildColorData()
        writeTag(out, 2, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, colorDataBytes.size.toLong())
        out.write(colorDataBytes)

        // Field 3: hasVocalRemoval = false
        writeTag(out, 3, WIRE_TYPE_VARINT)
        writeVarint(out, 0)

        return out.toByteArray()
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

    private fun buildLyricsResponse(lyrics: ParsedLyrics, providerName: String): ByteArray {
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
        writeString(out, 5, "$providerName (SpotXero)")

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

    private const val WIRE_TYPE_VARINT = 0
    private const val WIRE_TYPE_LENGTH_DELIMITED = 2

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
}
