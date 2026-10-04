package pl.hamlogbridge.wsjtx

/**
 * Decoder for WSJT-X UDP datagrams.
 *
 * Every datagram starts with:
 *   quint32 magic  = 0xADBCCBDA
 *   quint32 schema = 2 or 3 (we accept anything, fields are append-only)
 *   quint32 type
 *   utf8    id     (the sending program's "unique key", e.g. "WSJT-X" or "X6100")
 *
 * Fields were only ever appended across schema versions, so a tolerant reader
 * that stops when the buffer runs out works for every producer - including
 * partial implementations like the X6100 alternative firmware, which sends
 * short Status/Decode packets.
 */
object WsjtxCodec {

    const val MAGIC = 0xADBCCBDAL

    const val TYPE_HEARTBEAT = 0L
    const val TYPE_STATUS = 1L
    const val TYPE_DECODE = 2L
    const val TYPE_CLEAR = 3L
    const val TYPE_REPLY = 4L
    const val TYPE_QSO_LOGGED = 5L
    const val TYPE_CLOSE = 6L
    const val TYPE_REPLAY = 7L
    const val TYPE_HALT_TX = 8L
    const val TYPE_FREE_TEXT = 9L
    const val TYPE_WSPR_DECODE = 10L
    const val TYPE_LOCATION = 11L
    const val TYPE_LOGGED_ADIF = 12L

    data class Envelope(val magic: Long, val schema: Long, val type: Long, val message: WsjtxMessage)

    fun looksLikeWsjtx(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length < 8) return false
        val m = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
        return m == MAGIC
    }

    /** Returns null when the datagram is not a WSJT-X message. Throws on truncation. */
    fun decode(bytes: ByteArray, length: Int = bytes.size): Envelope? {
        val r = QDataReader(bytes, length)
        val magic = r.u32()
        if (magic != MAGIC) return null
        val schema = r.u32()
        val type = r.u32()
        val id = r.utf8() ?: ""

        val msg: WsjtxMessage = when (type) {
            TYPE_HEARTBEAT -> WsjtxMessage.Heartbeat(
                id = id,
                maxSchema = if (r.hasMore) r.u32() else 0,
                version = if (r.hasMore) r.utf8() else null,
                revision = if (r.hasMore) r.utf8() else null
            )

            TYPE_STATUS -> WsjtxMessage.Status(
                id = id,
                dialFrequencyHz = if (r.hasMore) r.u64() else 0,
                mode = if (r.hasMore) r.utf8() else null,
                dxCall = if (r.hasMore) r.utf8() else null,
                report = if (r.hasMore) r.utf8() else null,
                txMode = if (r.hasMore) r.utf8() else null,
                txEnabled = if (r.hasMore) r.bool() else false,
                transmitting = if (r.hasMore) r.bool() else false,
                decoding = if (r.hasMore) r.bool() else false,
                rxDf = if (r.hasMore) r.u32() else 0,
                txDf = if (r.hasMore) r.u32() else 0,
                deCall = if (r.hasMore) r.utf8() else null,
                deGrid = if (r.hasMore) r.utf8() else null,
                dxGrid = if (r.hasMore) r.utf8() else null,
                txWatchdog = if (r.hasMore) r.bool() else false,
                subMode = if (r.hasMore) r.utf8() else null,
                fastMode = if (r.hasMore) r.bool() else false,
                specialOpMode = if (r.hasMore) r.u8() else 0,
                frequencyTolerance = if (r.hasMore) r.u32() else 0,
                trPeriodSec = if (r.hasMore) r.u32() else 0,
                configurationName = if (r.hasMore) r.utf8() else null,
                txMessage = if (r.hasMore) r.utf8() else null
            )

            TYPE_DECODE -> WsjtxMessage.Decode(
                id = id,
                isNew = if (r.hasMore) r.bool() else true,
                timeMsSinceMidnight = if (r.hasMore) r.timeMs() else 0,
                snrDb = if (r.hasMore) r.i32() else 0,
                deltaTimeSec = if (r.hasMore) r.f64() else 0.0,
                deltaFrequencyHz = if (r.hasMore) r.u32() else 0,
                mode = if (r.hasMore) r.utf8() else null,
                message = if (r.hasMore) r.utf8() else null,
                lowConfidence = if (r.hasMore) r.bool() else false,
                offAir = if (r.hasMore) r.bool() else false
            )

            TYPE_CLEAR -> WsjtxMessage.Clear(id)

            TYPE_QSO_LOGGED -> WsjtxMessage.QsoLogged(
                id = id,
                timeOff = if (r.hasMore) r.dateTime() else null,
                dxCall = if (r.hasMore) r.utf8() else null,
                dxGrid = if (r.hasMore) r.utf8() else null,
                txFrequencyHz = if (r.hasMore) r.u64() else 0,
                mode = if (r.hasMore) r.utf8() else null,
                reportSent = if (r.hasMore) r.utf8() else null,
                reportReceived = if (r.hasMore) r.utf8() else null,
                txPower = if (r.hasMore) r.utf8() else null,
                comments = if (r.hasMore) r.utf8() else null,
                name = if (r.hasMore) r.utf8() else null,
                timeOn = if (r.hasMore) r.dateTime() else null,
                operatorCall = if (r.hasMore) r.utf8() else null,
                myCall = if (r.hasMore) r.utf8() else null,
                myGrid = if (r.hasMore) r.utf8() else null,
                exchangeSent = if (r.hasMore) r.utf8() else null,
                exchangeReceived = if (r.hasMore) r.utf8() else null,
                propMode = if (r.hasMore) r.utf8() else null
            )

            TYPE_CLOSE -> WsjtxMessage.Close(id)

            TYPE_WSPR_DECODE -> WsjtxMessage.WsprDecode(
                id = id,
                isNew = if (r.hasMore) r.bool() else true,
                timeMsSinceMidnight = if (r.hasMore) r.timeMs() else 0,
                snrDb = if (r.hasMore) r.i32() else 0,
                deltaTimeSec = if (r.hasMore) r.f64() else 0.0,
                frequencyHz = if (r.hasMore) r.u64() else 0,
                drift = if (r.hasMore) r.i32() else 0,
                callsign = if (r.hasMore) r.utf8() else null,
                grid = if (r.hasMore) r.utf8() else null,
                power = if (r.hasMore) r.i32() else 0,
                offAir = if (r.hasMore) r.bool() else false
            )

            TYPE_LOGGED_ADIF -> WsjtxMessage.LoggedAdif(
                id = id,
                adif = if (r.hasMore) r.utf8() else null
            )

            else -> WsjtxMessage.Other(id, type)
        }
        return Envelope(magic, schema, type, msg)
    }

    fun typeName(type: Long): String = when (type) {
        TYPE_HEARTBEAT -> "Heartbeat"
        TYPE_STATUS -> "Status"
        TYPE_DECODE -> "Decode"
        TYPE_CLEAR -> "Clear"
        TYPE_REPLY -> "Reply"
        TYPE_QSO_LOGGED -> "QSO Logged"
        TYPE_CLOSE -> "Close"
        TYPE_REPLAY -> "Replay"
        TYPE_HALT_TX -> "Halt Tx"
        TYPE_FREE_TEXT -> "Free Text"
        TYPE_WSPR_DECODE -> "WSPR Decode"
        TYPE_LOCATION -> "Location"
        TYPE_LOGGED_ADIF -> "Logged ADIF"
        13L -> "Highlight Callsign"
        14L -> "Switch Configuration"
        15L -> "Configure"
        else -> "Type $type"
    }
}
