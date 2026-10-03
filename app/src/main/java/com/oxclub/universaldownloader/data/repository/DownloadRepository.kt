package com.oxclub.universaldownloader.data.repository

import com.oxclub.universaldownloader.data.database.DownloadDao
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/** Single access point to persisted downloads. */
class DownloadRepository(private val dao: DownloadDao) {
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<DownloadEntity?> = dao.observe(id)

    suspend fun get(id: Long) = dao.get(id)
    suspend fun getAll() = dao.getAll()
    suspend fun insert(e: DownloadEntity) = dao.insert(e)
    suspend fun update(e: DownloadEntity) = dao.update(e)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun maxPriority() = dao.maxPriority() ?: 0

    suspend fun queued() = dao.getByStatus(listOf(DownloadStatus.QUEUED))

    suspend fun findActiveByUrl(url: String) = dao.findByUrl(
        url,
        listOf(DownloadStatus.QUEUED, DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.VERIFYING),
    )

    suspend fun setStatus(id: Long, status: DownloadStatus, message: String? = null) = dao.setStatus(id, status, message)
    suspend fun setMessage(id: Long, message: String?) = dao.setMessage(id, message)
    suspend fun updateProgress(id: Long, bytes: Long, parts: String?, activeMillis: Long) = dao.updateProgress(id, bytes, parts, activeMillis)
    suspend fun markFailed(id: Long, message: String?, retryable: Boolean) = dao.markFailed(id, DownloadStatus.FAILED, message, retryable)
    suspend fun resetProgress(id: Long) = dao.resetProgress(id)
    suspend fun setPriority(id: Long, p: Int) = dao.setPriority(id, p)
    suspend fun setFilename(id: Long, name: String) = dao.setFilename(id, name)
    suspend fun setQueuedMessage(message: String?) = dao.setQueuedMessage(message)
    suspend fun requeueRetryable() = dao.requeueRetryable()
    suspend fun moveStatuses(from: List<DownloadStatus>, to: DownloadStatus) = dao.moveStatuses(from, to)
    suspend fun deleteByStatus(statuses: List<DownloadStatus>) = dao.deleteByStatus(statuses)
}
