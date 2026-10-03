package com.oxclub.universaldownloader.downloader

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.data.network.HttpFetcher
import com.oxclub.universaldownloader.data.network.InsufficientStorageException
import com.oxclub.universaldownloader.data.network.NetworkMonitor
import com.oxclub.universaldownloader.data.preferences.SettingsRepository
import com.oxclub.universaldownloader.data.repository.DownloadRepository
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.LiveProgress
import com.oxclub.universaldownloader.extractor.ResourceDetector
import com.oxclub.universaldownloader.extractor.UrlAnalyzer
import com.oxclub.universaldownloader.notification.DownloadNotificationManager
import com.oxclub.universaldownloader.utils.AppLog
import com.oxclub.universaldownloader.utils.ErrorMessages
import com.oxclub.universaldownloader.utils.FileNames
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything needed to create a download (from an analysis result or the browser). */
data class NewDownload(
    val url: String,
    val filename: String,
    val finalUrl: String? = null,
    val mimeType: String? = null,
    val totalBytes: Long = -1,
    val etag: String? = null,
    val lastModified: String? = null,
    val supportsResume: Boolean = false,
    val server: String? = null,
    val checksum: String? = null,
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
)

sealed interface EnqueueResult {
    data class Added(val id: Long) : EnqueueResult
    data class Duplicate(val id: Long) : EnqueueResult
    data object SkippedExisting : EnqueueResult
    data class InsufficientStorage(val required: Long, val available: Long) : EnqueueResult
}

private enum class StopReason { PAUSE, CANCEL, DELETE, NETWORK, SHUTDOWN }

/**
 * Owns the queue. Downloads execute in this process's scope; a WorkManager worker running as a
 * foreground service keeps the process alive while anything is active, and re-starts the queue
 * when the network returns.
 */
class DownloadManager(
    private val context: Context,
    private val repo: DownloadRepository,
    private val analyzer: UrlAnalyzer,
    fetcher: HttpFetcher,
    private val fileStore: FileStore,
    private val settings: SettingsRepository,
    private val notifier: DownloadNotificationManager,
    private val network: NetworkMonitor,
) : DownloadEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val reasons = ConcurrentHashMap<Long, StopReason>()
    private val engine = DownloadEngine(context, repo, analyzer, SegmentDownloader(fetcher), fileStore, settings)
    private val pumpLock = Mutex()
    private val workerActive = AtomicBoolean(false)
    private val netEvents = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live

    val activeCount: Int get() = jobs.size

    // ---------------------------------------------------------------- lifecycle
    @Synchronized
    fun start() {
        if (started) return
        started = true
        network.register { netEvents.trySend(Unit) }
        scope.launch {
            for (e in netEvents) {
                delay(500) // debounce flapping connectivity callbacks
                onNetworkChanged()
            }
        }
        scope.launch {
            settings.flow.map { it.notifications }.distinctUntilChanged().collect { notifier.enabled = it }
        }
        scope.launch {
            settings.flow.map { it.wifiOnly to it.maxConcurrent }.distinctUntilChanged().collect { onNetworkChanged() }
        }
        scope.launch {
            recover()
            pumpInternal()
        }
    }

    /** After a process death nothing is really running: put interrupted downloads back in the queue. */
    private suspend fun recover() {
        repo.moveStatuses(
            listOf(DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING),
            DownloadStatus.QUEUED,
        )
        if (settings.current().autoResumeFailed) repo.requeueRetryable()
    }

    fun workerStarted() { workerActive.set(true) }
    fun workerStopped() { workerActive.set(false) }

    // ---------------------------------------------------------------- queue
    fun pump() {
        scope.launch { pumpInternal() }
    }

    private fun waitingMessage(wifiBlocked: Boolean): String =
        context.getString(if (wifiBlocked) R.string.waiting_for_wifi else R.string.waiting_for_network)

    private suspend fun pumpInternal() = pumpLock.withLock {
        val s = settings.current()
        val queued = repo.queued()
        if (!network.isAllowed(s.wifiOnly)) {
            if (queued.isNotEmpty()) {
                repo.setQueuedMessage(waitingMessage(network.isOnline()))
                scheduleResumeWhenOnline(s.wifiOnly)
            }
            return@withLock
        }
        val slots = s.maxConcurrent - jobs.size
        for (d in DownloadQueue.nextToStart(queued, jobs.keys, slots)) startJob(d.id)
        if (jobs.isNotEmpty()) ensureWorker()
    }

    private fun startJob(id: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) { runJob(id) }
        if (jobs.putIfAbsent(id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            jobs.remove(id)
            _live.update { it - id }
            pump()
        }
        job.start()
    }

    private suspend fun runJob(id: Long) {
        try {
            runWithRetries(id)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { handleStop(id, reasons.remove(id) ?: StopReason.SHUTDOWN) }
            throw e
        }
    }

    private suspend fun runWithRetries(id: Long) {
        var attempt = 0
        while (true) {
            val s = settings.current()
            val policy = RetryPolicy(s.retryCount, s.retryDelaySec * 1000L)
            try {
                engine.run(id, this)
                return
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is IOException && !network.isAllowed(s.wifiOnly)) {
                    // Connection lost: wait for it instead of burning retries.
                    repo.setStatus(id, DownloadStatus.QUEUED, waitingMessage(network.isOnline()))
                    return
                }
                if (policy.isRetryable(e) && attempt < policy.maxRetries) {
                    attempt++
                    val wait = policy.delayFor(attempt, e)
                    repo.setMessage(id, context.getString(R.string.retrying, attempt, policy.maxRetries, (wait / 1000).toInt()))
                    AppLog.d(TAG, "retry $attempt for #$id after ${e.javaClass.simpleName}")
                    delay(wait)
                    continue
                }
                fail(id, e, policy.isRetryable(e))
                return
            }
        }
    }

    private suspend fun fail(id: Long, e: Throwable, retryable: Boolean) {
        val message = ErrorMessages.describe(context, e)
        repo.markFailed(id, message, retryable)
        val d = repo.get(id)
        notifier.failed(id, d?.filename ?: "", message)
        AppLog.w(TAG, "download #$id failed: ${e.javaClass.simpleName}")
    }

    private suspend fun handleStop(id: Long, reason: StopReason) {
        val d = repo.get(id) ?: return
        when (reason) {
            StopReason.PAUSE -> {
                repo.setStatus(id, DownloadStatus.PAUSED, null)
                notifier.paused(id, d.filename)
            }
            StopReason.CANCEL -> finishCancel(id)
            StopReason.DELETE -> Unit
            StopReason.NETWORK -> repo.setStatus(id, DownloadStatus.QUEUED, waitingMessage(network.isOnline()))
            StopReason.SHUTDOWN -> repo.setStatus(id, DownloadStatus.QUEUED, null)
        }
    }

    private suspend fun finishCancel(id: Long) {
        fileStore.deletePartial(id)
        repo.resetProgress(id)
        repo.setStatus(id, DownloadStatus.CANCELLED, null)
        notifier.dismiss(id)
    }

    // ---------------------------------------------------------------- network
    private suspend fun onNetworkChanged() {
        val s = settings.current()
        if (!network.isAllowed(s.wifiOnly)) {
            for ((id, job) in jobs) {
                reasons[id] = StopReason.NETWORK
                job.cancel()
            }
            repo.setQueuedMessage(waitingMessage(network.isOnline()))
            scheduleResumeWhenOnline(s.wifiOnly)
        } else {
            repo.setQueuedMessage(null)
            if (s.autoResumeFailed) repo.requeueRetryable()
            pumpInternal()
        }
    }

    // ---------------------------------------------------------------- work manager
    private fun ensureWorker() {
        if (!workerActive.compareAndSet(false, true)) return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ACTIVE, ExistingWorkPolicy.KEEP, request)
    }

    private fun scheduleResumeWhenOnline(wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_RESUME, ExistingWorkPolicy.KEEP, request)
    }

    // ---------------------------------------------------------------- engine callbacks
    override fun onProgress(id: Long, filename: String, live: LiveProgress) {
        _live.update { it + (id to live) }
        notifier.progress(id, filename, live)
    }

    override fun onCompleted(entity: DownloadEntity) {
        notifier.completed(entity.id, entity.filename, entity.totalBytes, entity.destinationUri, entity.mimeType)
    }

    // ---------------------------------------------------------------- public operations
    suspend fun enqueue(n: NewDownload): EnqueueResult {
        val s = settings.current()
        repo.findActiveByUrl(n.url)?.let { return EnqueueResult.Duplicate(it.id) }
        if (n.totalBytes > 0) {
            val available = fileStore.availableBytes()
            if (available < n.totalBytes) return EnqueueResult.InsufficientStorage(n.totalBytes, available)
        }
        val name = FileNames.sanitize(n.filename)
        if (s.duplicatePolicy == com.oxclub.universaldownloader.domain.model.DuplicatePolicy.SKIP && fileStore.exists(name)) {
            return EnqueueResult.SkippedExisting
        }
        val id = repo.insert(
            DownloadEntity(
                url = n.url,
                finalUrl = n.finalUrl,
                filename = name,
                mimeType = n.mimeType,
                category = ResourceDetector.categoryOf(n.mimeType, name),
                totalBytes = n.totalBytes,
                status = if (s.autoStart) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
                etag = n.etag,
                lastModified = n.lastModified,
                supportsResume = n.supportsResume,
                partCount = s.partCount,
                server = n.server,
                checksum = n.checksum,
                cookie = n.cookie,
                referer = n.referer,
                userAgent = n.userAgent,
            ),
        )
        if (s.autoStart) pump()
        return EnqueueResult.Added(id)
    }

    fun pause(id: Long) {
        scope.launch {
            val job = jobs[id]
            if (job != null) {
                reasons[id] = StopReason.PAUSE
                job.cancel()
            } else if (repo.get(id)?.status == DownloadStatus.QUEUED) {
                repo.setStatus(id, DownloadStatus.PAUSED, null)
            }
        }
    }

    /** Resumes a paused download or retries a failed / cancelled one (continuing partial data when possible). */
    fun resume(id: Long) {
        scope.launch {
            val d = repo.get(id) ?: return@launch
            if (d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.FAILED || d.status == DownloadStatus.CANCELLED) {
                repo.setStatus(id, DownloadStatus.QUEUED, null)
                pumpInternal()
            }
        }
    }

    fun retry(id: Long) = resume(id)

    fun cancel(id: Long) {
        scope.launch {
            val job = jobs[id]
            if (job != null) {
                reasons[id] = StopReason.CANCEL
                job.cancel()
            } else {
                val d = repo.get(id) ?: return@launch
                if (d.status != DownloadStatus.COMPLETED) finishCancel(id)
            }
        }
    }

    fun pauseAll() {
        scope.launch {
            repo.moveStatuses(listOf(DownloadStatus.QUEUED), DownloadStatus.PAUSED)
            for ((id, job) in jobs) {
                reasons[id] = StopReason.PAUSE
                job.cancel()
            }
        }
    }

    fun resumeAll() {
        scope.launch {
            repo.moveStatuses(listOf(DownloadStatus.PAUSED), DownloadStatus.QUEUED)
            pumpInternal()
        }
    }

    fun cancelAll() {
        scope.launch {
            val ids = repo.getAll().filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }.map { it.id }
            ids.forEach { id ->
                val job = jobs[id]
                if (job != null) {
                    reasons[id] = StopReason.CANCEL
                    job.cancel()
                } else {
                    finishCancel(id)
                }
            }
        }
    }

    fun moveToTop(id: Long) {
        scope.launch { repo.setPriority(id, repo.maxPriority() + 1) }
    }

    fun delete(id: Long, deleteFile: Boolean) {
        scope.launch {
            jobs[id]?.let {
                reasons[id] = StopReason.DELETE
                it.cancelAndJoin()
            }
            val d = repo.get(id)
            val uri = d?.destinationUri
            if (deleteFile && uri != null) fileStore.delete(uri)
            fileStore.deletePartial(id)
            repo.delete(id)
            notifier.dismiss(id)
        }
    }

    /** Removes finished entries from the list (files stay on disk). */
    fun clearCompleted() {
        scope.launch { repo.deleteByStatus(listOf(DownloadStatus.COMPLETED)) }
    }

    /** Privacy: removes completed / failed / cancelled records. Downloaded files are kept. */
    suspend fun clearHistory(): Int =
        repo.deleteByStatus(listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED))

    /** Deletes partial data of failed / cancelled / orphaned downloads; returns the bytes freed. */
    suspend fun clearTemporaryFiles(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (file in fileStore.partialFiles()) {
            val id = file.nameWithoutExtension.toLongOrNull()
            val row = id?.let { repo.get(it) }
            val removable = row == null || row.status == DownloadStatus.FAILED || row.status == DownloadStatus.CANCELLED ||
                row.status == DownloadStatus.COMPLETED
            if (removable && !(id != null && jobs.containsKey(id))) {
                freed += file.length()
                file.delete()
                if (row != null) repo.resetProgress(row.id)
            }
        }
        freed
    }

    suspend fun rename(id: Long, newName: String): Boolean {
        val d = repo.get(id) ?: return false
        val name = FileNames.sanitize(newName)
        val uri = d.destinationUri
        if (d.status == DownloadStatus.COMPLETED && uri != null) {
            val newUri = fileStore.rename(uri, name) ?: return false
            repo.update(d.copy(filename = name, destinationUri = newUri, category = ResourceDetector.categoryOf(d.mimeType, name)))
        } else {
            repo.setFilename(id, name)
        }
        return true
    }

    suspend fun move(id: Long, treeUri: String): Boolean {
        val d = repo.get(id) ?: return false
        val uri = d.destinationUri ?: return false
        val saved = fileStore.moveTo(uri, d.filename, treeUri) ?: return false
        repo.update(d.copy(destinationUri = saved.uri, filename = saved.name))
        return true
    }

    companion object {
        private const val TAG = "DownloadManager"
        const val WORK_ACTIVE = "downloads-active"
        const val WORK_RESUME = "downloads-resume"
    }
}
