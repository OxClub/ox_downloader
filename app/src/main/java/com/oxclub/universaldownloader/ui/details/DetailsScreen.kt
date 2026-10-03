@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.details

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.DownloadAction
import com.oxclub.universaldownloader.ui.components.DownloadItem
import com.oxclub.universaldownloader.ui.components.statusLabel
import com.oxclub.universaldownloader.ui.downloads.DownloadActionDialogs
import com.oxclub.universaldownloader.ui.downloads.rememberActionController
import com.oxclub.universaldownloader.utils.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailsViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val item: StateFlow<DownloadItem?> = combine(c.repository.observe(id), c.downloadManager.live) { e, live ->
        e?.let { DownloadItem(it, if (it.status == DownloadStatus.DOWNLOADING) live[it.id] else null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun move(treeUri: String): Boolean = c.downloadManager.move(id, treeUri)
    fun describeTree(uri: String) = c.fileStore.describeTree(uri)
}

@Composable
fun DetailsScreen(id: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "details-$id") { DetailsViewModel(it, id) }
    val item by vm.item.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = rememberActionController(onMessage = { scope.launch { snackbar.showSnackbar(it) } }, onDetails = {})
    val movedMessage = stringResource(R.string.moved_ok)
    val moveFailed = stringResource(R.string.err_move_failed)

    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { snackbar.showSnackbar(if (vm.move(uri.toString())) movedMessage else moveFailed) }
        }
    }

    // Deleting from here leaves the (now missing) screen.
    val current = item
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.details_missing))
            }
        } else {
            DetailsBody(
                item = current,
                modifier = Modifier.padding(padding),
                onAction = { a ->
                    actions.perform(a, current.entity)
                },
                onMove = { moveLauncher.launch(null) },
            )
        }
    }
    DownloadActionDialogs(actions)
}

@Composable
private fun DetailsBody(item: DownloadItem, modifier: Modifier, onAction: (DownloadAction) -> Unit, onMove: () -> Unit) {
    val e = item.entity
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(e.filename, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(statusLabel(e.status)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        e.errorMessage?.let { Text(it, color = if (e.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val f = item.fraction
            if (f != null) {
                CircularProgressIndicator(progress = { f }, modifier = Modifier.size(120.dp), strokeWidth = 10.dp)
                Text("${Formatters.percent(item.bytes, item.total)}%", style = MaterialTheme.typography.headlineSmall)
            } else if (e.status.isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(120.dp), strokeWidth = 10.dp)
            }
        }
        Spacer(Modifier.height(16.dp))

        val sizeText = if (item.total > 0) stringResource(R.string.size_of, Formatters.size(item.bytes), Formatters.size(item.total))
        else stringResource(R.string.downloaded_unknown, Formatters.size(item.bytes)) + " • " + stringResource(R.string.size_unknown)
        InfoLine(R.string.label_downloaded, sizeText)
        item.live?.let { live ->
            InfoLine(R.string.label_speed, Formatters.speed(live.speedBytesPerSec))
            InfoLine(
                R.string.label_eta,
                live.etaSeconds?.let { Formatters.duration(it) } ?: stringResource(R.string.eta_unavailable),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        InfoLine(R.string.label_url, e.url)
        e.finalUrl?.takeIf { it != e.url }?.let { InfoLine(R.string.label_final_url, it) }
        InfoLine(R.string.label_destination, destinationText(e))
        e.server?.let { InfoLine(R.string.label_server, it) }
        e.mimeType?.let { InfoLine(R.string.label_content_type, it) }
        InfoLine(R.string.label_resume, stringResource(if (e.supportsResume) R.string.supported else R.string.not_supported))
        InfoLine(R.string.label_parts, if (e.supportsResume) e.partCount.toString() else "1")
        InfoLine(R.string.label_created, Formatters.dateTime(e.createdAt))
        e.startedAt?.let { InfoLine(R.string.label_started, Formatters.dateTime(it)) }
        e.completedAt?.let { done ->
            InfoLine(R.string.label_completed, Formatters.dateTime(done))
            if (e.activeMillis > 0) {
                InfoLine(R.string.label_duration, Formatters.duration(e.activeMillis / 1000))
                InfoLine(R.string.label_avg_speed, Formatters.speed(e.totalBytes * 1000.0 / e.activeMillis))
            }
        }
        if (e.checksumVerified) InfoLine(R.string.label_checksum, stringResource(R.string.checksum_verified, e.checksum?.substringBefore(':')?.uppercase() ?: ""))
        Spacer(Modifier.height(16.dp))

        val primary = when (e.status) {
            DownloadStatus.QUEUED, DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING -> listOf(DownloadAction.PAUSE, DownloadAction.CANCEL)
            DownloadStatus.PAUSED -> listOf(DownloadAction.RESUME, DownloadAction.CANCEL)
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> listOf(DownloadAction.RETRY)
            else -> emptyList()
        }
        if (primary.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary.forEachIndexed { index, a ->
                    if (index == 0) {
                        Button(onClick = { onAction(a) }, modifier = Modifier.weight(1f)) { Text(stringResource(com.oxclub.universaldownloader.ui.components.actionLabel(a))) }
                    } else {
                        OutlinedButton(onClick = { onAction(a) }, modifier = Modifier.weight(1f)) { Text(stringResource(com.oxclub.universaldownloader.ui.components.actionLabel(a))) }
                    }
                }
            }
        }
        if (e.status == DownloadStatus.COMPLETED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(DownloadAction.OPEN) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_open)) }
                OutlinedButton(onClick = { onAction(DownloadAction.SHARE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_share)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAction(DownloadAction.RENAME) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onMove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_move)) }
            }
        }
        if (e.status != DownloadStatus.VERIFYING) {
            TextButton(onClick = { onAction(DownloadAction.DELETE) }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InfoLine(label: Int, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun destinationText(e: DownloadEntity): String {
    val uri = e.destinationUri
    return when {
        e.status != DownloadStatus.COMPLETED || uri == null -> FileStore.DEFAULT_LABEL
        uri.startsWith("content://media/") -> FileStore.DEFAULT_LABEL
        else -> android.net.Uri.decode(uri).substringAfter("/document/", uri).substringBeforeLast('/')
            .replace(':', '/')
    }
}
