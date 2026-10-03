package com.oxclub.universaldownloader.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oxclub.universaldownloader.MainActivity
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.domain.model.LiveProgress
import com.oxclub.universaldownloader.utils.Formatters

class DownloadNotificationManager(private val context: Context) {
    @Volatile
    var enabled: Boolean = true

    private val nm = NotificationManagerCompat.from(context)

    init {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN),
        )
    }

    private fun notifId(id: Long) = (id % 100_000L).toInt() + 1000

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(id: Long, action: String, label: Int, requestOffset: Int): NotificationCompat.Action {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action).putExtra(DownloadActionReceiver.EXTRA_ID, id)
        val pi = PendingIntent.getBroadcast(
            context, notifId(id) * 10 + requestOffset, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action(R.drawable.ic_stat_download, context.getString(label), pi)
    }

    private fun post(id: Int, n: Notification) {
        if (!enabled) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; downloads keep working without notifications.
        }
    }

    fun progress(id: Long, filename: String, live: LiveProgress) {
        val pct = Formatters.percent(live.bytes, live.total)
        val parts = mutableListOf<String>()
        if (live.total > 0) parts += "$pct%"
        parts += Formatters.speed(live.speedBytesPerSec)
        live.etaSeconds?.let { parts += context.getString(R.string.notif_remaining, Formatters.duration(it)) }
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(filename)
            .setContentText(parts.joinToString(" • "))
            .setSubText(context.getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, pct, live.total <= 0)
            .addAction(action(id, DownloadActionReceiver.ACTION_PAUSE, R.string.action_pause, 1))
            .addAction(action(id, DownloadActionReceiver.ACTION_CANCEL, R.string.action_cancel, 2))
        post(notifId(id), b.build())
    }

    fun paused(id: Long, filename: String) {
        val b = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(filename)
            .setContentText(context.getString(R.string.status_paused))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .addAction(action(id, DownloadActionReceiver.ACTION_RESUME, R.string.action_resume, 3))
            .addAction(action(id, DownloadActionReceiver.ACTION_CANCEL, R.string.action_cancel, 2))
        post(notifId(id), b.build())
    }

    fun completed(id: Long, filename: String, size: Long, uri: String?, mime: String?) {
        val b = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_complete))
            .setContentText("$filename • ${Formatters.size(size)}")
            .setAutoCancel(true)
            .setContentIntent(openApp())
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(
                R.drawable.ic_stat_download, context.getString(R.string.action_open),
                PendingIntent.getActivity(context, notifId(id) * 10 + 4, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            val send = Intent(Intent.ACTION_SEND).setType(mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(
                R.drawable.ic_stat_download, context.getString(R.string.action_share),
                PendingIntent.getActivity(context, notifId(id) * 10 + 5, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        post(notifId(id), b.build())
    }

    fun failed(id: Long, filename: String, reason: String) {
        val b = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_failed))
            .setContentText("$filename — $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$filename\n$reason"))
            .setAutoCancel(true)
            .setContentIntent(openApp())
        post(notifId(id), b.build())
    }

    fun dismiss(id: Long) {
        nm.cancel(notifId(id))
    }

    /** Required notification for the foreground (WorkManager) service that keeps downloads alive. */
    fun foreground(activeCount: Int): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.notif_active_downloads, activeCount, activeCount))
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    companion object {
        const val CH_PROGRESS = "downloads_progress"
        const val CH_RESULT = "downloads_result"
        const val CH_SERVICE = "downloads_service"
        const val SERVICE_NOTIFICATION_ID = 1
    }
}
