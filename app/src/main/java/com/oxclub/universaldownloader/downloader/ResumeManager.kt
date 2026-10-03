package com.oxclub.universaldownloader.downloader

import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.ProbeResult

/** Decides whether partial data on disk may be continued or the file must restart from zero. */
object ResumeManager {
    fun validatorsChanged(stored: DownloadEntity, probe: ProbeResult): Boolean {
        if (stored.etag != null && probe.etag != null) return stored.etag != probe.etag
        if (stored.etag == null && stored.lastModified != null && probe.lastModified != null) {
            if (stored.lastModified != probe.lastModified) return true
        }
        if (stored.totalBytes > 0 && probe.contentLength > 0 && stored.totalBytes != probe.contentLength) return true
        return false
    }

    /** True if the existing partial file cannot be trusted. */
    fun mustRestart(stored: DownloadEntity, probe: ProbeResult, rangesNow: Boolean, parts: List<PartState>?): Boolean =
        parts == null || !stored.supportsResume || !rangesNow || validatorsChanged(stored, probe)

    /** Value for the If-Range header: a strong ETag, otherwise Last-Modified. */
    fun validatorFor(probe: ProbeResult): String? {
        val etag = probe.etag
        if (etag != null && !etag.startsWith("W/")) return etag
        return probe.lastModified
    }
}
