package pl.hamlogbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QsoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(qso: QsoEntity): Long

    @Update
    suspend fun update(qso: QsoEntity)

    @Query("SELECT * FROM qso WHERE dedupKey = :key LIMIT 1")
    suspend fun findByDedupKey(key: String): QsoEntity?

    @Query("SELECT * FROM qso WHERE id = :id")
    suspend fun byId(id: Long): QsoEntity?

    @Query("SELECT * FROM qso ORDER BY timeOnEpoch DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<QsoEntity>>

    @Query("SELECT * FROM qso ORDER BY timeOnEpoch ASC")
    suspend fun all(): List<QsoEntity>

    @Query("SELECT COUNT(*) FROM qso")
    fun count(): Flow<Int>

    @Query("DELETE FROM qso")
    suspend fun deleteAll()
}

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(u: UploadEntity): Long

    @Update
    suspend fun update(u: UploadEntity)

    @Query("SELECT * FROM upload WHERE status = :status ORDER BY id ASC LIMIT :limit")
    suspend fun byStatus(status: String = UploadStatus.PENDING, limit: Int = 200): List<UploadEntity>

    @Query("SELECT * FROM upload WHERE qsoId IN (:ids)")
    fun forQsos(ids: List<Long>): Flow<List<UploadEntity>>

    @Query("SELECT * FROM upload ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int = 900): Flow<List<UploadEntity>>

    @Query("SELECT COUNT(*) FROM upload WHERE status = :status")
    fun countByStatus(status: String): Flow<Int>

    @Query("UPDATE upload SET status = :newStatus, attempts = 0 WHERE status = :old")
    suspend fun requeue(old: String = UploadStatus.FAILED, newStatus: String = UploadStatus.PENDING)

    @Query("UPDATE upload SET status = :newStatus, attempts = 0 WHERE id = :id")
    suspend fun requeueOne(id: Long, newStatus: String = UploadStatus.PENDING)

    @Query("DELETE FROM upload")
    suspend fun deleteAll()
}
