package com.oxclub.universaldownloader.downloader

import android.content.Context
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.data.network.ChecksumMismatchException
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.InsufficientStorageException
import com.oxclub.universaldownloader.data.network.RangeNotHonoredException
import com.oxclub.universaldownloader.data.preferences.AppSettings
import com.oxclub.universaldownloader.data.preferences.SettingsRepository
import com.oxclub.universaldownloader.data.repository.DownloadRepository
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.LiveProgress
import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.domain.model.ProbeResult
import com.oxclub.universaldownloader.extractor.ResourceDetector
import com.oxclub.universaldownloader.extractor.UrlAnalyzer
import com.oxclub.universaldownloader.utils.Checksums
import com.oxclub.universaldownloader.utils.Formatters
import com.oxclub.universaldownloader.utils.UrlUtils
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one download from probing to the final file. All progress is derived from real bytes
 * written; nothing is simulated.
 */
class DownloadEngine(
    private val context: Context,
    private val repo: DownloadRepository,
    private val analyzer: UrlAnalyzer,
    private val segments: SegmentDownloader,
    private val fileStore: FileStore,
    private val settings: SettingsRepository,
) {
    interface Listener {
        fun onProgress(id: Long, filename: String, live: LiveProgress)
        fun onCompleted(entity: DownloadEntity)
    }

    suspend fun run(id: Long, listener: Listener) {
        var restarts = 0
        while (true) {
            try {
                runOnce(id, listener, forceSingle = restarts >= 2)
                return
            } catch (e: RangeNotHonoredException) {
                restarts++
                if (restarts > 2) throw e
                // Resource changed or ranges are not honoured: discard partial data and start over.
                fileStore.deletePartial(id)
                repo.resetProgress(id)
            }
        }
    }

    private fun headersFor(d: DownloadEntity): Map<String, String> = buildMap {
        d.userAgent?.let { put("User-Agent", it) }
        d.referer?.let { put("Referer", it) }
        d.cookie?.let { put("Cookie", it) }
    }

    private suspend fun probeWithFallback(d: DownloadEntity, headers: Map<String, String>, cfg: NetConfig): ProbeResult {
        val primary = d.finalUrl ?: d.url
        return try {
            analyzer.probe(primary, headers, cfg)
        } catch (e: HttpStatusException) {
            // A stored (possibly signed/expired) final URL may be gone; try the original URL once.
            if (primary != d.url && e.code in setOf(403, 404, 410)) analyzer.probe(d.url, headers, cfg) else throw e
        }
    }

    private suspend fun runOnce(id: Long, listener: Listener, forceSingle: Boolean) {
        val s: AppSettings = settings.current()
        val cfg = s.netConfig()
        var d = repo.get(id) ?: return
        val hadProgress = d.downloadedBytes > 0 && d.partsState != null
        val now = System.currentTimeMillis()
        repo.update(
            d.copy(
                status = if (hadProgress) DownloadStatus.DOWNLOADING else DownloadStatus.ANALYZING,
                errorMessage = null,
                startedAt = d.startedAt ?: now,
            ),
        )
        d = repo.get(id) ?: return
        val headers = headersFor(d)

        val probe = probeWithFallback(d, headers, cfg)
        val total = probe.contentLength
        val ranges = probe.supportsRanges && total > 0 && !forceSingle

        var parts: List<PartState>? = PartCodec.decode(d.partsState)
        if (hadProgress && !ResumeManager.mustRestart(d, probe, ranges, parts)) {
            // keep the partial data
        } else {
            fileStore.deletePartial(id)
            parts = null
        }
        val plan: List<PartState> = parts ?: PartPlanner.plan(total, ranges, d.partCount)
        val startBytes = plan.sumOf { it.done.get() }

        repo.update(
            d.copy(
                finalUrl = probe.finalUrl,
                mimeType = d.mimeType ?: probe.contentType,
                totalBytes = total,
                downloadedBytes = startBytes,
                etag = probe.etag,
                lastModified = probe.lastModified,
                supportsResume = ranges,
                server = probe.server ?: d.server,
                checksum = d.checksum ?: Checksums.fromContentMd5(probe.contentMd5Base64),
                status = DownloadStatus.DOWNLOADING,
                partsState = PartCodec.encode(plan),
            ),
        )

        if (total > 0) {
            val remaining = total - startBytes
            val available = fileStore.availableBytes()
            if (available < remaining) throw InsufficientStorageException(remaining, available)
        }

        val file = fileStore.partialFile(id)
        val sessionStart = System.currentTimeMillis()
        val baseActive = d.activeMillis
        val spec = SegmentSpec(probe.finalUrl, headers, cfg, total, ResumeManager.validatorFor(probe))

        try {
            RandomAccessFile(file, "rw").use { raf ->
                if (total > 0 && raf.length() != total) raf.setLength(total)
                val channel = raf.channel
                coroutineScope {
                    val ticker = launch { tick(id, d.filename, total, plan, listener, baseActive, sessionStart) }
                    try {
                        plan.filter { !it.isComplete }
                            .map { part -> async { segments.download(part, spec, channel) } }
                            .awaitAll()
                    } finally {
                        ticker.cancel()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                repo.updateProgress(
                    id,
                    plan.sumOf { it.done.get() },
                    PartCodec.encode(plan),
                    baseActive + (System.currentTimeMillis() - sessionStart),
                )
            }
        }

        // ---------- verify ----------
        repo.setStatus(id, DownloadStatus.VERIFYING, null)
        val written = plan.sumOf { it.done.get() }
        if (!file.exists()) throw IOException("Downloaded file is missing")
        if (total > 0) {
            if (plan.any { !it.isComplete } || file.length() != total) throw IOException("Downloaded file is incomplete")
        } else if (file.length() != written) {
            throw IOException("Downloaded file size does not match the received bytes")
        }
        var verified = false
        val expected = repo.get(id)?.checksum
        if (expected != null) {
            if (!Checksums.verify(file, expected)) throw ChecksumMismatchException()
            verified = true
        }

        // ---------- save ----------
        val fresh = repo.get(id) ?: return
        val saved = fileStore.saveFromTemp(file, fresh.filename, s.duplicatePolicy)
        if (saved == null) {
            fileStore.deletePartial(id)
            repo.setStatus(id, DownloadStatus.CANCELLED, context.getString(R.string.skipped_exists))
            return
        }
        val finished = System.currentTimeMillis()
        val mime = fresh.mimeType ?: probe.contentType
        val keepUrls = !s.doNotStoreUrls
        val completed = fresh.copy(
            status = DownloadStatus.COMPLETED,
            destinationUri = saved.uri,
            filename = saved.name,
            category = ResourceDetector.categoryOf(mime, saved.name),
            totalBytes = saved.size,
            downloadedBytes = saved.size,
            completedAt = finished,
            errorMessage = null,
            retryable = false,
            checksumVerified = verified,
            cookie = null,
            partsState = null,
            activeMillis = baseActive + (finished - sessionStart),
            url = if (keepUrls) fresh.url else UrlUtils.hostOnly(fresh.url),
            finalUrl = if (keepUrls) fresh.finalUrl else null,
            referer = null,
        )
        repo.update(completed)
        fileStore.deletePartial(id)
        listener.onCompleted(completed)
    }

    private suspend fun tick(
        id: Long,
        filename: String,
        total: Long,
        parts: List<PartState>,
        listener: Listener,
        baseActive: Long,
        sessionStart: Long,
    ) {
        val meter = SpeedMeter()
        var lastPersist = System.currentTimeMillis()
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            val bytes = parts.sumOf { it.done.get() }
            val speed = meter.sample(now, bytes)
            val eta = if (total > 0) Formatters.etaSeconds(total - bytes, speed) else null
            listener.onProgress(id, filename, LiveProgress(bytes, total, speed, eta, parts.size))
            if (now - lastPersist >= PERSIST_INTERVAL_MS) {
                repo.updateProgress(id, bytes, PartCodec.encode(parts), baseActive + (now - sessionStart))
                lastPersist = now
            }
            if (total <= 0) {
                val available = fileStore.availableBytes()
                if (available < LOW_SPACE_BYTES) throw InsufficientStorageException(LOW_SPACE_BYTES, available)
            }
        }
    }

    companion object {
        private const val PERSIST_INTERVAL_MS = 2000L
        private const val LOW_SPACE_BYTES = 8L * 1024 * 1024
    }
}
