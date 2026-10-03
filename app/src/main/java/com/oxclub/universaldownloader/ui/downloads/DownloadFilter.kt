package com.oxclub.universaldownloader.ui.downloads

import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.FileCategory

enum class StatusFilter { ALL, DOWNLOADING, COMPLETED, FAILED, PAUSED }
enum class SortOrder { NEWEST, OLDEST, NAME, SIZE, PROGRESS }

/** Pure filtering / searching / sorting so it can be unit-tested without Android. */
object DownloadFilter {
    fun matches(status: DownloadStatus, filter: StatusFilter): Boolean = when (filter) {
        StatusFilter.ALL -> true
        StatusFilter.DOWNLOADING -> status.isActive
        StatusFilter.COMPLETED -> status == DownloadStatus.COMPLETED
        StatusFilter.FAILED -> status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
        StatusFilter.PAUSED -> status == DownloadStatus.PAUSED
    }

    fun apply(
        list: List<DownloadEntity>,
        query: String,
        filter: StatusFilter,
        category: FileCategory?,
        sort: SortOrder,
    ): List<DownloadEntity> {
        val q = query.trim().lowercase()
        val filtered = list.filter { e ->
            matches(e.status, filter) &&
                (category == null || e.category == category) &&
                (q.isEmpty() || e.filename.lowercase().contains(q) || e.url.lowercase().contains(q) ||
                    (e.mimeType?.lowercase()?.contains(q) == true) || e.category.name.lowercase().contains(q))
        }
        return when (sort) {
            SortOrder.NEWEST -> filtered.sortedByDescending { it.createdAt }
            SortOrder.OLDEST -> filtered.sortedBy { it.createdAt }
            SortOrder.NAME -> filtered.sortedBy { it.filename.lowercase() }
            SortOrder.SIZE -> filtered.sortedByDescending { it.totalBytes }
            SortOrder.PROGRESS -> filtered.sortedByDescending {
                if (it.totalBytes > 0) it.downloadedBytes.toDouble() / it.totalBytes else 0.0
            }
        }
    }
}
