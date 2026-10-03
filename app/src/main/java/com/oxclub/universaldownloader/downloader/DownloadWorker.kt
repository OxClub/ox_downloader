package com.oxclub.universaldownloader.downloader

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.oxclub.universaldownloader.UniversalDownloaderApp
import com.oxclub.universaldownloader.notification.DownloadNotificationManager
import kotlinx.coroutines.delay

/**
 * Keeps the app process alive (as a foreground "data sync" service) while downloads are running
 * and restarts the queue when WorkManager's network constraint is met after a restart.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as UniversalDownloaderApp).container

    private fun info(active: Int) = ForegroundInfo(
        DownloadNotificationManager.SERVICE_NOTIFICATION_ID,
        container.notifier.foreground(active),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = info(container.downloadManager.activeCount)

    override suspend fun doWork(): Result {
        val manager = container.downloadManager
        manager.workerStarted()
        try {
            try {
                setForeground(info(manager.activeCount))
            } catch (e: Exception) {
                // Foreground start can be refused when the app is in the background; downloads still proceed.
            }
            manager.start()
            manager.pump()
            delay(1500)
            var idleTicks = 0
            var lastCount = -1
            while (idleTicks < 3) {
                val count = manager.activeCount
                idleTicks = if (count == 0) idleTicks + 1 else 0
                if (count != lastCount && count > 0) {
                    lastCount = count
                    try { setForeground(info(count)) } catch (e: Exception) { /* ignore */ }
                }
                delay(1000)
            }
        } finally {
            manager.workerStopped()
        }
        return Result.success()
    }
}
