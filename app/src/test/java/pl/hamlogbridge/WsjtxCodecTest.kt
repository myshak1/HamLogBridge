package pl.hamlogbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.hamlogbridge.wsjtx.WsjtxCodec
import pl.hamlogbridge.wsjtx.WsjtxMessage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Builds real QDataStream payloads and checks the decoder round-trips them. */
class WsjtxCodecTest {

    private fun packet(type: Long, id: String, body: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeInt(0xADBCCBDA.toInt())
        out.writeInt(2)
        out.writeInt(type.toInt())
        out.writeUtf8(id)
        out.body()
        return bos.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(s: String?) {
        if (s == null) { writeInt(-1); return }
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    @Test
    fun decodesADecodeMessage() {
        val bytes = packet(2, "X6100") {
            writeByte(1)                 // new
            writeInt(43_200_000)         // 12:00:00 UTC
            writeInt(-14)                // snr
            writeDouble(0.2)             // dt
            writeInt(1337)               // df
            writeUtf8("FT8")
            writeUtf8("CQ SP1ABC JO90")
            writeByte(0)
            writeByte(0)
        }
        val env = WsjtxCodec.decode(bytes)!!
        val m = env.message as WsjtxMessage.Decode
        assertEquals("X6100", m.id)
        assertEquals(-14, m.snrDb)
        assertEquals(1337L, m.deltaFrequencyHz)
        assertEquals("CQ SP1ABC JO90", m.message)
    }

    @Test
    fun toleratesTruncatedStatusFromPartialImplementations() {
        // A firmware that only fills in the first few Status fields.
        val bytes = packet(1, "X6100") {
            writeLong(14_074_000L)
            writeUtf8("FT8")
        }
        val m = WsjtxCodec.decode(bytes)!!.message as WsjtxMessage.Status
        assertEquals(14_074_000L, m.dialFrequencyHz)
        assertEquals("FT8", m.mode)
        assertEquals("", m.deCall ?: "")
    }

    @Test
    fun rejectsForeignDatagrams() {
        assertEquals(null, WsjtxCodec.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertTrue(!WsjtxCodec.looksLikeWsjtx(byteArrayOf(0, 0, 0, 0)))
    }
}
