@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.settings

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.utils.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageInfo(val total: Long, val free: Long, val folderFiles: Int, val folderBytes: Long, val location: String) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

class StorageViewModel(private val c: AppContainer) : ViewModel() {
    private val _info = MutableStateFlow<StorageInfo?>(null)
    val info: StateFlow<StorageInfo?> = _info.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val stat = withContext(Dispatchers.IO) { StatFs(Environment.getDataDirectory().path) }
            val (count, bytes) = c.fileStore.folderStats()
            val tree = c.settings.current().downloadTreeUri
            _info.value = StorageInfo(
                total = stat.totalBytes,
                free = stat.availableBytes,
                folderFiles = count,
                folderBytes = bytes,
                location = tree?.let { c.fileStore.describeTree(it) } ?: FileStore.DEFAULT_LABEL,
            )
        }
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit) {
    val vm = appViewModel { StorageViewModel(it) }
    val info by vm.info.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_reload))
                    }
                },
            )
        },
    ) { padding ->
        val i = info
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (i == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.internal_storage), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { if (i.total > 0) i.used.toFloat() / i.total else 0f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Line(R.string.storage_total, Formatters.size(i.total))
                        Line(R.string.storage_used, Formatters.size(i.used))
                        Line(R.string.storage_free, Formatters.size(i.free))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.setting_location), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Line(R.string.storage_folder, i.location)
                        Line(R.string.storage_folder_size, Formatters.size(i.folderBytes))
                        Line(R.string.storage_file_count, i.folderFiles.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(label: Int, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
