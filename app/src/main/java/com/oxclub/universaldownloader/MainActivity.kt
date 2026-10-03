package com.oxclub.universaldownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.oxclub.universaldownloader.ui.AppRoot
import com.oxclub.universaldownloader.ui.LocalAppContainer
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlUtils

class MainActivity : AppCompatActivity() {
    private val container get() = (application as UniversalDownloaderApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Android Share -> Universal Downloader: extracts URLs from the shared text. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        var urls = UrlUtils.extractUrls(text)
        if (urls.isEmpty()) {
            val check = UrlUtils.normalize(text, allowLocal = true)
            if (check is UrlCheck.Valid) urls = listOf(check.url.toString())
        }
        if (urls.isNotEmpty()) container.incomingUrls.value = urls
        intent.action = null
    }
}
