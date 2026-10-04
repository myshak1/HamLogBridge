package pl.hamlogbridge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "qso",
    indices = [Index(value = ["call", "timeOnEpoch"]), Index(value = ["dedupKey"], unique = true)]
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val call: String,
    val grid: String? = null,
    val mode: String? = null,
    val band: String? = null,
    val freqHz: Long = 0,
    val timeOnEpoch: Long,
    val timeOffEpoch: Long,
    val rstSent: String? = null,
    val rstRcvd: String? = null,
    val myCall: String? = null,
    val myGrid: String? = null,
    val comment: String? = null,
    val adif: String,
    /** "WSJT-X" style program id from the datagram. */
    val sourceId: String? = null,
    /** call + UTC minute + band: collapses the QSO Logged + Logged ADIF pair. */
    val dedupKey: String,
    val createdAt: Long = System.currentTimeMillis()
)

object UploadStatus {
    const val PENDING = "PENDING"
    const val OK = "OK"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
}

@Entity(
    tableName = "upload",
    indices = [Index(value = ["qsoId", "targetId"], unique = true), Index(value = ["status"])]
)
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val qsoId: Long,
    val targetId: String,
    @ColumnInfo(defaultValue = UploadStatus.PENDING) val status: String = UploadStatus.PENDING,
    val attempts: Int = 0,
    val lastMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class UploadWithQso(
    val uploadId: Long,
    val qsoId: Long,
    val targetId: String,
    val status: String,
    val attempts: Int,
    val lastMessage: String?,
    val adif: String,
    val call: String
)
