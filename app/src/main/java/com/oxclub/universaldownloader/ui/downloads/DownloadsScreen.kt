@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.ads.AdPlacement
import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.BannerSlot
import com.oxclub.universaldownloader.ui.components.DownloadCard
import com.oxclub.universaldownloader.ui.components.DownloadItem
import com.oxclub.universaldownloader.ui.components.EmptyState
import com.oxclub.universaldownloader.ui.components.TextInputDialog
import com.oxclub.universaldownloader.ui.components.categoryLabel
import com.oxclub.universaldownloader.PendingRequest
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DownloadsViewModel(private val c: AppContainer, initialCategory: FileCategory?) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(StatusFilter.ALL)
    val sort = MutableStateFlow(SortOrder.NEWEST)
    val category = MutableStateFlow(initialCategory)

    val items: StateFlow<List<DownloadItem>> = combine(
        c.repository.observeAll(), c.downloadManager.live, query, filter, sort, category,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<com.oxclub.universaldownloader.data.database.DownloadEntity>
        @Suppress("UNCHECKED_CAST")
        val live = values[1] as Map<Long, com.oxclub.universaldownloader.domain.model.LiveProgress>
        val shown = DownloadFilter.apply(all, values[2] as String, values[3] as StatusFilter, values[5] as FileCategory?, values[4] as SortOrder)
        shown.map { e -> DownloadItem(e, if (e.status == com.oxclub.universaldownloader.domain.model.DownloadStatus.DOWNLOADING) live[e.id] else null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pauseAll() = c.downloadManager.pauseAll()
    fun resumeAll() = c.downloadManager.resumeAll()
    fun cancelAll() = c.downloadManager.cancelAll()
    fun clearCompleted() = c.downloadManager.clearCompleted()

    /** Validates a URL typed into the add dialog and hands it to the analysis screen. */
    fun submit(text: String, onReady: () -> Unit, onInvalid: (Int) -> Unit) {
        viewModelScope.launch {
            val s = c.settings.current()
            val candidate = UrlUtils.extractUrls(text).firstOrNull() ?: text
            when (val r = UrlUtils.normalize(candidate, s.allowLocalTargets)) {
                is UrlCheck.Invalid -> onInvalid(com.oxclub.universaldownloader.utils.ErrorMessages.problemRes(r.problem))
                is UrlCheck.Valid -> {
                    c.pendingRequest.request = PendingRequest(r.url.toString())
                    onReady()
                }
            }
        }
    }
}

private val TABS = listOf(
    StatusFilter.ALL to R.string.tab_all,
    StatusFilter.DOWNLOADING to R.string.tab_downloading,
    StatusFilter.COMPLETED to R.string.tab_finished,
    StatusFilter.FAILED to R.string.tab_error,
    StatusFilter.PAUSED to R.string.tab_paused,
)

@Composable
fun DownloadsScreen(category: String?, onAnalyze: () -> Unit, onOpenDetails: (Long) -> Unit) {
    val initial = category?.let { name -> FileCategory.entries.firstOrNull { it.name == name } }
    val vm = appViewModel(key = "downloads-$category") { DownloadsViewModel(it, initial) }
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = rememberActionController(onMessage = { scope.launch { snackbar.showSnackbar(it) } }, onDetails = onOpenDetails)
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            modifier = Modifier.fillMaxWidth().testTag("search_field"),
                        )
                    } else {
                        Text(stringResource(R.string.nav_downloads))
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            modifier = Modifier.testTag("search_toggle"),
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for ((order, label) in listOf(
                                SortOrder.NEWEST to R.string.sort_newest, SortOrder.OLDEST to R.string.sort_oldest,
                                SortOrder.NAME to R.string.sort_name, SortOrder.SIZE to R.string.sort_size,
                                SortOrder.PROGRESS to R.string.sort_progress,
                            )) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { if (order == sort) Icon(Icons.Filled.Check, contentDescription = null) },
                                    onClick = { vm.sort.value = order; sortMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pause_all)) }, onClick = { moreMenu = false; vm.pauseAll() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.resume_all)) }, onClick = { moreMenu = false; vm.resumeAll() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.cancel_all)) }, onClick = { moreMenu = false; vm.cancelAll() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.clear_completed)) }, onClick = { moreMenu = false; vm.clearCompleted() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_download))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BannerSlot(AdPlacement.DOWNLOADS_BOTTOM) },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = TABS.indexOfFirst { it.first == filter }.coerceAtLeast(0), edgePadding = 8.dp) {
                for ((f, label) in TABS) {
                    Tab(selected = f == filter, onClick = { vm.filter.value = f }, text = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("tab_${f.name}"))
                }
            }
            cat?.let { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = { vm.category.value = null },
                        label = { Text(stringResource(categoryLabel(c))) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear)) },
                    )
                }
            }
            if (items.isEmpty()) {
                val empty = when {
                    query.isNotBlank() -> Triple(Icons.Filled.Search, R.string.empty_search_title, R.string.empty_search_message)
                    filter == StatusFilter.FAILED -> Triple(Icons.Filled.Error, R.string.empty_errors_title, R.string.empty_errors_message)
                    filter == StatusFilter.COMPLETED -> Triple(Icons.Filled.Download, R.string.empty_finished_title, R.string.empty_finished_message)
                    else -> Triple(Icons.Filled.Download, R.string.empty_downloads_title, R.string.empty_downloads_message)
                }
                EmptyState(empty.first, empty.second, empty.third, Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp, top = 4.dp)) {
                    items(items, key = { it.entity.id }) { item ->
                        DownloadCard(
                            item = item,
                            onClick = { onOpenDetails(item.entity.id) },
                            onAction = { actions.perform(it, item.entity) },
                        )
                    }
                }
            }
        }
    }
    if (showAdd) {
        TextInputDialog(
            title = R.string.add_download_title,
            initial = "",
            label = R.string.url_hint,
            onDismiss = { showAdd = false },
            onConfirm = { text ->
                showAdd = false
                vm.submit(text, onReady = onAnalyze, onInvalid = { res -> scope.launch { snackbar.showSnackbar(context.getString(res)) } })
            },
        )
    }
    DownloadActionDialogs(actions)
}
