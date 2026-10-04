package pl.hamlogbridge.wsjtx

import java.time.Instant

/**
 * Typed view of the WSJT-X UDP "Message Protocol" (NetworkMessage.hpp).
 * Only the inbound messages matter for a logging bridge; outbound ones
 * (Reply, Halt Tx, ...) are relayed verbatim instead of being re-encoded.
 */
sealed class WsjtxMessage {
    abstract val id: String

    data class Heartbeat(
        override val id: String,
        val maxSchema: Long,
        val version: String?,
        val revision: String?
    ) : WsjtxMessage()

    data class Status(
        override val id: String,
        val dialFrequencyHz: Long,
        val mode: String?,
        val dxCall: String?,
        val report: String?,
        val txMode: String?,
        val txEnabled: Boolean,
        val transmitting: Boolean,
        val decoding: Boolean,
        val rxDf: Long,
        val txDf: Long,
        val deCall: String?,
        val deGrid: String?,
        val dxGrid: String?,
        val txWatchdog: Boolean,
        val subMode: String?,
        val fastMode: Boolean,
        val specialOpMode: Int,
        val frequencyTolerance: Long,
        val trPeriodSec: Long,
        val configurationName: String?,
        val txMessage: String?
    ) : WsjtxMessage()

    data class Decode(
        override val id: String,
        val isNew: Boolean,
        val timeMsSinceMidnight: Long,
        val snrDb: Int,
        val deltaTimeSec: Double,
        val deltaFrequencyHz: Long,
        val mode: String?,
        val message: String?,
        val lowConfidence: Boolean,
        val offAir: Boolean
    ) : WsjtxMessage()

    data class Clear(override val id: String) : WsjtxMessage()

    data class QsoLogged(
        override val id: String,
        val timeOff: Instant?,
        val dxCall: String?,
        val dxGrid: String?,
        val txFrequencyHz: Long,
        val mode: String?,
        val reportSent: String?,
        val reportReceived: String?,
        val txPower: String?,
        val comments: String?,
        val name: String?,
        val timeOn: Instant?,
        val operatorCall: String?,
        val myCall: String?,
        val myGrid: String?,
        val exchangeSent: String?,
        val exchangeReceived: String?,
        val propMode: String?
    ) : WsjtxMessage()

    data class Close(override val id: String) : WsjtxMessage()

    data class WsprDecode(
        override val id: String,
        val isNew: Boolean,
        val timeMsSinceMidnight: Long,
        val snrDb: Int,
        val deltaTimeSec: Double,
        val frequencyHz: Long,
        val drift: Int,
        val callsign: String?,
        val grid: String?,
        val power: Int,
        val offAir: Boolean
    ) : WsjtxMessage()

    /** Type 12 - the full ADIF record WSJT-X wrote to its own log. */
    data class LoggedAdif(
        override val id: String,
        val adif: String?
    ) : WsjtxMessage()

    data class Other(override val id: String, val type: Long) : WsjtxMessage()
}
