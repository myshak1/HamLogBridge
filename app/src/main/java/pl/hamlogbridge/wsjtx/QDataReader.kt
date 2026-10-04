package pl.hamlogbridge.wsjtx

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reader for Qt's QDataStream wire format, which is what WSJT-X (and every
 * clone of its UDP protocol) uses. Everything is big-endian.
 *
 *  - quint8/quint32/quint64  : plain big-endian integers
 *  - bool                    : one byte, 0 = false
 *  - QString (utf8)          : qint32 byte length, then raw UTF-8.
 *                              Length 0xFFFFFFFF means "null string".
 *  - QTime                   : quint32 milliseconds since midnight
 *  - QDateTime               : qint64 Julian day, quint32 ms since midnight,
 *                              quint8 timespec (0 local, 1 UTC, 2 offset,
 *                              3 named zone); spec 2 adds qint32 offset seconds.
 */
class QDataReader(bytes: ByteArray, length: Int = bytes.size) {

    private val bb: ByteBuffer =
        ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)

    class Malformed(message: String) : Exception(message)

    val remaining: Int get() = bb.remaining()
    val hasMore: Boolean get() = bb.remaining() > 0

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: BufferUnderflowException) {
        throw Malformed("truncated while reading $what")
    }

    fun u8(): Int = guard("u8") { bb.get().toInt() and 0xFF }
    fun bool(): Boolean = u8() != 0
    fun i32(): Int = guard("i32") { bb.int }
    fun u32(): Long = guard("u32") { bb.int.toLong() and 0xFFFFFFFFL }
    fun i64(): Long = guard("i64") { bb.long }
    fun u64(): Long = guard("u64") { bb.long }
    fun f64(): Double = guard("f64") { bb.double }

    /** Qt utf8 string; returns null for the null-string marker. */
    fun utf8(): String? = guard("utf8") {
        if (bb.remaining() < 4) return@guard null
        val len = bb.int
        if (len == -1) return@guard null          // 0xFFFFFFFF
        if (len < 0 || len > bb.remaining()) throw Malformed("bad string length $len")
        val a = ByteArray(len)
        bb.get(a)
        String(a, StandardCharsets.UTF_8)
    }

    /** Same as [utf8] but never null - easier for optional trailing fields. */
    fun utf8OrEmpty(): String = utf8() ?: ""

    /** QTime -> milliseconds since midnight (UTC in WSJT-X decodes). */
    fun timeMs(): Long = u32()

    fun dateTime(): Instant? = guard("QDateTime") {
        val julianDay = bb.long
        val msSinceMidnight = bb.int.toLong() and 0xFFFFFFFFL
        val spec = bb.get().toInt() and 0xFF
        var offsetSeconds = 0
        var zoneId: ZoneId? = null
        when (spec) {
            2 -> offsetSeconds = bb.int
            3 -> {
                val name = utf8()
                zoneId = runCatching { ZoneId.of(name!!) }.getOrNull()
            }
        }
        if (julianDay <= 0L) return@guard null
        // Julian day 2440588 == 1970-01-01
        val epochDay = julianDay - 2_440_588L
        val local = LocalDate.ofEpochDay(epochDay).atStartOfDay()
            .plusNanos(msSinceMidnight * 1_000_000L)
        when (spec) {
            0 -> local.atZone(ZoneId.systemDefault()).toInstant()      // local time
            2 -> local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds))
            3 -> local.atZone(zoneId ?: ZoneOffset.UTC).toInstant()
            else -> local.toInstant(ZoneOffset.UTC)                    // 1 = UTC
        }
    }
}
