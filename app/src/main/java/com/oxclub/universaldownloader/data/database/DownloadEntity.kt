package com.oxclub.universaldownloader.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.FileCategory

@Entity(
    tableName = "downloads",
    indices = [Index("status"), Index("category"), Index("url")],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val finalUrl: String? = null,
    val filename: String,
    val mimeType: String? = null,
    val category: FileCategory = FileCategory.OTHER,
    /** -1 when the server did not report a size. */
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val destinationUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    /** True when a FAILED download failed for a transient reason and may be retried automatically. */
    val retryable: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val supportsResume: Boolean = false,
    val partCount: Int = 4,
    val priority: Int = 0,
    val server: String? = null,
    /** Serialized per-segment progress, see PartCodec. */
    val partsState: String? = null,
    /** Milliseconds actually spent transferring (excludes pauses), used for average speed. */
    val activeMillis: Long = 0,
    /** "algo:hex" checksum to verify, from the server or the user. */
    val checksum: String? = null,
    val checksumVerified: Boolean = false,
    /** Browser-session headers for downloads started from the built-in browser. Never logged; cleared on completion. */
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
)
