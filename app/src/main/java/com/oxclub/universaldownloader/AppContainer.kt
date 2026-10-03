package com.oxclub.universaldownloader

import android.app.Application
import androidx.room.Room
import com.oxclub.universaldownloader.ads.AdProvider
import com.oxclub.universaldownloader.ads.NoOpAdProvider
import com.oxclub.universaldownloader.data.database.AppDatabase
import com.oxclub.universaldownloader.data.network.HttpFetcher
import com.oxclub.universaldownloader.data.network.NetworkMonitor
import com.oxclub.universaldownloader.data.preferences.AppSettings
import com.oxclub.universaldownloader.data.preferences.SettingsRepository
import com.oxclub.universaldownloader.data.repository.DownloadRepository
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.downloader.DownloadManager
import com.oxclub.universaldownloader.extractor.UrlAnalyzer
import com.oxclub.universaldownloader.notification.DownloadNotificationManager
import com.oxclub.universaldownloader.premium.Entitlements
import com.oxclub.universaldownloader.premium.OpenEntitlements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Request handed from a screen (home, share sheet, browser) to the analysis screen. */
data class PendingRequest(
    val url: String,
    val quick: Boolean = false,
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
) {
    fun headers(): Map<String, String> = buildMap {
        cookie?.let { put("Cookie", it) }
        referer?.let { put("Referer", it) }
        userAgent?.let { put("User-Agent", it) }
    }
}

class PendingRequestHolder {
    @Volatile var request: PendingRequest? = null
    @Volatile var batchUrls: List<String> = emptyList()
}

/** Manual dependency container (keeps the app free of annotation-processing DI). */
class AppContainer(val app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val settingsState: StateFlow<AppSettings?> = settings.flow.stateIn(appScope, SharingStarted.Eagerly, null)

    private val database = Room.databaseBuilder(app, AppDatabase::class.java, "universal_downloader.db").build()
    val repository = DownloadRepository(database.downloadDao())
    val fetcher = HttpFetcher()
    val analyzer = UrlAnalyzer(fetcher)
    val fileStore = FileStore(app, settings)
    val networkMonitor = NetworkMonitor(app)
    val notifier = DownloadNotificationManager(app)
    val downloadManager = DownloadManager(app, repository, analyzer, fetcher, fileStore, settings, notifier, networkMonitor)

    val pendingRequest = PendingRequestHolder()
    /** URLs received from the Android share sheet, consumed by the navigation host. */
    val incomingUrls = MutableStateFlow<List<String>>(emptyList())
    /** URL the built-in browser should open (e.g. to sign in before downloading). */
    val browserRequest = MutableStateFlow<String?>(null)

    val ads: AdProvider = NoOpAdProvider
    val entitlements: Entitlements = OpenEntitlements
}
