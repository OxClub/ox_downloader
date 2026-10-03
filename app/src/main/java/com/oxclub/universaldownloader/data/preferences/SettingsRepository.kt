package com.oxclub.universaldownloader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oxclub.universaldownloader.domain.model.AccentColor
import com.oxclub.universaldownloader.domain.model.DuplicatePolicy
import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val DEFAULT_USER_AGENT = "UniversalDownloader/1.0 (Android)"

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.VIOLET,
    val language: String = "",
    val downloadTreeUri: String? = null,
    val partCount: Int = 4,
    val maxConcurrent: Int = 3,
    val wifiOnly: Boolean = false,
    val askBeforeDownload: Boolean = false,
    val autoStart: Boolean = true,
    val autoResumeFailed: Boolean = true,
    val notifications: Boolean = true,
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.RENAME,
    val connectTimeoutSec: Int = 20,
    val readTimeoutSec: Int = 30,
    val retryCount: Int = 3,
    val retryDelaySec: Int = 2,
    val userAgent: String = DEFAULT_USER_AGENT,
    val allowLocalTargets: Boolean = false,
    val browserEnabled: Boolean = true,
    val clipboardDetection: Boolean = true,
    val askBeforeExtract: Boolean = false,
    val doNotStoreUrls: Boolean = false,
    val lastClipboardUrl: String? = null,
) {
    fun netConfig() = NetConfig(connectTimeoutSec, readTimeoutSec, userAgent.ifBlank { DEFAULT_USER_AGENT }, allowLocalTargets)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object Keys {
    val ONBOARDING = booleanPreferencesKey("onboarding_done")
    val THEME = stringPreferencesKey("theme_mode")
    val ACCENT = stringPreferencesKey("accent")
    val LANGUAGE = stringPreferencesKey("language")
    val TREE_URI = stringPreferencesKey("download_tree_uri")
    val PARTS = intPreferencesKey("part_count")
    val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val ASK_BEFORE = booleanPreferencesKey("ask_before_download")
    val AUTO_START = booleanPreferencesKey("auto_start")
    val AUTO_RESUME = booleanPreferencesKey("auto_resume_failed")
    val NOTIFICATIONS = booleanPreferencesKey("notifications")
    val DUPLICATES = stringPreferencesKey("duplicate_policy")
    val CONNECT_TIMEOUT = intPreferencesKey("connect_timeout")
    val READ_TIMEOUT = intPreferencesKey("read_timeout")
    val RETRY_COUNT = intPreferencesKey("retry_count")
    val RETRY_DELAY = intPreferencesKey("retry_delay")
    val USER_AGENT = stringPreferencesKey("user_agent")
    val ALLOW_LOCAL = booleanPreferencesKey("allow_local")
    val BROWSER = booleanPreferencesKey("browser_enabled")
    val CLIPBOARD = booleanPreferencesKey("clipboard_detection")
    val ASK_EXTRACT = booleanPreferencesKey("ask_before_extract")
    val NO_URLS = booleanPreferencesKey("do_not_store_urls")
    val LAST_CLIPBOARD = stringPreferencesKey("last_clipboard_url")
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

class SettingsRepository(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun remove(key: Preferences.Key<*>) {
        context.dataStore.edit { it.remove(key) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = this[Keys.ONBOARDING] ?: d.onboardingDone,
            themeMode = enumOr(this[Keys.THEME], d.themeMode),
            accent = enumOr(this[Keys.ACCENT], d.accent),
            language = this[Keys.LANGUAGE] ?: d.language,
            downloadTreeUri = this[Keys.TREE_URI],
            partCount = this[Keys.PARTS] ?: d.partCount,
            maxConcurrent = this[Keys.MAX_CONCURRENT] ?: d.maxConcurrent,
            wifiOnly = this[Keys.WIFI_ONLY] ?: d.wifiOnly,
            askBeforeDownload = this[Keys.ASK_BEFORE] ?: d.askBeforeDownload,
            autoStart = this[Keys.AUTO_START] ?: d.autoStart,
            autoResumeFailed = this[Keys.AUTO_RESUME] ?: d.autoResumeFailed,
            notifications = this[Keys.NOTIFICATIONS] ?: d.notifications,
            duplicatePolicy = enumOr(this[Keys.DUPLICATES], d.duplicatePolicy),
            connectTimeoutSec = this[Keys.CONNECT_TIMEOUT] ?: d.connectTimeoutSec,
            readTimeoutSec = this[Keys.READ_TIMEOUT] ?: d.readTimeoutSec,
            retryCount = this[Keys.RETRY_COUNT] ?: d.retryCount,
            retryDelaySec = this[Keys.RETRY_DELAY] ?: d.retryDelaySec,
            userAgent = this[Keys.USER_AGENT] ?: d.userAgent,
            allowLocalTargets = this[Keys.ALLOW_LOCAL] ?: d.allowLocalTargets,
            browserEnabled = this[Keys.BROWSER] ?: d.browserEnabled,
            clipboardDetection = this[Keys.CLIPBOARD] ?: d.clipboardDetection,
            askBeforeExtract = this[Keys.ASK_EXTRACT] ?: d.askBeforeExtract,
            doNotStoreUrls = this[Keys.NO_URLS] ?: d.doNotStoreUrls,
            lastClipboardUrl = this[Keys.LAST_CLIPBOARD],
        )
    }
}
