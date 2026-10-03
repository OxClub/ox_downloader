@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oxclub.universaldownloader.PendingRequest
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.ui.LocalAppContainer
import com.oxclub.universaldownloader.utils.Formatters
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlUtils

private const val HOME_URL = "https://duckduckgo.com"

private data class DetectedDownload(
    val url: String,
    val name: String,
    val size: Long,
    val mime: String?,
    val userAgent: String?,
    val pageUrl: String?,
    val cookie: String?,
)

private fun toBrowserUrl(input: String): String {
    val t = input.trim()
    UrlUtils.extractUrls(t).firstOrNull()?.let { return it }
    val check = UrlUtils.normalize(t, allowLocal = true)
    return if (check is UrlCheck.Valid && !t.contains(' ')) check.url.toString()
    else "https://duckduckgo.com/?q=" + Uri.encode(t)
}

/**
 * Built-in browser. It only observes downloads the page itself starts; it does not bypass site
 * protections. When the user starts a download from a page they are signed in to, that browser
 * session's cookie is sent with the request (stored privately, never logged, wiped on completion).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onDownload: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var urlText by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<DetectedDownload?>(null) }
    val requested by container.browserRequest.collectAsStateWithLifecycle()

    BackHandler(enabled = canBack) { webView?.goBack() }

    LaunchedEffect(requested, webView) {
        val target = requested
        val view = webView
        if (target != null && view != null) {
            view.loadUrl(target)
            container.browserRequest.value = null
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { webView?.goBack() }, enabled = canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            IconButton(onClick = { webView?.goForward() }, enabled = canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.cd_forward))
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_reload))
            }
            IconButton(onClick = { webView?.loadUrl(HOME_URL) }) {
                Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.nav_home))
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, urlText)
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
            }
        }
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text(stringResource(R.string.browser_url_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { webView?.loadUrl(toBrowserUrl(urlText)) }),
        )
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

        detected?.let { d ->
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.download_available), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (d.size > 0) Formatters.size(d.size) else null,
                            d.mime,
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { detected = null }) { Text(stringResource(R.string.action_ignore)) }
                        Button(onClick = {
                            container.pendingRequest.request = PendingRequest(
                                url = d.url, cookie = d.cookie, referer = d.pageUrl, userAgent = d.userAgent,
                            )
                            detected = null
                            onDownload()
                        }) { Text(stringResource(R.string.action_download_caps)) }
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            urlText = url
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            urlText = url
                            canBack = view.canGoBack()
                            canForward = view.canGoForward()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                        detected = DetectedDownload(
                            url = url,
                            name = URLUtil.guessFileName(url, contentDisposition, mimeType),
                            size = contentLength,
                            mime = mimeType,
                            userAgent = userAgent,
                            pageUrl = this.url,
                            cookie = CookieManager.getInstance().getCookie(url),
                        )
                    }
                    val start = container.browserRequest.value ?: HOME_URL
                    container.browserRequest.value = null
                    loadUrl(start)
                    webView = this
                }
            },
            onRelease = {
                it.stopLoading()
                it.destroy()
                webView = null
            },
        )
    }
}
