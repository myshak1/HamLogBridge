package pl.hamlogbridge.adif

import pl.hamlogbridge.wsjtx.WsjtxMessage
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** ADIF 3 helpers: build records from WSJT-X messages, parse them back for display. */
object Adif {

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)

    /** ADIF field lengths are byte counts, not character counts. */
    fun field(name: String, value: String?): String {
        if (value.isNullOrBlank()) return ""
        val v = value.trim()
        val len = v.toByteArray(StandardCharsets.UTF_8).size
        return "<${name.uppercase()}:$len>$v "
    }

    fun header(programVersion: String): String = buildString {
        append("ADIF export from HamLog Bridge\n")
        append(field("ADIF_VER", "3.1.4"))
        append(field("PROGRAMID", "HamLogBridge"))
        append(field("PROGRAMVERSION", programVersion))
        append("<EOH>\n")
    }

    data class StationDefaults(
        val myCall: String = "",
        val myGrid: String = "",
        val operator: String = "",
        val txPowerW: String = "",
        val rigName: String = "",
        val antenna: String = "",
        val myCountry: String = "",
        val comment: String = ""
    )

    /** Builds a single ADIF record (no header, ends with <EOR>). */
    fun fromQsoLogged(m: WsjtxMessage.QsoLogged, d: StationDefaults): String {
        val on = m.timeOn ?: m.timeOff ?: Instant.now()
        val off = m.timeOff ?: on
        val freqHz = m.txFrequencyHz
        val freqMhz = if (freqHz > 0) String.format("%.6f", freqHz / 1_000_000.0) else null
        val rawMode = (m.mode ?: "").trim().uppercase()
        val (mode, subMode) = normaliseMode(rawMode)

        val sb = StringBuilder()
        sb.append(field("CALL", m.dxCall))
        sb.append(field("GRIDSQUARE", m.dxGrid))
        sb.append(field("MODE", mode))
        sb.append(field("SUBMODE", subMode))
        sb.append(field("FREQ", freqMhz))
        sb.append(field("BAND", bandFor(freqHz)))
        sb.append(field("QSO_DATE", DATE.format(on)))
        sb.append(field("TIME_ON", TIME.format(on)))
        sb.append(field("QSO_DATE_OFF", DATE.format(off)))
        sb.append(field("TIME_OFF", TIME.format(off)))
        sb.append(field("RST_SENT", m.reportSent))
        sb.append(field("RST_RCVD", m.reportReceived))
        sb.append(field("TX_PWR", m.txPower?.takeIf { it.isNotBlank() } ?: d.txPowerW))
        sb.append(field("NAME", m.name))
        sb.append(field("COMMENT", m.comments?.takeIf { it.isNotBlank() } ?: d.comment))
        sb.append(field("STATION_CALLSIGN", m.myCall?.takeIf { it.isNotBlank() } ?: d.myCall))
        sb.append(
            field(
                "OPERATOR",
                m.operatorCall?.takeIf { it.isNotBlank() }
                    ?: d.operator.takeIf { it.isNotBlank() }
                    ?: d.myCall
            )
        )
        sb.append(field("MY_GRIDSQUARE", m.myGrid?.takeIf { it.isNotBlank() } ?: d.myGrid))
        sb.append(field("SRX_STRING", m.exchangeReceived))
        sb.append(field("STX_STRING", m.exchangeSent))
        sb.append(field("PROP_MODE", m.propMode))
        sb.append(field("MY_RIG", d.rigName))
        sb.append(field("MY_ANTENNA", d.antenna))
        sb.append(field("MY_COUNTRY", d.myCountry))
        sb.append("<EOR>\n")
        return sb.toString()
    }

    /** FT8/FT4/JT9... are ADIF sub-modes of MFSK/JT9 etc. Keeps loggers happy. */
    fun normaliseMode(raw: String): Pair<String, String?> = when (raw) {
        "FT8" -> "FT8" to null            // FT8 is a first-class ADIF mode
        "FT4" -> "MFSK" to "FT4"
        "JS8" -> "MFSK" to "JS8"
        "Q65" -> "MFSK" to "Q65"
        "JT65", "JT9", "JT4", "FST4", "MSK144", "WSPR", "" -> (raw.ifBlank { "FT8" }) to null
        "FST4W" -> "FST4W" to null
        else -> raw to null
    }

    private data class Band(val name: String, val lowMhz: Double, val highMhz: Double)

    private val BANDS = listOf(
        Band("2190m", 0.1357, 0.1378), Band("630m", 0.472, 0.479),
        Band("160m", 1.8, 2.0), Band("80m", 3.5, 4.0), Band("60m", 5.06, 5.45),
        Band("40m", 7.0, 7.3), Band("30m", 10.1, 10.15), Band("20m", 14.0, 14.35),
        Band("17m", 18.068, 18.168), Band("15m", 21.0, 21.45),
        Band("12m", 24.89, 24.99), Band("10m", 28.0, 29.7), Band("6m", 50.0, 54.0),
        Band("4m", 70.0, 71.0), Band("2m", 144.0, 148.0), Band("1.25m", 222.0, 225.0),
        Band("70cm", 420.0, 450.0), Band("33cm", 902.0, 928.0), Band("23cm", 1240.0, 1300.0)
    )

    fun bandFor(freqHz: Long): String? {
        if (freqHz <= 0) return null
        val mhz = freqHz / 1_000_000.0
        return BANDS.firstOrNull { mhz >= it.lowMhz && mhz <= it.highMhz }?.name
    }

    /** Parses the first record of an ADIF blob into an upper-case field map. */
    fun parseFields(adif: String): Map<String, String> {
        val eoh = adif.indexOf("<eoh>", ignoreCase = true)
        val body = if (eoh >= 0) adif.substring(eoh + 5) else adif
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            val lt = body.indexOf('<', i)
            if (lt < 0) break
            val gt = body.indexOf('>', lt)
            if (gt < 0) break
            val spec = body.substring(lt + 1, gt)
            if (spec.equals("EOR", true)) break
            val parts = spec.split(':')
            val name = parts[0].uppercase()
            val len = parts.getOrNull(1)?.toIntOrNull()
            if (len == null) { i = gt + 1; continue }
            val end = (gt + 1 + len).coerceAtMost(body.length)
            out[name] = body.substring(gt + 1, end)
            i = end
        }
        return out
    }

    /** Injects/overrides fields in an existing single ADIF record. */
    fun withExtraFields(record: String, extra: Map<String, String>): String {
        if (extra.isEmpty()) return record
        val existing = parseFields(record).keys
        val add = extra.filterKeys { it.uppercase() !in existing }
            .entries.joinToString("") { field(it.key, it.value) }
        if (add.isEmpty()) return record
        val idx = record.indexOf("<EOR>", ignoreCase = true)
        return if (idx < 0) record + add else
            record.substring(0, idx) + add + record.substring(idx)
    }

    fun singleRecordFile(record: String, programVersion: String): String =
        header(programVersion) + record
}
