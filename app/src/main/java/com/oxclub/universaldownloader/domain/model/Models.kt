package com.oxclub.universaldownloader.domain.model

enum class DownloadStatus {
    QUEUED, ANALYZING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED, VERIFYING;

    /** Waiting for or using a network connection / the queue. */
    val isActive: Boolean get() = this == QUEUED || this == ANALYZING || this == DOWNLOADING || this == VERIFYING
    val isRunning: Boolean get() = this == ANALYZING || this == DOWNLOADING || this == VERIFYING
}

enum class FileCategory { VIDEO, MUSIC, IMAGE, DOCUMENT, COMPRESSED, PROGRAM, SUBTITLE, OTHER }

enum class DuplicatePolicy { RENAME, REPLACE, SKIP }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor(val argb: Long) {
    VIOLET(0xFF5B3FD9),
    BLUE(0xFF1E6FD9),
    TEAL(0xFF00796B),
    ORANGE(0xFFD9541E),
    PINK(0xFFC2185B),
}

enum class StreamKind { HLS, DASH }

/** Network behaviour used for every request; derived from user settings. */
data class NetConfig(
    val connectTimeoutSec: Int = 20,
    val readTimeoutSec: Int = 30,
    val userAgent: String = "UniversalDownloader/1.0 (Android)",
    val allowLocal: Boolean = false,
)

/** Everything learned about a URL from a single lightweight request. */
data class ProbeResult(
    val originalUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    /** Total size of the resource in bytes, or -1 when the server did not say. */
    val contentLength: Long,
    val contentDisposition: String?,
    val filename: String,
    val supportsRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val server: String?,
    val cacheControl: String?,
    val contentMd5Base64: String?,
    val redirectCount: Int,
)

data class DiscoveredResource(
    val url: String,
    val filename: String,
    val mimeType: String?,
    val category: FileCategory,
    val sizeBytes: Long = -1,
    val sourceTag: String = "",
    val streamKind: StreamKind? = null,
    val probed: Boolean = false,
)

data class PageContent(
    val title: String?,
    val resources: List<DiscoveredResource>,
    /** True when the page likely loads more content with JavaScript that was not executed. */
    val maybeIncomplete: Boolean,
)

sealed interface AnalysisResult {
    data class Direct(val probe: ProbeResult, val resource: DiscoveredResource) : AnalysisResult
    data class Page(val probe: ProbeResult) : AnalysisResult
    data class Manifest(val probe: ProbeResult, val kind: StreamKind) : AnalysisResult
}

/** In-memory progress of a running download (never persisted on every tick). */
data class LiveProgress(
    val bytes: Long,
    val total: Long,
    val speedBytesPerSec: Double,
    val etaSeconds: Long?,
    val parts: Int,
)
