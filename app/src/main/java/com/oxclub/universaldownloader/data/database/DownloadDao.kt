package com.oxclub.universaldownloader.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    suspend fun getByStatus(statuses: List<DownloadStatus>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE url = :url AND status IN (:statuses) LIMIT 1")
    suspend fun findByUrl(url: String, statuses: List<DownloadStatus>): DownloadEntity?

    @Query("SELECT MAX(priority) FROM downloads")
    suspend fun maxPriority(): Int?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status IN (:statuses)")
    suspend fun deleteByStatus(statuses: List<DownloadStatus>): Int

    @Query("UPDATE downloads SET status = :status, errorMessage = :message WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, message: String?)

    @Query("UPDATE downloads SET errorMessage = :message WHERE id = :id")
    suspend fun setMessage(id: Long, message: String?)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, partsState = :parts, activeMillis = :active WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, parts: String?, active: Long)

    @Query("UPDATE downloads SET status = :status, errorMessage = :message, retryable = :retryable WHERE id = :id")
    suspend fun markFailed(id: Long, status: DownloadStatus, message: String?, retryable: Boolean)

    @Query("UPDATE downloads SET status = :to WHERE status IN (:from)")
    suspend fun moveStatuses(from: List<DownloadStatus>, to: DownloadStatus)

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'FAILED' AND retryable = 1")
    suspend fun requeueRetryable()

    @Query("UPDATE downloads SET errorMessage = :message WHERE status = 'QUEUED'")
    suspend fun setQueuedMessage(message: String?)

    @Query("UPDATE downloads SET downloadedBytes = 0, partsState = NULL, cookie = NULL WHERE id = :id")
    suspend fun resetProgress(id: Long)

    @Query("UPDATE downloads SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: Long, priority: Int)

    @Query("UPDATE downloads SET filename = :filename WHERE id = :id")
    suspend fun setFilename(id: Long, filename: String)
}
