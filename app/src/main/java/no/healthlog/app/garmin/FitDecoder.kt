package no.healthlog.app.garmin

/**
 * Minimal leser for Garmins FIT-format (binærfilene i "Eksporter"-zippen fra Garmin
 * Connect). Leser bare enkeltverdi-felter som tall - nok til søvn, HRV, stress, Body
 * Battery og hvilepuls. Tekst, lister og utviklerfelter hoppes over.
 *
 * Formatet: 12/14-byte header, deretter poster. Hver post er enten en DEFINISJON (hvilke
 * felter med hvilke størrelser en "lokal meldingstype" har) eller DATA for en tidligere
 * definert lokal type. Se Garmin FIT SDK-dokumentasjonen for detaljer.
 */
data class FitMessage(val globalNumber: Int, val fields: Map<Int, Long>) {
    operator fun get(field: Int): Long? = fields[field]
}

class FitFormatException(message: String) : Exception(message)

/** Sekunder mellom FIT-epoken (1989-12-31T00:00:00Z) og Unix-epoken. */
const val FIT_EPOCH_OFFSET_SECONDS = 631_065_600L

/** Felt 253 er alltid tidsstempel i FIT. */
const val FIT_TIMESTAMP_FIELD = 253

private class FieldDef(val number: Int, val size: Int, val baseType: Int)
private class MessageDef(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devFieldsSize: Int)

fun decodeFit(bytes: ByteArray): List<FitMessage> {
    if (bytes.size < 12) throw FitFormatException("For kort til å være en FIT-fil")
    val headerSize = bytes[0].toInt() and 0xFF
    if (headerSize < 12 || bytes.size < headerSize) throw FitFormatException("Ugyldig FIT-header")
    if (String(bytes, 8, 4, Charsets.US_ASCII) != ".FIT") throw FitFormatException("Ikke en FIT-fil")
    val dataSize = readUInt(bytes, 4, 4, bigEndian = false).toInt()
    val end = minOf(bytes.size, headerSize + dataSize)

    val definitions = arrayOfNulls<MessageDef>(16)
    val messages = mutableListOf<FitMessage>()
    var lastTimestamp = 0L
    var pos = headerSize

    while (pos < end) {
        val header = bytes[pos].toInt() and 0xFF
        pos++

        if (header and 0x80 != 0) {
            // Komprimert tidsstempel: 5 bit tidsforskyvning i headeren, alltid data.
            val local = (header shr 5) and 0x03
            val offset = (header and 0x1F).toLong()
            lastTimestamp = if (offset >= (lastTimestamp and 0x1F)) {
                (lastTimestamp and 0x1F.inv().toLong()) + offset
            } else {
                (lastTimestamp and 0x1F.inv().toLong()) + offset + 0x20
            }
            val def = definitions[local] ?: throw FitFormatException("Data før definisjon")
            val (message, newPos) = readData(bytes, pos, def)
            pos = newPos
            val fields = message.toMutableMap()
            fields.putIfAbsent(FIT_TIMESTAMP_FIELD, lastTimestamp)
            messages += FitMessage(def.global, fields)
            continue
        }

        val local = header and 0x0F
        if (header and 0x40 != 0) {
            val hasDevFields = header and 0x20 != 0
            if (pos + 5 > end) throw FitFormatException("Avkortet definisjon")
            val bigEndian = bytes[pos + 1].toInt() == 1
            val global = readUInt(bytes, pos + 2, 2, bigEndian).toInt()
            val fieldCount = bytes[pos + 4].toInt() and 0xFF
            pos += 5
            val fields = (0 until fieldCount).map { i ->
                val p = pos + i * 3
                FieldDef(bytes[p].toInt() and 0xFF, bytes[p + 1].toInt() and 0xFF, bytes[p + 2].toInt() and 0xFF)
            }
            pos += fieldCount * 3
            var devSize = 0
            if (hasDevFields) {
                val devCount = bytes[pos].toInt() and 0xFF
                pos++
                for (i in 0 until devCount) devSize += bytes[pos + i * 3 + 1].toInt() and 0xFF
                pos += devCount * 3
            }
            definitions[local] = MessageDef(global, bigEndian, fields, devSize)
        } else {
            val def = definitions[local] ?: throw FitFormatException("Data før definisjon")
            val (message, newPos) = readData(bytes, pos, def)
            pos = newPos
            message[FIT_TIMESTAMP_FIELD]?.let { lastTimestamp = it }
            messages += FitMessage(def.global, message)
        }
    }
    return messages
}

private fun readData(bytes: ByteArray, start: Int, def: MessageDef): Pair<Map<Int, Long>, Int> {
    var pos = start
    val values = mutableMapOf<Int, Long>()
    for (field in def.fields) {
        if (pos + field.size > bytes.size) throw FitFormatException("Avkortet datapost")
        decodeValue(bytes, pos, field.size, field.baseType, def.bigEndian)?.let { values[field.number] = it }
        pos += field.size
    }
    return values to pos + def.devFieldsSize
}

/** Tallverdien, eller null for ugyldig verdi, tekst og lister (flere verdier i ett felt). */
private fun decodeValue(bytes: ByteArray, pos: Int, size: Int, baseType: Int, bigEndian: Boolean): Long? {
    val (typeSize, signed, invalid) = when (baseType) {
        0x00, 0x02, 0x0D -> Triple(1, false, 0xFFL)          // enum, uint8, byte
        0x0A -> Triple(1, false, 0L)                          // uint8z
        0x01 -> Triple(1, true, 0x7FL)                        // sint8
        0x84 -> Triple(2, false, 0xFFFFL)                     // uint16
        0x8B -> Triple(2, false, 0L)                          // uint16z
        0x83 -> Triple(2, true, 0x7FFFL)                      // sint16
        0x86 -> Triple(4, false, 0xFFFFFFFFL)                 // uint32
        0x8C -> Triple(4, false, 0L)                          // uint32z
        0x85 -> Triple(4, true, 0x7FFFFFFFL)                  // sint32
        else -> return null                                   // tekst, flyttall, 64-bit
    }
    if (size != typeSize) return null
    val raw = readUInt(bytes, pos, size, bigEndian)
    if (raw == invalid) return null
    if (!signed) return raw
    val bits = size * 8
    return if (raw and (1L shl (bits - 1)) != 0L) raw - (1L shl bits) else raw
}

private fun readUInt(bytes: ByteArray, pos: Int, size: Int, bigEndian: Boolean): Long {
    var value = 0L
    for (i in 0 until size) {
        val b = bytes[pos + if (bigEndian) i else size - 1 - i].toLong() and 0xFF
        value = (value shl 8) or b
    }
    return value
}
