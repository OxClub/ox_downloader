package com.oxclub.universaldownloader.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oxclub.universaldownloader.UniversalDownloaderApp

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val manager = (context.applicationContext as UniversalDownloaderApp).container.downloadManager
        when (intent.action) {
            ACTION_PAUSE -> manager.pause(id)
            ACTION_RESUME -> manager.resume(id)
            ACTION_CANCEL -> manager.cancel(id)
        }
    }

    companion object {
        const val EXTRA_ID = "download_id"
        const val ACTION_PAUSE = "com.oxclub.universaldownloader.PAUSE"
        const val ACTION_RESUME = "com.oxclub.universaldownloader.RESUME"
        const val ACTION_CANCEL = "com.oxclub.universaldownloader.CANCEL"
    }
}
