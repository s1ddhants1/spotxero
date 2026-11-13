package app.spotxero.hooks.attestation

import java.io.ByteArrayOutputStream

object ClientTokenProto {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private const val FIELD_REQUEST_TYPE = 1
    private const val FIELD_CLIENT_DATA = 2
    private const val FIELD_CONNECTIVITY_SDK_DATA = 3
    private const val FIELD_DEVICE_ID = 2

    private class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray)

    private fun parseFields(body: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var pos = 0
        while (pos < body.size) {
            val tag = readVarint(body, pos) ?: return fields
            pos = tag.second
            val number = (tag.first shr 3).toInt()
            when ((tag.first and 0x7L).toInt()) {
                WIRE_VARINT -> {
                    val value = readVarint(body, pos) ?: return fields
                    pos = value.second
                    fields.add(Field(number, WIRE_VARINT, value.first, ByteArray(0)))
                }
                WIRE_FIXED64 -> {
                    if (pos + 8 > body.size) return fields
                    fields.add(Field(number, WIRE_FIXED64, 0, body.copyOfRange(pos, pos + 8)))
                    pos += 8
                }
                WIRE_LEN -> {
                    val length = readVarint(body, pos) ?: return fields
                    pos = length.second
                    if (length.first + pos > body.size) return fields
                    fields.add(Field(number, WIRE_LEN, 0, body.copyOfRange(pos, pos + length.first.toInt())))
                    pos += length.first.toInt()
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > body.size) return fields
                    fields.add(Field(number, WIRE_FIXED32, 0, body.copyOfRange(pos, pos + 4)))
                    pos += 4
                }
                else -> return fields
            }
        }
        return fields
    }

    private fun readVarint(body: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var pos = start
        while (pos < body.size) {
            val byte = body[pos].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            pos++
            if (byte and 0x80 == 0) return value to pos
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    fun requestType(body: ByteArray): Int? =
        parseFields(body).firstOrNull { it.number == FIELD_REQUEST_TYPE && it.wireType == WIRE_VARINT }?.varint?.toInt()

    fun extractDeviceId(body: ByteArray): String? {
        val clientData = parseFields(body).firstOrNull { it.number == FIELD_CLIENT_DATA && it.wireType == WIRE_LEN }?.bytes
            ?: return null
        val connectivity = parseFields(clientData)
            .firstOrNull { it.number == FIELD_CONNECTIVITY_SDK_DATA && it.wireType == WIRE_LEN }?.bytes
            ?: return null
        return parseFields(connectivity)
            .firstOrNull { it.number == FIELD_DEVICE_ID && it.wireType == WIRE_LEN }
            ?.bytes?.let { String(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotEmpty() }
    }

    fun buildWebJsonRequest(
        deviceId: String,
        clientId: String,
        clientVersion: String
    ): ByteArray {
        val json = """{"client_data":{"client_version":"$clientVersion","client_id":"$clientId","js_sdk_data":{"device_brand":"unknown","device_model":"unknown","os":"android","os_version":"14","device_id":"$deviceId","device_type":"smartphone"}}}"""
        return json.toByteArray(Charsets.UTF_8)
    }

    fun buildGrantedTokenResponse(
        token: String,
        expiresAfterSeconds: Int = 1216800,
        refreshAfterSeconds: Int = 1209600,
        domains: List<String> = listOf("spotify.com", "spotify.net")
    ): ByteArray {
        val granted = encode { out ->
            writeString(out, 1, token)
            writeVarint(out, tag(2, WIRE_VARINT))
            writeVarint(out, expiresAfterSeconds.toLong())
            writeVarint(out, tag(3, WIRE_VARINT))
            writeVarint(out, refreshAfterSeconds.toLong())
            for (domain in domains) {
                val d = encode { writeString(it, 1, domain) }
                writeMessage(out, 4, d)
            }
        }
        return encode { out ->
            writeVarint(out, tag(1, WIRE_VARINT))
            writeVarint(out, 1L) // RESPONSE_GRANTED_TOKEN_RESPONSE = 1
            writeMessage(out, 2, granted)
        }
    }

    private fun encode(builder: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        builder(out)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): Long = ((field shl 3) or wire).toLong()

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
        val payload = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, tag(field, WIRE_LEN))
        writeVarint(out, payload.size.toLong())
        out.write(payload)
    }

    private fun writeMessage(out: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        writeVarint(out, tag(field, WIRE_LEN))
        writeVarint(out, payload.size.toLong())
        out.write(payload)
    }
}
