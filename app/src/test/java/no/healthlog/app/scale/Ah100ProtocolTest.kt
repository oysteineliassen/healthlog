package no.healthlog.app.scale

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class Ah100ProtocolTest {

    private val mac = Ah100Protocol.macToBytes("5C:CA:D3:14:2B:B3")
    private val token = Ah100Protocol.authToken(1)
    private val key = Ah100Protocol.sessionKey(token, mac)

    @Test
    fun authTokenXorsToZero() {
        // Egen variabel: inne i assertEquals gjettet Kotlin Long for fold(0) og feilet på Int.
        val checksum: Int = token.fold(0) { a, b -> a xor (b.toInt() and 0xFF) }
        assertEquals(0, checksum)
        assertEquals(7, token.size)
        assertEquals(16, key.size)
    }

    /** Frames logged from a real CH100 (same hardware) whose display showed 126.6 kg / 33.8 %. */
    @Test
    fun decodesRealHardwareFrames() {
        val r1 = decode("bc110eabf78678303ba462fc93d1db4b348861")
        assertEquals(126.6f, r1.weightKg, 0.001f)
        assertEquals(33.8f, r1.fatPercent!!, 0.001f)
        assertEquals(306, r1.impedanceOhm)
        assertEquals(1, r1.slot)
        assertEquals(LocalDateTime.of(2026, 8, 10, 10, 1, 38), r1.measuredAt)

        val r2 = decode("bc110eabf48679303ba462fc93dce84b3f8861")
        assertEquals(126.5f, r2.weightKg, 0.001f)
        assertEquals(33.9f, r2.fatPercent!!, 0.001f)
        assertEquals(313, r2.impedanceOhm)
    }

    @Test
    fun userInfoFrameRoundTrips() {
        val profile = Ah100Protocol.Profile(heightCm = 180, age = 40, male = true)
        val frame = Ah100Protocol.userInfoFrame(token, profile, 85.3f, key, mac)
        assertEquals(0xDC, frame[0].toInt() and 0xFF)
        assertEquals(14, frame[1].toInt())
        assertEquals(0x09, frame[2].toInt())
        val plain = Ah100Protocol.aesCtr(Ah100Protocol.xorMac(frame.copyOfRange(3, frame.size), mac), key)
        assertArrayEquals(token, plain.copyOfRange(0, 7))
        assertEquals(40, plain[7].toInt())
        assertEquals(180, plain[8].toInt() and 0xFF)
        assertEquals(853, (plain[10].toInt() and 0xFF) or ((plain[11].toInt() and 0xFF) shl 8))
    }

    @Test
    fun plainFrameHasLengthPlusOne() {
        val f = Ah100Protocol.authFrame(token, mac)
        assertEquals(0xDB, f[0].toInt() and 0xFF)
        assertEquals(8, f[1].toInt())
        assertEquals(0x24, f[2].toInt())
        assertArrayEquals(token, Ah100Protocol.xorMac(f.copyOfRange(3, f.size), mac))
    }

    @Test
    fun matchesAdvertNames() {
        assert(Ah100Protocol.matchesName("AH100"))
        assert(Ah100Protocol.matchesName("HUAWEI AH100"))
        assert(Ah100Protocol.matchesName("CH100\u0000"))
        assert(!Ah100Protocol.matchesName("CH100S"))
        assert(!Ah100Protocol.matchesName(null))
    }

    private fun decode(hexFrame: String): Ah100Protocol.Reading {
        val bytes = ByteArray(hexFrame.length / 2) { hexFrame.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val n = Ah100Protocol.parse(bytes, mac)!!
        assertEquals(Ah100Protocol.Op.MEASUREMENT, n.op)
        return Ah100Protocol.decodeMeasurement(n.body, key)
    }
}
