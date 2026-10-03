@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.domain.model.AnalysisResult
import com.oxclub.universaldownloader.domain.model.DiscoveredResource
import com.oxclub.universaldownloader.domain.model.ProbeResult
import com.oxclub.universaldownloader.domain.model.StreamKind
import com.oxclub.universaldownloader.downloader.EnqueueResult
import com.oxclub.universaldownloader.downloader.NewDownload
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.CategoryBadge
import com.oxclub.universaldownloader.ui.components.MessageDialog
import com.oxclub.universaldownloader.utils.Checksums
import com.oxclub.universaldownloader.utils.ErrorMessages
import com.oxclub.universaldownloader.utils.Formatters
import com.oxclub.universaldownloader.utils.UrlCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface AnalysisUiState {
    data object Loading : AnalysisUiState
    data class Failure(val message: String, val needsLogin: Boolean) : AnalysisUiState
    data class Direct(val probe: ProbeResult, val resource: DiscoveredResource) : AnalysisUiState
    data class ConfirmExtract(val probe: ProbeResult) : AnalysisUiState
    data class Page(
        val probe: ProbeResult,
        val title: String?,
        val resources: List<DiscoveredResource>,
        val selected: Set<String>,
        val incomplete: Boolean,
    ) : AnalysisUiState
    data class Manifest(val probe: ProbeResult, val kind: StreamKind) : AnalysisUiState
}

sealed interface AnalysisEvent {
    data object Done : AnalysisEvent
    data class Message(val text: String) : AnalysisEvent
}

class AnalysisViewModel(private val c: AppContainer) : ViewModel() {
    private val request = c.pendingRequest.request
    private val _state = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Loading)
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()
    private val _events = Channel<AnalysisEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    var checksumInput by mutableStateOf("")
    var dialogMessage by mutableStateOf<String?>(null)
    val url: String get() = request?.url.orEmpty()

    private var job: Job? = null

    init { analyze() }

    private fun headers() = request?.headers().orEmpty()

    fun analyze() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = AnalysisUiState.Loading
            val req = request
            if (req == null) {
                _state.value = AnalysisUiState.Failure(c.app.getString(R.string.err_no_request), false)
                return@launch
            }
            try {
                val s = c.settings.current()
                val check = UrlUtils_normalize(req.url, s.allowLocalTargets)
                if (check is UrlCheck.Invalid) {
                    _state.value = AnalysisUiState.Failure(c.app.getString(ErrorMessages.problemRes(check.problem)), false)
                    return@launch
                }
                when (val result = c.analyzer.analyze(req.url, headers(), s.netConfig())) {
                    is AnalysisResult.Direct -> {
                        if (req.quick && !s.askBeforeDownload) {
                            enqueueDirect(result.probe, result.resource)
                        } else {
                            _state.value = AnalysisUiState.Direct(result.probe, result.resource)
                        }
                    }
                    is AnalysisResult.Page ->
                        if (s.askBeforeExtract) _state.value = AnalysisUiState.ConfirmExtract(result.probe) else loadPage(result.probe)
                    is AnalysisResult.Manifest -> _state.value = AnalysisUiState.Manifest(result.probe, result.kind)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = AnalysisUiState.Failure(
                    ErrorMessages.describe(c.app, e),
                    needsLogin = e is HttpStatusException && (e.code == 401 || e.code == 403),
                )
            }
        }
    }

    private fun UrlUtils_normalize(url: String, allowLocal: Boolean) = com.oxclub.universaldownloader.utils.UrlUtils.normalize(url, allowLocal)

    fun extractFromPage() {
        val st = _state.value as? AnalysisUiState.ConfirmExtract ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = AnalysisUiState.Loading
            try {
                loadPage(st.probe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = AnalysisUiState.Failure(ErrorMessages.describe(c.app, e), false)
            }
        }
    }

    private suspend fun loadPage(probe: ProbeResult) {
        val s = c.settings.current()
        val page = c.analyzer.loadPage(probe, headers(), s.netConfig())
        val selectable = page.resources.filter { it.streamKind == null }.map { it.url }.toSet()
        _state.value = AnalysisUiState.Page(probe, page.title, page.resources, selectable, page.maybeIncomplete)
        enrich(page.resources.filter { it.streamKind == null }.take(MAX_PROBED), s.netConfig())
    }

    private fun enrich(resources: List<DiscoveredResource>, cfg: com.oxclub.universaldownloader.domain.model.NetConfig) {
        viewModelScope.launch {
            val gate = Semaphore(6)
            resources.map { r ->
                async {
                    gate.withPermit {
                        val updated = try {
                            c.analyzer.enrich(r, headers(), cfg)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            r.copy(probed = true)
                        }
                        _state.update { st ->
                            if (st is AnalysisUiState.Page) {
                                st.copy(resources = st.resources.map { if (it.url == updated.url) updated else it })
                            } else st
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun toggle(url: String) {
        _state.update { st ->
            if (st is AnalysisUiState.Page) {
                st.copy(selected = if (url in st.selected) st.selected - url else st.selected + url)
            } else st
        }
    }

    fun selectAll(all: Boolean) {
        _state.update { st ->
            if (st is AnalysisUiState.Page) {
                st.copy(selected = if (all) st.resources.filter { it.streamKind == null }.map { it.url }.toSet() else emptySet())
            } else st
        }
    }

    private fun newDownload(r: DiscoveredResource, probe: ProbeResult?, checksum: String?) = NewDownload(
        url = r.url,
        filename = r.filename,
        finalUrl = probe?.finalUrl,
        mimeType = r.mimeType,
        totalBytes = r.sizeBytes,
        etag = probe?.etag,
        lastModified = probe?.lastModified,
        supportsResume = probe?.let { it.supportsRanges && it.contentLength > 0 } ?: false,
        server = probe?.server,
        checksum = checksum,
        cookie = request?.cookie,
        referer = request?.referer,
        userAgent = request?.userAgent,
    )

    private suspend fun enqueueDirect(probe: ProbeResult, r: DiscoveredResource) {
        val checksum = if (checksumInput.isBlank()) null else Checksums.parse(checksumInput)
        if (checksumInput.isNotBlank() && checksum == null) {
            dialogMessage = c.app.getString(R.string.err_checksum_format)
            return
        }
        report(listOf(c.downloadManager.enqueue(newDownload(r, probe, checksum))))
    }

    fun downloadDirect() {
        val st = _state.value as? AnalysisUiState.Direct ?: return
        viewModelScope.launch { enqueueDirect(st.probe, st.resource) }
    }

    fun downloadSelected() {
        val st = _state.value as? AnalysisUiState.Page ?: return
        enqueueMany(st.resources.filter { it.url in st.selected && it.streamKind == null }, st.probe)
    }

    fun downloadAll() {
        val st = _state.value as? AnalysisUiState.Page ?: return
        enqueueMany(st.resources.filter { it.streamKind == null }, st.probe)
    }

    private fun enqueueMany(list: List<DiscoveredResource>, pageProbe: ProbeResult) {
        viewModelScope.launch {
            val results = list.map { c.downloadManager.enqueue(newDownload(it, null, null).copy(referer = request?.referer ?: pageProbe.finalUrl)) }
            report(results)
        }
    }

    private suspend fun report(results: List<EnqueueResult>) {
        val added = results.count { it is EnqueueResult.Added }
        val storage = results.filterIsInstance<EnqueueResult.InsufficientStorage>().firstOrNull()
        if (storage != null && added == 0) {
            dialogMessage = c.app.getString(
                R.string.err_storage, Formatters.size(storage.required), Formatters.size(storage.available),
            )
            return
        }
        if (added == 0) {
            val msg = if (results.any { it is EnqueueResult.Duplicate }) R.string.already_in_downloads else R.string.skipped_exists
            _events.send(AnalysisEvent.Message(c.app.getString(msg)))
            return
        }
        _events.send(AnalysisEvent.Done)
    }

    fun openInBrowser() {
        c.browserRequest.value = request?.url
    }

    companion object {
        private const val MAX_PROBED = 60
    }
}

@Composable
fun AnalysisScreen(onBack: () -> Unit, onDone: () -> Unit, onOpenBrowser: () -> Unit) {
    val vm = appViewModel { AnalysisViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                AnalysisEvent.Done -> onDone()
                is AnalysisEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analysis_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val st = state) {
                AnalysisUiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.analyzing_url))
                    Text(vm.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                }
                is AnalysisUiState.Failure -> FailureView(st, vm, onBack, onOpenBrowser)
                is AnalysisUiState.Direct -> DirectView(st, vm)
                is AnalysisUiState.ConfirmExtract -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(st.probe)
                    Text(stringResource(R.string.confirm_extract), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.extractFromPage() }) { Text(stringResource(R.string.action_yes)) }
                        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                is AnalysisUiState.Manifest -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(st.probe)
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.manifest_detected), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.manifest_explained), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is AnalysisUiState.Page -> PageView(st, vm)
            }
        }
    }
    vm.dialogMessage?.let { MessageDialog(R.string.dialog_cannot_start, it) { vm.dialogMessage = null } }
}

@Composable
private fun FailureView(st: AnalysisUiState.Failure, vm: AnalysisViewModel, onBack: () -> Unit, onOpenBrowser: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(st.message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.analyze() }) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cd_back)) }
        }
        if (st.needsLogin) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.openInBrowser(); onOpenBrowser() }) { Text(stringResource(R.string.open_in_browser)) }
        }
    }
}

@Composable
private fun InfoCard(p: ProbeResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.analysis_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            InfoRow(R.string.label_original_url, p.originalUrl)
            if (p.finalUrl != p.originalUrl) InfoRow(R.string.label_final_url, p.finalUrl)
            p.server?.let { InfoRow(R.string.label_server, it) }
            p.contentType?.let { InfoRow(R.string.label_content_type, it) }
            InfoRow(R.string.label_resume, stringResource(if (p.supportsRanges) R.string.supported else R.string.not_supported))
        }
    }
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DirectView(st: AnalysisUiState.Direct, vm: AnalysisViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.file_found), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(st.resource.category)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(st.resource.filename, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = if (st.resource.sizeBytes >= 0) Formatters.size(st.resource.sizeBytes) else stringResource(R.string.size_unknown)
                            Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!st.probe.supportsRanges) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.no_resume_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = vm.checksumInput,
                        onValueChange = { vm.checksumInput = it },
                        label = { Text(stringResource(R.string.checksum_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.downloadDirect() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_download_caps))
                    }
                }
            }
        }
        item { InfoCard(st.probe) }
    }
}

@Composable
private fun PageView(st: AnalysisUiState.Page, vm: AnalysisViewModel) {
    val selectable = st.resources.filter { it.streamKind == null }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
            item { Column(Modifier.padding(16.dp)) { InfoCard(st.probe) } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.discovered_resources, st.resources.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectable.isNotEmpty()) {
                        TextButton(onClick = { vm.selectAll(st.selected.size < selectable.size) }) {
                            Text(stringResource(if (st.selected.size < selectable.size) R.string.select_all else R.string.select_none))
                        }
                    }
                }
            }
            if (st.resources.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_resources), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(st.resources, key = { it.url }) { r ->
                val isManifest = r.streamKind != null
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !isManifest) { vm.toggle(r.url) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = r.url in st.selected, onCheckedChange = { vm.toggle(r.url) }, enabled = !isManifest)
                    CategoryBadge(r.category)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        val size = when {
                            isManifest -> stringResource(R.string.manifest_detected)
                            r.sizeBytes >= 0 -> Formatters.size(r.sizeBytes)
                            r.probed -> stringResource(R.string.size_unknown)
                            else -> stringResource(R.string.size_checking)
                        }
                        Text(
                            listOfNotNull(size, r.mimeType).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }
            if (st.incomplete) {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.resources_incomplete), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (selectable.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.downloadAll() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.download_all)) }
                Button(onClick = { vm.downloadSelected() }, enabled = st.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.download_selected, st.selected.size))
                }
            }
        }
    }
}
