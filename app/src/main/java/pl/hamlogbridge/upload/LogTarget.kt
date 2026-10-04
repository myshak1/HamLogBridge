package pl.hamlogbridge.upload

import pl.hamlogbridge.data.QsoEntity

data class TargetField(
    val key: String,
    val label: String,
    val hint: String = "",
    val secret: Boolean = false,
    val default: String = "",
    val required: Boolean = true
)

sealed class UploadResult {
    /** Accepted by the remote logger. */
    data class Ok(val message: String = "Uploaded") : UploadResult()
    /** Temporary problem (no network, 5xx, timeout) - worth another try. */
    data class Retry(val message: String) : UploadResult()
    /** Permanent problem (bad key, duplicate, malformed) - stop trying. */
    data class Fatal(val message: String) : UploadResult()
}

interface LogTarget {
    val id: String
    val title: String
    val blurb: String
    val fields: List<TargetField>

    /** @param record a single ADIF record ending with <EOR>. */
    suspend fun upload(cfg: Map<String, String>, record: String, qso: QsoEntity): UploadResult

    /**
     * Cheap connectivity/credential probe for the settings screen. Services
     * without a read-only endpoint fall back to "looks complete" - nothing is
     * ever written to the remote log by a test.
     */
    suspend fun test(cfg: Map<String, String>): UploadResult =
        UploadResult.Ok("Settings complete. Credentials are verified on the first QSO.")

    fun missingFields(cfg: Map<String, String>): List<TargetField> =
        fields.filter { it.required && cfg[it.key].isNullOrBlank() }
}
