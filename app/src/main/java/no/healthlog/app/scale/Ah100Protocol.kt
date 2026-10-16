package no.healthlog.app.scale

import java.time.LocalDateTime
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol for the Huawei Body Fat Scale AH100 (identical hardware is sold as CH100).
 *
 * Summary of how the scale talks:
 *  - GATT service 0xFAA0. We write commands to 0xFAA1 and receive notifications on 0xFAA2.
 *  - Every frame is `[start, length, opcode, payload…]`. The payload is always XOR-ed
 *    with the scale's own Bluetooth MAC address (repeating 6-byte key).
 *  - The user profile we send and the measurement the scale returns are additionally
 *    AES-128-CTR encrypted. The session key is the MAC-XOR-ed auth token (7 bytes)
 *    followed by a fixed 9-byte tail; the IV is fixed.
 *
 * Pure Kotlin, no Android dependencies, so it can be unit tested on the JVM.
 */
object Ah100Protocol {

    val SERVICE_UUID: UUID = uuid16(0xFAA0)
    val WRITE_UUID: UUID = uuid16(0xFAA1)
    val NOTIFY_UUID: UUID = uuid16(0xFAA2)
    val CCCD_UUID: UUID = uuid16(0x2902)

    /** Advertised names this protocol applies to. */
    val ADVERTISED_NAMES = setOf("AH100", "CH100")

    // Frame start bytes
    private const val START_PLAIN = 0xDB          // host -> scale, MAC-XOR only
    private const val START_ENCRYPTED = 0xDC      // host -> scale, AES + MAC-XOR
    const val START_NOTIFY_ENCRYPTED = 0xBC       // scale -> host, measurement halves

    /** Opcodes in notifications from the scale. */
    object Op {
        const val WAKEUP = 0x00
        const val AUTH_RESULT = 0x26
        const val BIND_OK = 0x27
        const val USER_CHANGED = 0x20
        const val MEASUREMENT = 0x0E
        const val MEASUREMENT_PART2 = 0x8E
        const val HISTORY = 0x10
        const val HISTORY_PART2 = 0x90
    }

    /** Opcodes for commands we send. */
    object Cmd {
        const val SET_UNIT = 0x02
        const val SET_CLOCK = 0x08
        const val USER_INFO = 0x09
        const val GET_VERSION = 0x0C
        const val RESULT_ACK = 0x13
        const val AUTH = 0x24
        const val BIND = 0x25
    }

    private val KEY_TAIL = hex("2A980FDE3456732156")               // 9 bytes
    private val IV = hex("4EF764322FDA7632123DEB8790FEA219")      // 16 bytes

    /** The user profile the scale needs to compute body fat. */
    data class Profile(
        val heightCm: Int,
        val age: Int,
        val male: Boolean,
        /** Scale user slot 1..10. HealthLog only uses one person, so 1 is fine. */
        val slot: Int = 1,
    )

    data class Reading(
        val slot: Int,
        val weightKg: Float,
        val fatPercent: Float?,
        val impedanceOhm: Int?,
        val measuredAt: LocalDateTime?,
    )

    // ---------------------------------------------------------------- session

    /** 7-byte token: 11 22 33 44 55, checksum making the whole array XOR to 0, user slot. */
    fun authToken(slot: Int): ByteArray {
        val t = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0, slot.toByte())
        t[5] = t.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
        return t
    }

    fun sessionKey(token: ByteArray, mac: ByteArray): ByteArray = xorMac(token, mac) + KEY_TAIL

    // ---------------------------------------------------------------- outgoing frames

    fun plainFrame(cmd: Int, payload: ByteArray, mac: ByteArray): ByteArray =
        byteArrayOf(START_PLAIN.toByte(), (payload.size + 1).toByte(), cmd.toByte()) + xorMac(payload, mac)

    fun authFrame(token: ByteArray, mac: ByteArray) = plainFrame(Cmd.AUTH, token, mac)
    fun bindFrame(token: ByteArray, mac: ByteArray) = plainFrame(Cmd.BIND, token, mac)
    fun unitKgFrame(mac: ByteArray) = plainFrame(Cmd.SET_UNIT, byteArrayOf(0x01), mac)
    fun versionFrame(mac: ByteArray) = plainFrame(Cmd.GET_VERSION, ByteArray(0), mac)
    fun resultAckFrame(mac: ByteArray) = plainFrame(Cmd.RESULT_ACK, byteArrayOf(0x00), mac)

    fun clockFrame(now: LocalDateTime, mac: ByteArray): ByteArray {
        val y = now.year
        val payload = byteArrayOf(
            (y and 0xFF).toByte(), (y shr 8).toByte(),
            now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
            now.dayOfWeek.value.toByte(), // 1 = Monday … 7 = Sunday
        )
        return plainFrame(Cmd.SET_CLOCK, payload, mac)
    }

    /**
     * Encrypted user profile. Plaintext is 16 bytes:
     * token(7) · age|sexBit · height · 0 · weight dkg LE(2) · FF FF · 1C E2.
     * The length byte says 14: the 2-byte trailer is sent but not counted.
     */
    fun userInfoFrame(token: ByteArray, profile: Profile, weightKg: Float, key: ByteArray, mac: ByteArray): ByteArray {
        val sexBit = if (profile.male) 0x00 else 0x80
        val w = (weightKg * 10).toInt().coerceIn(1, 0xFFFF)
        val plain = token + byteArrayOf(
            ((profile.age and 0x7F) or sexBit).toByte(),
            profile.heightCm.toByte(),
            0,
            (w and 0xFF).toByte(), (w shr 8).toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0x1C, 0xE2.toByte(),
        )
        val body = xorMac(aesCtr(plain, key), mac)
        return byteArrayOf(START_ENCRYPTED.toByte(), (plain.size - 2).toByte(), Cmd.USER_INFO.toByte()) + body
    }

    // ---------------------------------------------------------------- incoming frames

    class Notification(val start: Int, val op: Int, val body: ByteArray)

    /** Splits a notification and removes the MAC XOR from its body. Null if too short. */
    fun parse(frame: ByteArray, mac: ByteArray): Notification? {
        if (frame.size < 3) return null
        return Notification(
            start = frame[0].toInt() and 0xFF,
            op = frame[2].toInt() and 0xFF,
            body = xorMac(frame.copyOfRange(3, frame.size), mac),
        )
    }

    /**
     * Decrypts and decodes a measurement body (already MAC-unXOR-ed). Needs ≥ 15 bytes.
     * Plaintext layout: slot · weight dkg LE(2) · fat ‰ LE(2) · year LE(2) · month · day ·
     * hour · minute · second · weekday · impedance Ω LE(2).
     */
    fun decodeMeasurement(body: ByteArray, key: ByteArray): Reading {
        require(body.size >= 15) { "Measurement body too short: ${body.size}" }
        val p = aesCtr(body, key)
        fun u8(i: Int) = p[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

        val fat = u16(3)
        val imp = u16(13)
        val time = runCatching { LocalDateTime.of(u16(5), u8(7), u8(8), u8(9), u8(10), u8(11)) }.getOrNull()
        return Reading(
            slot = u8(0),
            weightKg = u16(1) / 10f,
            fatPercent = if (fat in 1..999) fat / 10f else null,   // 0 = no barefoot contact
            impedanceOhm = if (imp in 1..3999) imp else null,
            measuredAt = time,
        )
    }

    // ---------------------------------------------------------------- helpers

    fun macToBytes(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "Bad MAC: $mac" }
        return hex(hex)
    }

    fun xorMac(data: ByteArray, mac: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor mac[i % mac.size].toInt()).toByte() }

    fun aesCtr(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
            doFinal(data)
        }

    fun matchesName(name: String?): Boolean {
        if (name == null) return false
        val n = name.replace("\u0000", "").trim().uppercase()
        return ADVERTISED_NAMES.any { n == it || n.startsWith("$it-") || n.startsWith("$it ") || n.endsWith(" $it") }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun uuid16(v: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
}
