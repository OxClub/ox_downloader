@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.PendingRequest
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.ads.AdPlacement
import com.oxclub.universaldownloader.data.preferences.Keys
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.BannerSlot
import com.oxclub.universaldownloader.ui.components.DownloadCard
import com.oxclub.universaldownloader.ui.components.DownloadItem
import com.oxclub.universaldownloader.ui.components.EmptyState
import com.oxclub.universaldownloader.ui.downloads.DownloadActionDialogs
import com.oxclub.universaldownloader.ui.downloads.rememberActionController
import com.oxclub.universaldownloader.utils.ErrorMessages
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlProblem
import com.oxclub.universaldownloader.utils.UrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    var input by mutableStateOf("")
    var problem by mutableStateOf<UrlProblem?>(null)
    var clipboardUrl by mutableStateOf<String?>(null)

    val recent: StateFlow<List<DownloadItem>> = combine(c.repository.observeAll(), c.downloadManager.live) { list, live ->
        list.take(5).map { DownloadItem(it, live[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Checks the clipboard for a URL (once per distinct URL) when the app comes to the foreground. */
    fun checkClipboard() {
        viewModelScope.launch {
            delay(350) // Android only lets the focused app read the clipboard
            val s = c.settings.current()
            if (!s.clipboardDetection) {
                clipboardUrl = null
                return@launch
            }
            val cm = c.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c.app)?.toString()
            val url = text?.let { UrlUtils.extractUrls(it).firstOrNull() }
            clipboardUrl = if (url != null && url != s.lastClipboardUrl && url != input.trim()) url else null
        }
    }

    fun ignoreClipboard() {
        val url = clipboardUrl ?: return
        clipboardUrl = null
        viewModelScope.launch { c.settings.set(Keys.LAST_CLIPBOARD, url) }
    }

    fun readClipboardText(): String? {
        val cm = c.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c.app)?.toString() ?: return null
        return UrlUtils.extractUrls(text).firstOrNull() ?: text.trim()
    }

    fun paste() {
        readClipboardText()?.let { input = it; problem = null }
    }

    /** Validates [text]; on success stores the request and calls [onReady] to open the analysis screen. */
    fun submit(text: String, quick: Boolean, onReady: () -> Unit) {
        viewModelScope.launch {
            val s = c.settings.current()
            val candidate = UrlUtils.extractUrls(text).firstOrNull() ?: text
            when (val r = UrlUtils.normalize(candidate, s.allowLocalTargets)) {
                is UrlCheck.Invalid -> problem = r.problem
                is UrlCheck.Valid -> {
                    problem = null
                    val url = r.url.toString()
                    c.pendingRequest.request = PendingRequest(url, quick = quick)
                    c.settings.set(Keys.LAST_CLIPBOARD, url)
                    clipboardUrl = null
                    onReady()
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    onAnalyze: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onOpenBatch: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = rememberActionController(onMessage = { scope.launch { snackbar.showSnackbar(it) } }, onDetails = onOpenDetails)
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.checkClipboard() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { listState.animateScrollToItem(0); focus.requestFocus() } }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_enter_url))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.batch_title)) }, onClick = { menu = false; onOpenBatch() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.storage_title)) }, onClick = { menu = false; onOpenStorage() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.help_title)) }, onClick = { menu = false; onOpenHelp() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.about_title)) }, onClick = { menu = false; onOpenAbout() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0); focus.requestFocus() } }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_download))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BannerSlot(AdPlacement.HOME_BOTTOM) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
        ) {
            item {
                val problem = vm.problem
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.paste_url_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        OutlinedTextField(
                            value = vm.input,
                            onValueChange = { vm.input = it; vm.problem = null },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus).testTag("url_input"),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.url_hint)) },
                            isError = vm.problem != null,
                            supportingText = if (problem != null) {
                                { Text(stringResource(ErrorMessages.problemRes(problem))) }
                            } else null,
                            trailingIcon = {
                                if (vm.input.isNotEmpty()) {
                                    IconButton(onClick = { vm.input = ""; vm.problem = null }) {
                                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear))
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { vm.submit(vm.input, quick = false, onReady = onAnalyze) }),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.paste() }, modifier = Modifier.weight(1f).testTag("btn_paste")) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_paste), maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = { vm.submit(vm.input, quick = false, onReady = onAnalyze) },
                                modifier = Modifier.weight(1f).testTag("btn_analyze"),
                            ) { Text(stringResource(R.string.action_analyze), maxLines = 1) }
                            Button(
                                onClick = { vm.submit(vm.input, quick = true, onReady = onAnalyze) },
                                modifier = Modifier.weight(1f).testTag("btn_download"),
                            ) { Text(stringResource(R.string.action_download), maxLines = 1) }
                        }
                    }
                }
            }
            vm.clipboardUrl?.let { url ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.clipboard_detected), style = MaterialTheme.typography.titleSmall)
                            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.ignoreClipboard() }) { Text(stringResource(R.string.action_ignore)) }
                                TextButton(onClick = { vm.submit(url, quick = false, onReady = onAnalyze) }) {
                                    Text(stringResource(R.string.action_download))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.recent_downloads), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (recent.isNotEmpty()) TextButton(onClick = onOpenDownloads) { Text(stringResource(R.string.action_view_all)) }
                }
            }
            if (recent.isEmpty()) {
                item {
                    EmptyState(Icons.Filled.Download, R.string.empty_downloads_title, R.string.empty_downloads_message)
                }
            } else {
                items(recent, key = { it.entity.id }) { item ->
                    DownloadCard(
                        item = item,
                        onClick = { onOpenDetails(item.entity.id) },
                        onAction = { actions.perform(it, item.entity) },
                    )
                }
            }
        }
    }
    DownloadActionDialogs(actions)
}
