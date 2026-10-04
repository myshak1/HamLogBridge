package pl.hamlogbridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import pl.hamlogbridge.adif.Adif
import pl.hamlogbridge.net.BtPacketSource
import pl.hamlogbridge.upload.LocalAdifWriter
import pl.hamlogbridge.upload.Targets
import pl.hamlogbridge.upload.UploadWorker
import pl.hamlogbridge.wsjtx.WsjtxCodec
import pl.hamlogbridge.wsjtx.WsjtxMessage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DecodeRow(
    val utc: String,
    val snr: Int,
    val dt: Double,
    val dfHz: Long,
    val mode: String,
    val text: String,
    val receivedAt: Long = System.currentTimeMillis()
)

data class RigStatus(
    val programId: String,
    val dialFrequencyHz: Long,
    val mode: String,
    val deCall: String,
    val deGrid: String,
    val dxCall: String,
    val transmitting: Boolean,
    val txEnabled: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)

class Repository(private val ctx: Context) {

    val db = AppDb.get(ctx)
    val settings = SettingsStore(ctx)
    private val adifFile = LocalAdifWriter(ctx)

    val serviceRunning = MutableStateFlow(false)
    val boundPort = MutableStateFlow<Int?>(null)
    val lastPacketAt = MutableStateFlow<Long?>(null)
    val packetCount = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
    val decodes = MutableStateFlow<List<DecodeRow>>(emptyList())
    val rigStatus = MutableStateFlow<RigStatus?>(null)
    val trace = MutableStateFlow<List<String>>(emptyList())
    val btState = MutableStateFlow(BtPacketSource.State.IDLE)
    val btMessage = MutableStateFlow<String?>(null)

    private val utcTime = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
    private val dedupFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)

    suspend fun settingsSnapshot(): AppSettings = settings.flow.first()

    fun note(line: String) {
        val stamp = utcTime.format(Instant.now())
        trace.value = (listOf("$stamp $line") + trace.value).take(200)
    }

    fun onPacket(type: Long, fromHost: String) {
        packetCount.value = packetCount.value + 1
        lastPacketAt.value = System.currentTimeMillis()
        if (type != WsjtxCodec.TYPE_HEARTBEAT && type != WsjtxCodec.TYPE_STATUS) {
            note("${WsjtxCodec.typeName(type)} from $fromHost")
        }
    }

    suspend fun handle(msg: WsjtxMessage) {
        when (msg) {
            is WsjtxMessage.Decode -> {
                val row = DecodeRow(
                    utc = utcTime.format(Instant.ofEpochMilli(startOfUtcDay() + msg.timeMsSinceMidnight)),
                    snr = msg.snrDb,
                    dt = msg.deltaTimeSec,
                    dfHz = msg.deltaFrequencyHz,
                    mode = msg.mode.orEmpty(),
                    text = msg.message.orEmpty()
                )
                decodes.value = (listOf(row) + decodes.value).take(200)
            }

            is WsjtxMessage.Status -> {
                rigStatus.value = RigStatus(
                    programId = msg.id,
                    dialFrequencyHz = msg.dialFrequencyHz,
                    mode = msg.subMode?.takeIf { it.isNotBlank() } ?: msg.mode.orEmpty(),
                    deCall = msg.deCall.orEmpty(),
                    deGrid = msg.deGrid.orEmpty(),
                    dxCall = msg.dxCall.orEmpty(),
                    transmitting = msg.transmitting,
                    txEnabled = msg.txEnabled
                )
            }

            is WsjtxMessage.QsoLogged -> storeFromQsoLogged(msg)
            is WsjtxMessage.LoggedAdif -> storeFromAdif(msg)
            is WsjtxMessage.Clear -> decodes.value = emptyList()
            else -> Unit
        }
    }

    private fun startOfUtcDay(): Long =
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

    private suspend fun storeFromQsoLogged(m: WsjtxMessage.QsoLogged) {
        val s = settingsSnapshot()
        val defaults = Adif.StationDefaults(
            myCall = s.myCall, myGrid = s.myGrid, operator = s.operator,
            txPowerW = s.txPowerW, rigName = s.rigName, antenna = s.antenna
        )
        val record = Adif.fromQsoLogged(m, defaults)
        val on = m.timeOn ?: m.timeOff ?: Instant.now()
        val off = m.timeOff ?: on
        val call = (m.dxCall ?: "").uppercase().ifBlank { "UNKNOWN" }
        val band = Adif.bandFor(m.txFrequencyHz)

        val qso = QsoEntity(
            call = call,
            grid = m.dxGrid,
            mode = Adif.parseFields(record)["MODE"],
            band = band,
            freqHz = m.txFrequencyHz,
            timeOnEpoch = on.toEpochMilli(),
            timeOffEpoch = off.toEpochMilli(),
            rstSent = m.reportSent,
            rstRcvd = m.reportReceived,
            myCall = m.myCall?.ifBlank { s.myCall } ?: s.myCall,
            myGrid = m.myGrid?.ifBlank { s.myGrid } ?: s.myGrid,
            comment = m.comments,
            adif = record,
            sourceId = m.id,
            dedupKey = dedupKey(call, on, band)
        )
        persist(qso, s)
    }

    /**
     * WSJT-X sends both QSO Logged (5) and Logged ADIF (12) for the same
     * contact. The ADIF one is richer, so when it matches an existing row we
     * upgrade that row instead of logging the QSO twice.
     */
    private suspend fun storeFromAdif(m: WsjtxMessage.LoggedAdif) {
        val raw = m.adif?.takeIf { it.isNotBlank() } ?: return
        val f = Adif.parseFields(raw)
        val call = (f["CALL"] ?: return).uppercase()
        val on = parseAdifInstant(f["QSO_DATE"], f["TIME_ON"]) ?: Instant.now()
        val off = parseAdifInstant(f["QSO_DATE_OFF"] ?: f["QSO_DATE"], f["TIME_OFF"] ?: f["TIME_ON"]) ?: on
        val freqHz = f["FREQ"]?.toDoubleOrNull()?.let { (it * 1_000_000).toLong() } ?: 0L
        val band = f["BAND"] ?: Adif.bandFor(freqHz)
        val key = dedupKey(call, on, band)

        val s = settingsSnapshot()
        val record = normaliseRecord(raw, s)
        val existing = db.qsoDao().findByDedupKey(key)
        if (existing != null) {
            db.qsoDao().update(existing.copy(adif = record, grid = f["GRIDSQUARE"] ?: existing.grid))
            note("QSO $call enriched from ADIF message")
            return
        }

        persist(
            QsoEntity(
                call = call,
                grid = f["GRIDSQUARE"],
                mode = f["SUBMODE"]?.takeIf { it.isNotBlank() } ?: f["MODE"],
                band = band,
                freqHz = freqHz,
                timeOnEpoch = on.toEpochMilli(),
                timeOffEpoch = off.toEpochMilli(),
                rstSent = f["RST_SENT"],
                rstRcvd = f["RST_RCVD"],
                myCall = f["STATION_CALLSIGN"] ?: s.myCall,
                myGrid = f["MY_GRIDSQUARE"] ?: s.myGrid,
                comment = f["COMMENT"],
                adif = record,
                sourceId = m.id,
                dedupKey = key
            ),
            s
        )
    }

    /** Strips any header the sender included and injects station defaults. */
    private fun normaliseRecord(raw: String, s: AppSettings): String {
        var body = raw
        val eoh = body.indexOf("<EOH>", ignoreCase = true)
        if (eoh >= 0) body = body.substring(eoh + 5)
        body = body.trim()
        if (!body.contains("<EOR>", true)) body += "<EOR>\n"
        return Adif.withExtraFields(
            body, buildMap {
                s.myCall.takeIf { it.isNotBlank() }?.let { put("STATION_CALLSIGN", it) }
                s.myGrid.takeIf { it.isNotBlank() }?.let { put("MY_GRIDSQUARE", it) }
                s.operator.takeIf { it.isNotBlank() }?.let { put("OPERATOR", it) }
                s.txPowerW.takeIf { it.isNotBlank() }?.let { put("TX_PWR", it) }
                s.rigName.takeIf { it.isNotBlank() }?.let { put("MY_RIG", it) }
                s.antenna.takeIf { it.isNotBlank() }?.let { put("MY_ANTENNA", it) }
            }
        )
    }

    private suspend fun persist(qso: QsoEntity, s: AppSettings) {
        val id = db.qsoDao().insert(qso)
        if (id <= 0) {
            note("QSO ${qso.call} already logged, skipping")
            return
        }
        note("Logged ${qso.call} ${qso.band.orEmpty()} ${qso.mode.orEmpty()}")

        adifFile.append(qso.adif).onFailure { note("ADIF file write failed: ${it.message}") }

        val enabled = s.enabledTargetIds().filter { Targets.byId(it) != null }
        enabled.forEach { targetId ->
            db.uploadDao().insert(UploadEntity(qsoId = id, targetId = targetId))
        }
        if (enabled.isNotEmpty()) UploadWorker.enqueue(ctx)
    }

    suspend fun markUpload(item: UploadEntity, status: String, message: String?, attempts: Int = item.attempts) {
        db.uploadDao().update(
            item.copy(
                status = status, lastMessage = message, attempts = attempts,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun retryFailed() {
        db.uploadDao().requeue()
        UploadWorker.enqueue(ctx)
    }

    /** Re-queues one QSO to every currently enabled target. */
    suspend fun resend(qsoId: Long) {
        val s = settingsSnapshot()
        s.enabledTargetIds().forEach { targetId ->
            val inserted = db.uploadDao().insert(UploadEntity(qsoId = qsoId, targetId = targetId))
            if (inserted <= 0) {
                db.uploadDao().byStatus(UploadStatus.FAILED, 500)
                    .firstOrNull { it.qsoId == qsoId && it.targetId == targetId }
                    ?.let { db.uploadDao().requeueOne(it.id) }
                db.uploadDao().byStatus(UploadStatus.OK, 500)
                    .firstOrNull { it.qsoId == qsoId && it.targetId == targetId }
                    ?.let { db.uploadDao().requeueOne(it.id) }
            }
        }
        UploadWorker.enqueue(ctx)
    }

    fun exportFile() = adifFile.currentFile()

    private fun dedupKey(call: String, on: Instant, band: String?): String =
        "$call|${dedupFmt.format(on)}|${band.orEmpty()}"

    private fun parseAdifInstant(date: String?, time: String?): Instant? {
        if (date == null || date.length < 8) return null
        val t = (time ?: "000000").padEnd(6, '0')
        return runCatching {
            java.time.LocalDateTime.of(
                date.substring(0, 4).toInt(), date.substring(4, 6).toInt(), date.substring(6, 8).toInt(),
                t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt()
            ).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }
}
