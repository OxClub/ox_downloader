@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.Preferences
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.ads.AdPlacement
import com.oxclub.universaldownloader.data.preferences.AppSettings
import com.oxclub.universaldownloader.data.preferences.DEFAULT_USER_AGENT
import com.oxclub.universaldownloader.data.preferences.Keys
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.domain.model.AccentColor
import com.oxclub.universaldownloader.domain.model.DuplicatePolicy
import com.oxclub.universaldownloader.domain.model.ThemeMode
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.BannerSlot
import com.oxclub.universaldownloader.ui.components.ChoiceDialog
import com.oxclub.universaldownloader.ui.components.SectionHeader
import com.oxclub.universaldownloader.ui.components.TextInputDialog
import com.oxclub.universaldownloader.ui.navigation.Routes
import com.oxclub.universaldownloader.utils.Formatters
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun <T> set(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch { c.settings.set(key, value) }
    }

    fun setLanguage(tag: String) {
        set(Keys.LANGUAGE, tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun clearTreeUri() {
        viewModelScope.launch { c.settings.remove(Keys.TREE_URI) }
    }

    suspend fun clearHistory(): Int = c.downloadManager.clearHistory()
    suspend fun clearTemporary(): Long = c.downloadManager.clearTemporaryFiles()
    fun describeTree(uri: String) = c.fileStore.describeTree(uri)
}

private enum class Dialog { THEME, ACCENT, LANGUAGE, PARTS, CONCURRENT, DUPLICATES, CONNECT, READ, RETRIES, RETRY_DELAY, AGENT, CONFIRM_HISTORY }

@Composable
private fun Pref(icon: ImageVector, title: Int, subtitle: String?, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchPref(icon: ImageVector, title: Int, subtitle: Int?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (subtitle != null) {
            { Text(stringResource(subtitle)) }
        } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    )
}

@Composable
fun SettingsScreen(onOpenStorage: () -> Unit, onOpenInfo: (String) -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.set(Keys.TREE_URI, uri.toString())
        }
    }

    val themeNames = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark),
    )
    val accentNames = listOf(
        AccentColor.VIOLET to stringResource(R.string.accent_violet),
        AccentColor.BLUE to stringResource(R.string.accent_blue),
        AccentColor.TEAL to stringResource(R.string.accent_teal),
        AccentColor.ORANGE to stringResource(R.string.accent_orange),
        AccentColor.PINK to stringResource(R.string.accent_pink),
    )
    val languageNames = listOf(
        "" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "hi" to stringResource(R.string.language_hindi),
    )
    val duplicateNames = listOf(
        DuplicatePolicy.RENAME to stringResource(R.string.dup_rename),
        DuplicatePolicy.REPLACE to stringResource(R.string.dup_replace),
        DuplicatePolicy.SKIP to stringResource(R.string.dup_skip),
    )
    val historyCleared = stringResource(R.string.history_cleared)
    val tempCleared = stringResource(R.string.temp_cleared)
    val location = s.downloadTreeUri?.let { vm.describeTree(it) } ?: FileStore.DEFAULT_LABEL

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BannerSlot(AdPlacement.SETTINGS_BOTTOM) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(R.string.section_appearance)
            Pref(Icons.Filled.Palette, R.string.setting_theme, themeNames.first { it.first == s.themeMode }.second, { dialog = Dialog.THEME })
            Pref(
                Icons.Filled.Palette, R.string.setting_accent, accentNames.first { it.first == s.accent }.second, { dialog = Dialog.ACCENT },
                trailing = { androidx.compose.foundation.layout.Box(Modifier.size(24.dp).background(Color(s.accent.argb), CircleShape)) },
            )
            HorizontalDivider()

            SectionHeader(R.string.section_language)
            Pref(Icons.Filled.Language, R.string.setting_language, languageNames.firstOrNull { it.first == s.language }?.second, { dialog = Dialog.LANGUAGE })
            HorizontalDivider()

            SectionHeader(R.string.section_download)
            Pref(Icons.Filled.Folder, R.string.setting_location, location, { folderPicker.launch(null) })
            if (s.downloadTreeUri != null) {
                Pref(Icons.Filled.Folder, R.string.setting_location_reset, FileStore.DEFAULT_LABEL, { vm.clearTreeUri() })
            }
            Pref(Icons.Filled.Download, R.string.setting_parts, s.partCount.toString(), { dialog = Dialog.PARTS })
            Pref(Icons.Filled.Download, R.string.setting_concurrent, s.maxConcurrent.toString(), { dialog = Dialog.CONCURRENT })
            SwitchPref(Icons.Filled.Wifi, R.string.setting_wifi_only, R.string.setting_wifi_only_desc, s.wifiOnly, "sw_wifi") { vm.set(Keys.WIFI_ONLY, it) }
            SwitchPref(Icons.Filled.Download, R.string.setting_ask_before, null, s.askBeforeDownload, "sw_ask") { vm.set(Keys.ASK_BEFORE, it) }
            SwitchPref(Icons.Filled.Download, R.string.setting_auto_start, null, s.autoStart, "sw_auto") { vm.set(Keys.AUTO_START, it) }
            SwitchPref(Icons.Filled.Refresh, R.string.setting_auto_resume, R.string.setting_auto_resume_desc, s.autoResumeFailed, "sw_resume") { vm.set(Keys.AUTO_RESUME, it) }
            SwitchPref(Icons.Filled.Notifications, R.string.setting_notifications, null, s.notifications, "sw_notif") { vm.set(Keys.NOTIFICATIONS, it) }
            Pref(Icons.Filled.Folder, R.string.setting_duplicates, duplicateNames.first { it.first == s.duplicatePolicy }.second, { dialog = Dialog.DUPLICATES })
            HorizontalDivider()

            SectionHeader(R.string.section_network)
            Pref(Icons.Filled.Timer, R.string.setting_connect_timeout, stringResource(R.string.seconds_value, s.connectTimeoutSec), { dialog = Dialog.CONNECT })
            Pref(Icons.Filled.Timer, R.string.setting_read_timeout, stringResource(R.string.seconds_value, s.readTimeoutSec), { dialog = Dialog.READ })
            Pref(Icons.Filled.Refresh, R.string.setting_retry_count, s.retryCount.toString(), { dialog = Dialog.RETRIES })
            Pref(Icons.Filled.Refresh, R.string.setting_retry_delay, stringResource(R.string.seconds_value, s.retryDelaySec), { dialog = Dialog.RETRY_DELAY })
            Pref(Icons.Filled.Cloud, R.string.setting_user_agent, s.userAgent, { dialog = Dialog.AGENT })
            SwitchPref(Icons.Filled.Lock, R.string.setting_allow_local, R.string.setting_allow_local_desc, s.allowLocalTargets, "sw_local") { vm.set(Keys.ALLOW_LOCAL, it) }
            HorizontalDivider()

            SectionHeader(R.string.section_browser)
            SwitchPref(Icons.Filled.Public, R.string.setting_browser, null, s.browserEnabled, "sw_browser") { vm.set(Keys.BROWSER, it) }
            SwitchPref(Icons.Filled.ContentPaste, R.string.setting_clipboard, R.string.setting_clipboard_desc, s.clipboardDetection, "sw_clipboard") { vm.set(Keys.CLIPBOARD, it) }
            SwitchPref(Icons.Filled.Speed, R.string.setting_ask_extract, R.string.setting_ask_extract_desc, s.askBeforeExtract, "sw_extract") { vm.set(Keys.ASK_EXTRACT, it) }
            HorizontalDivider()

            SectionHeader(R.string.section_storage)
            Pref(Icons.Filled.Storage, R.string.storage_title, stringResource(R.string.storage_summary), onOpenStorage)
            HorizontalDivider()

            SectionHeader(R.string.section_privacy)
            Pref(Icons.Filled.Delete, R.string.setting_clear_history, null, { dialog = Dialog.CONFIRM_HISTORY })
            Pref(Icons.Filled.Delete, R.string.setting_clear_temp, null, {
                scope.launch {
                    val freed = vm.clearTemporary()
                    snackbar.showSnackbar(tempCleared.replace("%s", Formatters.size(freed)))
                }
            })
            SwitchPref(Icons.Filled.Lock, R.string.setting_no_urls, R.string.setting_no_urls_desc, s.doNotStoreUrls, "sw_nourls") { vm.set(Keys.NO_URLS, it) }
            Pref(Icons.Filled.Lock, R.string.privacy_title, null, { onOpenInfo(Routes.PRIVACY) })
            HorizontalDivider()

            SectionHeader(R.string.section_about)
            Pref(Icons.Filled.Info, R.string.about_title, stringResource(R.string.version_format, com.oxclub.universaldownloader.BuildConfig.VERSION_NAME), { onOpenInfo(Routes.ABOUT) })
        }
    }

    when (dialog) {
        Dialog.THEME -> ChoiceDialog(R.string.setting_theme, themeNames, s.themeMode, { dialog = null }) { vm.set(Keys.THEME, it.name) }
        Dialog.ACCENT -> ChoiceDialog(R.string.setting_accent, accentNames, s.accent, { dialog = null }) { vm.set(Keys.ACCENT, it.name) }
        Dialog.LANGUAGE -> ChoiceDialog(R.string.setting_language, languageNames, s.language, { dialog = null }) { vm.setLanguage(it) }
        Dialog.DUPLICATES -> ChoiceDialog(R.string.setting_duplicates, duplicateNames, s.duplicatePolicy, { dialog = null }) { vm.set(Keys.DUPLICATES, it.name) }
        Dialog.PARTS -> ChoiceDialog(
            R.string.setting_parts, listOf(1, 2, 4, 8, 16).map { it to it.toString() }, s.partCount, { dialog = null },
        ) { vm.set(Keys.PARTS, it) }
        Dialog.CONCURRENT -> ChoiceDialog(
            R.string.setting_concurrent, (1..5).map { it to it.toString() }, s.maxConcurrent, { dialog = null },
        ) { vm.set(Keys.MAX_CONCURRENT, it) }
        Dialog.CONNECT -> NumberDialog(R.string.setting_connect_timeout, s.connectTimeoutSec, 5..120, { dialog = null }) { vm.set(Keys.CONNECT_TIMEOUT, it) }
        Dialog.READ -> NumberDialog(R.string.setting_read_timeout, s.readTimeoutSec, 5..300, { dialog = null }) { vm.set(Keys.READ_TIMEOUT, it) }
        Dialog.RETRIES -> NumberDialog(R.string.setting_retry_count, s.retryCount, 0..10, { dialog = null }) { vm.set(Keys.RETRY_COUNT, it) }
        Dialog.RETRY_DELAY -> NumberDialog(R.string.setting_retry_delay, s.retryDelaySec, 1..60, { dialog = null }) { vm.set(Keys.RETRY_DELAY, it) }
        Dialog.AGENT -> TextInputDialog(
            title = R.string.setting_user_agent, initial = s.userAgent, onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.set(Keys.USER_AGENT, it.ifBlank { DEFAULT_USER_AGENT }) },
        )
        Dialog.CONFIRM_HISTORY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.setting_clear_history)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        vm.clearHistory()
                        snackbar.showSnackbar(historyCleared)
                    }
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun NumberDialog(title: Int, current: Int, range: IntRange, onDismiss: () -> Unit, onValue: (Int) -> Unit) {
    TextInputDialog(
        title = title,
        initial = current.toString(),
        onDismiss = onDismiss,
        onConfirm = { text ->
            onDismiss()
            text.toIntOrNull()?.let { onValue(it.coerceIn(range.first, range.last)) }
        },
        keyboardNumber = true,
    )
}
