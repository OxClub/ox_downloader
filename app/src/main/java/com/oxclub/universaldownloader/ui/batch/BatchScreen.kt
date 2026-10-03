@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.preferences.AppSettings
import com.oxclub.universaldownloader.domain.model.AnalysisResult
import com.oxclub.universaldownloader.downloader.EnqueueResult
import com.oxclub.universaldownloader.downloader.NewDownload
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.utils.ErrorMessages
import com.oxclub.universaldownloader.utils.Formatters
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BatchKind { ADDED, SKIPPED, PAGE, ERROR }
data class BatchResult(val url: String, val kind: BatchKind, val message: String)

class BatchViewModel(private val c: AppContainer) : ViewModel() {
    var text by mutableStateOf(c.pendingRequest.batchUrls.joinToString("\n"))
    var running by mutableStateOf(false)
    var done by mutableStateOf(false)
    val results = mutableStateListOf<BatchResult>()

    init { c.pendingRequest.batchUrls = emptyList() }

    fun importFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    c.app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(MAX_IMPORT_CHARS) }
                } catch (e: Exception) {
                    null
                }
            }
            if (content != null) {
                val urls = UrlUtils.extractUrls(content)
                text = (text.trim().lines().filter { it.isNotBlank() } + urls).joinToString("\n")
            }
        }
    }

    fun run() {
        viewModelScope.launch {
            running = true
            done = false
            results.clear()
            val s = c.settings.current()
            val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .map { line -> UrlUtils.extractUrls(line).firstOrNull() ?: line }.distinct()
            urls.chunked(3).forEach { chunk ->
                chunk.map { async { process(it, s) } }.awaitAll().forEach { results.add(it) }
            }
            running = false
            done = true
        }
    }

    private suspend fun process(raw: String, s: AppSettings): BatchResult {
        val check = UrlUtils.normalize(raw, s.allowLocalTargets)
        if (check is UrlCheck.Invalid) return BatchResult(raw, BatchKind.ERROR, c.app.getString(ErrorMessages.problemRes(check.problem)))
        val url = (check as UrlCheck.Valid).url.toString()
        return try {
            when (val r = c.analyzer.analyze(url, emptyMap(), s.netConfig())) {
                is AnalysisResult.Direct -> {
                    val p = r.probe
                    val res = c.downloadManager.enqueue(
                        NewDownload(
                            url = url, filename = r.resource.filename, finalUrl = p.finalUrl, mimeType = r.resource.mimeType,
                            totalBytes = r.resource.sizeBytes, etag = p.etag, lastModified = p.lastModified,
                            supportsResume = p.supportsRanges && p.contentLength > 0, server = p.server,
                        ),
                    )
                    when (res) {
                        is EnqueueResult.Added -> BatchResult(url, BatchKind.ADDED, r.resource.filename)
                        is EnqueueResult.Duplicate -> BatchResult(url, BatchKind.SKIPPED, c.app.getString(R.string.already_in_downloads))
                        EnqueueResult.SkippedExisting -> BatchResult(url, BatchKind.SKIPPED, c.app.getString(R.string.skipped_exists))
                        is EnqueueResult.InsufficientStorage -> BatchResult(
                            url, BatchKind.ERROR,
                            c.app.getString(R.string.err_storage, Formatters.size(res.required), Formatters.size(res.available)),
                        )
                    }
                }
                is AnalysisResult.Page -> BatchResult(url, BatchKind.PAGE, c.app.getString(R.string.batch_page))
                is AnalysisResult.Manifest -> BatchResult(url, BatchKind.PAGE, c.app.getString(R.string.manifest_detected))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BatchResult(url, BatchKind.ERROR, ErrorMessages.describe(c.app, e))
        }
    }

    companion object {
        private const val MAX_IMPORT_CHARS = 1_000_000
    }
}

@Composable
fun BatchScreen(onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val vm = appViewModel { BatchViewModel(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.text,
                onValueChange = { vm.text = it },
                label = { Text(stringResource(R.string.batch_hint)) },
                minLines = 5,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.running,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { picker.launch(arrayOf("text/*")) }, enabled = !vm.running, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.batch_import))
                }
                Button(onClick = { vm.run() }, enabled = !vm.running && vm.text.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.batch_start))
                }
            }
            if (vm.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(vm.results) { _, r ->
                    ListItem(
                        leadingContent = {
                            when (r.kind) {
                                BatchKind.ADDED -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                BatchKind.ERROR -> Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            }
                        },
                        headlineContent = { Text(r.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(r.message, maxLines = 3) },
                    )
                }
            }
            if (vm.done && vm.results.any { it.kind == BatchKind.ADDED }) {
                Button(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.batch_view_downloads)) }
            }
        }
    }
}
