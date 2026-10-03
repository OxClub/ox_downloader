package com.oxclub.universaldownloader.downloader

import com.oxclub.universaldownloader.data.database.DownloadEntity

object DownloadQueue {
    /** Queue order: higher priority first, then oldest first. */
    fun nextToStart(queued: List<DownloadEntity>, runningIds: Set<Long>, freeSlots: Int): List<DownloadEntity> {
        if (freeSlots <= 0) return emptyList()
        return queued
            .filter { it.id !in runningIds }
            .sortedWith(compareByDescending<DownloadEntity> { it.priority }.thenBy { it.createdAt }.thenBy { it.id })
            .take(freeSlots)
    }
}
