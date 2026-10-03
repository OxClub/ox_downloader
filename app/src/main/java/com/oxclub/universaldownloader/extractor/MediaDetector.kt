package com.oxclub.universaldownloader.extractor

import com.oxclub.universaldownloader.domain.model.StreamKind
import com.oxclub.universaldownloader.utils.FileNames

/** Detects segmented streaming manifests. These are reported, never downloaded as plain files. */
object MediaDetector {
    fun streamKind(contentType: String?, filename: String?): StreamKind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        val ext = filename?.let { FileNames.extensionOf(it) }
        return when {
            ct == "application/vnd.apple.mpegurl" || ct == "application/x-mpegurl" || ct == "audio/mpegurl" ||
                ct == "audio/x-mpegurl" || ext == "m3u8" -> StreamKind.HLS
            ct == "application/dash+xml" || ext == "mpd" -> StreamKind.DASH
            else -> null
        }
    }
}
