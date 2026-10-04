package pl.hamlogbridge.upload

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.hamlogbridge.adif.Adif
import pl.hamlogbridge.data.QsoEntity

private val JSON = "application/json; charset=utf-8".toMediaType()
private val TEXT = "text/plain; charset=utf-8".toMediaType()

/**
 * Cloudlog and Wavelog share the same QSO API:
 *   POST {base}/index.php/api/qso   {"key","station_profile_id","type":"adif","string"}
 */
object CloudlogTarget : LogTarget {
    override val id = "cloudlog"
    override val title = "Cloudlog / Wavelog"
    override val blurb = "Self-hosted logbook. Needs a read/write API key and the station profile id."
    override val fields = listOf(
        TargetField("url", "Base URL", "https://log.example.com  or  http://192.168.1.50/cloudlog"),
        TargetField("key", "API key", "cl12ab...", secret = true),
        TargetField("station_id", "Station profile id", "1", default = "1")
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val base = cfg["url"].orEmpty().trim().trimEnd('/')
        val endpoint = when {
            base.endsWith("/api/qso") -> base
            base.endsWith("/index.php") -> "$base/api/qso"
            else -> "$base/index.php/api/qso"
        }
        endpoint.toHttpUrlOrNull() ?: return UploadResult.Fatal("Invalid URL")

        val payload = JSONObject()
            .put("key", cfg["key"].orEmpty().trim())
            .put("station_profile_id", cfg["station_id"]?.trim().orEmpty().ifBlank { "1" })
            .put("type", "adif")
            .put("string", Adif.singleRecordFile(record, "1.0.0"))
            .toString()

        val req = Request.Builder().url(endpoint)
            .post(payload.toRequestBody(JSON))
            .header("User-Agent", "HamLogBridge/1.0")
            .build()

        return Http.call(req) { resp, body ->
            val lower = body.lowercase()
            when {
                resp.isSuccessful && lower.contains("\"status\":\"created\"") -> UploadResult.Ok("Created")
                resp.isSuccessful && lower.contains("duplicate") -> UploadResult.Ok("Duplicate, already in log")
                resp.isSuccessful && !lower.contains("\"status\":\"failed\"") -> UploadResult.Ok(Http.trim(body, 80))
                else -> UploadResult.Fatal(Http.trim(body).ifBlank { "HTTP ${resp.code}" })
            }
        }
    }
    override suspend fun test(cfg: Map<String, String>): UploadResult {
        val base = cfg["url"].orEmpty().trim().trimEnd('/')
        val key = cfg["key"].orEmpty().trim()
        val url = "$base/index.php/api/statistics/$key"
        url.toHttpUrlOrNull() ?: return UploadResult.Fatal("Invalid URL")
        val req = Request.Builder().url(url).get().header("User-Agent", "HamLogBridge/1.0").build()
        return Http.call(req) { resp, body ->
            when {
                resp.isSuccessful && body.contains("{") -> UploadResult.Ok("Reachable, key accepted")
                resp.code == 404 -> UploadResult.Retry("404 - check the base URL")
                else -> UploadResult.Fatal(Http.trim(body).ifBlank { "HTTP ${resp.code}" })
            }
        }
    }
}

/** QRZ.com Logbook API - one key per logbook, found under Settings in the logbook. */
object QrzTarget : LogTarget {
    override val id = "qrz"
    override val title = "QRZ.com Logbook"
    override val blurb = "Needs an XML/logbook API key (QRZ Logbook > Settings). Subscription required for some features."
    override val fields = listOf(
        TargetField("key", "Logbook API key", "1A2B-3C4D-5E6F-7G8H", secret = true),
        TargetField("replace", "Replace duplicates (0/1)", "0", default = "0", required = false)
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val form = FormBody.Builder()
            .add("KEY", cfg["key"].orEmpty().trim())
            .add("ACTION", "INSERT")
            .add("ADIF", record)
        if (cfg["replace"] == "1") form.add("OPTION", "REPLACE")

        val req = Request.Builder().url("https://logbook.qrz.com/api")
            .post(form.build())
            .header("User-Agent", "HamLogBridge/1.0")
            .build()

        return Http.call(req) { _, body ->
            val map = body.split('&').mapNotNull {
                val i = it.indexOf('='); if (i < 0) null else it.substring(0, i).uppercase() to it.substring(i + 1)
            }.toMap()
            val reason = map["REASON"] ?: map["EXTENDED"] ?: Http.trim(body, 120)
            when (map["RESULT"]?.uppercase()) {
                "OK" -> UploadResult.Ok("LOGID ${map["LOGID"] ?: "?"}")
                "REPLACE" -> UploadResult.Ok("Replaced existing QSO")
                "AUTH" -> UploadResult.Fatal("Bad API key")
                "FAIL" -> if (reason.contains("duplicate", true))
                    UploadResult.Ok("Duplicate, already in log") else UploadResult.Fatal(reason)
                else -> UploadResult.Retry(reason.ifBlank { "Unexpected reply" })
            }
        }
    }
    override suspend fun test(cfg: Map<String, String>): UploadResult {
        val form = FormBody.Builder()
            .add("KEY", cfg["key"].orEmpty().trim())
            .add("ACTION", "STATUS")
            .build()
        val req = Request.Builder().url("https://logbook.qrz.com/api")
            .post(form).header("User-Agent", "HamLogBridge/1.0").build()
        return Http.call(req) { _, body ->
            when {
                body.contains("RESULT=OK", true) -> {
                    val count = Regex("COUNT=([0-9]+)").find(body)?.groupValues?.get(1)
                    UploadResult.Ok("Key valid" + (count?.let { ", $it QSOs in logbook" } ?: ""))
                }
                body.contains("AUTH", true) -> UploadResult.Fatal("Bad API key")
                else -> UploadResult.Fatal(Http.trim(body, 120).ifBlank { "Unexpected reply" })
            }
        }
    }
}

/** Club Log real-time upload endpoint. */
object ClublogTarget : LogTarget {
    override val id = "clublog"
    override val title = "Club Log"
    override val blurb = "Real-time QSO upload. Needs your Club Log account plus an API key from clublog.org/api."
    override val fields = listOf(
        TargetField("email", "Account e-mail", "you@example.com"),
        TargetField("password", "Password", secret = true),
        TargetField("callsign", "Logged callsign", "SP1ABC"),
        TargetField("api", "API key", secret = true)
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val form = FormBody.Builder()
            .add("email", cfg["email"].orEmpty().trim())
            .add("password", cfg["password"].orEmpty())
            .add("callsign", cfg["callsign"].orEmpty().trim().uppercase())
            .add("adif", record)
            .add("api", cfg["api"].orEmpty().trim())
            .add("test", "0")
            .build()

        val req = Request.Builder().url("https://clublog.org/realtime.php")
            .post(form).header("User-Agent", "HamLogBridge/1.0").build()

        return Http.call(req) { resp, body ->
            val t = Http.trim(body, 200)
            when {
                resp.isSuccessful && (t.isBlank() || t.startsWith("OK", true)) -> UploadResult.Ok("Accepted")
                t.contains("duplicate", true) -> UploadResult.Ok("Duplicate, already in log")
                t.contains("password", true) || t.contains("api key", true) -> UploadResult.Fatal(t)
                resp.isSuccessful -> UploadResult.Ok(t.take(80))
                else -> UploadResult.Fatal(t.ifBlank { "HTTP ${resp.code}" })
            }
        }
    }
}

/** HRDLog.net robot endpoint. */
object HrdLogTarget : LogTarget {
    override val id = "hrdlog"
    override val title = "HRDLog.net"
    override val blurb = "Uses your callsign plus the upload code from your HRDLog account page."
    override val fields = listOf(
        TargetField("callsign", "Callsign", "SP1ABC"),
        TargetField("code", "Upload code", secret = true)
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val form = FormBody.Builder()
            .add("Callsign", cfg["callsign"].orEmpty().trim().uppercase())
            .add("Code", cfg["code"].orEmpty().trim())
            .add("App", "HamLogBridge")
            .add("ADIFData", record)
            .build()

        val req = Request.Builder().url("https://robot.hrdlog.net/NewEntry.aspx")
            .post(form).header("User-Agent", "HamLogBridge/1.0").build()

        return Http.call(req) { resp, body ->
            val t = Http.trim(body, 200)
            when {
                resp.isSuccessful && !t.contains("error", true) -> UploadResult.Ok(t.take(80).ifBlank { "Accepted" })
                t.contains("code", true) -> UploadResult.Fatal(t)
                else -> UploadResult.Fatal(t.ifBlank { "HTTP ${resp.code}" })
            }
        }
    }
}

/** eQSL.cc ADIF import. Credentials travel inside the ADIF record itself. */
object EqslTarget : LogTarget {
    override val id = "eqsl"
    override val title = "eQSL.cc"
    override val blurb = "Uploads the QSO as an outgoing eQSL. Nickname is only needed if you have several QTHs."
    override val fields = listOf(
        TargetField("user", "eQSL user", "SP1ABC"),
        TargetField("password", "eQSL password", secret = true),
        TargetField("nickname", "QTH nickname", "HOME", required = false),
        TargetField("message", "QSL message", required = false)
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val enriched = Adif.withExtraFields(
            record, buildMap {
                put("EQSL_USER", cfg["user"].orEmpty().trim())
                put("EQSL_PSWD", cfg["password"].orEmpty())
                cfg["nickname"]?.takeIf { it.isNotBlank() }?.let { put("APP_EQSL_QTH_NICKNAME", it) }
                cfg["message"]?.takeIf { it.isNotBlank() }?.let { put("QSLMSG", it) }
            }
        )
        val adifFile = Adif.singleRecordFile(enriched, "1.0.0")
        val form = FormBody.Builder().add("ADIFData", adifFile).build()

        val req = Request.Builder().url("https://www.eqsl.cc/qslcard/importADIF.cfm")
            .post(form).header("User-Agent", "HamLogBridge/1.0").build()

        return Http.call(req) { resp, body ->
            val t = Http.trim(body, 300)
            when {
                t.contains("Result: 1 out of 1", true) -> UploadResult.Ok("1 record added")
                t.contains("out of 1 records added", true) && t.contains("Duplicate", true) ->
                    UploadResult.Ok("Duplicate, already on eQSL")
                t.contains("Bad record", true) || t.contains("Error", true) -> UploadResult.Fatal(t.take(160))
                resp.isSuccessful -> UploadResult.Ok(t.take(120).ifBlank { "Accepted" })
                else -> UploadResult.Retry("HTTP ${resp.code}")
            }
        }
    }
}

/**
 * Generic HTTP target - covers everything with a custom endpoint:
 * Log4OM's remote API, Ham2K, a home-grown script, n8n, Home Assistant...
 */
object WebhookTarget : LogTarget {
    override val id = "webhook"
    override val title = "Custom webhook"
    override val blurb = "POSTs the QSO to any URL. Choose raw ADIF or a JSON envelope."
    override val fields = listOf(
        TargetField("url", "Endpoint URL", "https://example.com/qso"),
        TargetField("format", "Body format (adif/json)", "adif", default = "adif", required = false),
        TargetField("headers", "Extra headers, one per line", "Authorization: Bearer ...", required = false)
    )

    override suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult {
        val url = cfg["url"].orEmpty().trim()
        url.toHttpUrlOrNull() ?: return UploadResult.Fatal("Invalid URL")
        val json = cfg["format"].orEmpty().equals("json", true)

        val body = if (json) {
            JSONObject()
                .put("call", qso.call)
                .put("band", qso.band)
                .put("mode", qso.mode)
                .put("freq_hz", qso.freqHz)
                .put("rst_sent", qso.rstSent)
                .put("rst_rcvd", qso.rstRcvd)
                .put("time_on", qso.timeOnEpoch)
                .put("time_off", qso.timeOffEpoch)
                .put("my_call", qso.myCall)
                .put("my_grid", qso.myGrid)
                .put("grid", qso.grid)
                .put("adif", record)
                .toString().toRequestBody(JSON)
        } else record.toRequestBody(TEXT)

        val b = Request.Builder().url(url).post(body).header("User-Agent", "HamLogBridge/1.0")
        cfg["headers"].orEmpty().lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) b.header(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }

        return Http.call(b.build()) { resp, bodyText ->
            if (resp.isSuccessful) UploadResult.Ok("HTTP ${resp.code}")
            else UploadResult.Fatal(Http.trim(bodyText).ifBlank { "HTTP ${resp.code}" })
        }
    }
}

object Targets {
    val all: List<LogTarget> = listOf(
        CloudlogTarget, QrzTarget, ClublogTarget, HrdLogTarget, EqslTarget, WebhookTarget
    )

    /** Written to disk by the service, so it is not a network target. */
    const val LOCAL_FILE_ID = "adif_file"

    fun byId(id: String): LogTarget? = all.firstOrNull { it.id == id }

    fun titleOf(id: String): String =
        if (id == LOCAL_FILE_ID) "ADIF file on this phone" else byId(id)?.title ?: id
}
