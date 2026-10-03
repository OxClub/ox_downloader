package com.oxclub.universaldownloader.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oxclub.universaldownloader.PendingRequest
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.preferences.AppSettings
import com.oxclub.universaldownloader.ui.LocalAppContainer
import com.oxclub.universaldownloader.ui.analysis.AnalysisScreen
import com.oxclub.universaldownloader.ui.batch.BatchScreen
import com.oxclub.universaldownloader.ui.browser.BrowserScreen
import com.oxclub.universaldownloader.ui.categories.CategoriesScreen
import com.oxclub.universaldownloader.ui.details.DetailsScreen
import com.oxclub.universaldownloader.ui.downloads.DownloadsScreen
import com.oxclub.universaldownloader.ui.home.HomeScreen
import com.oxclub.universaldownloader.ui.settings.InfoScreen
import com.oxclub.universaldownloader.ui.settings.InfoKind
import com.oxclub.universaldownloader.ui.settings.SettingsScreen
import com.oxclub.universaldownloader.ui.settings.StorageScreen

private data class TopItem(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun MainNav(settings: AppSettings) {
    val nav = rememberNavController()
    val container = LocalAppContainer.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Share sheet: one URL goes straight to analysis, several go to batch download.
    val incoming by container.incomingUrls.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            if (incoming.size == 1) {
                container.pendingRequest.request = PendingRequest(incoming.first())
                nav.navigate(Routes.ANALYSIS)
            } else {
                container.pendingRequest.batchUrls = incoming
                nav.navigate(Routes.BATCH)
            }
            container.incomingUrls.value = emptyList()
        }
    }

    val items = buildList {
        add(TopItem(Routes.HOME, R.string.nav_home, Icons.Filled.Home))
        add(TopItem(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Filled.Download))
        add(TopItem(Routes.CATEGORIES, R.string.nav_categories, Icons.Filled.Category))
        if (settings.browserEnabled) add(TopItem(Routes.BROWSER, R.string.nav_browser, Icons.Filled.Public))
        add(TopItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings))
    }
    val showBar = route != null && route in Routes.topLevel

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    for (item in items) {
                        val selected = route?.startsWith(item.route) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAnalyze = { nav.navigate(Routes.ANALYSIS) },
                    onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                    onOpenDetails = { nav.navigate(Routes.details(it)) },
                    onOpenBatch = { nav.navigate(Routes.BATCH) },
                    onOpenStorage = { nav.navigate(Routes.STORAGE) },
                    onOpenHelp = { nav.navigate(Routes.HELP) },
                    onOpenAbout = { nav.navigate(Routes.ABOUT) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                Routes.DOWNLOADS_PATTERN,
                arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                DownloadsScreen(
                    category = entry.arguments?.getString("category"),
                    onAnalyze = { nav.navigate(Routes.ANALYSIS) },
                    onOpenDetails = { nav.navigate(Routes.details(it)) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(onOpenCategory = { nav.navigate(Routes.downloads(it)) })
            }
            composable(Routes.BROWSER) {
                BrowserScreen(onDownload = { nav.navigate(Routes.ANALYSIS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenStorage = { nav.navigate(Routes.STORAGE) },
                    onOpenInfo = { nav.navigate(it) },
                )
            }
            composable(Routes.ANALYSIS) {
                AnalysisScreen(
                    onBack = { nav.popBackStack() },
                    onDone = {
                        nav.navigate(Routes.DOWNLOADS) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenBrowser = {
                        nav.navigate(Routes.BROWSER) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.BATCH) { BatchScreen(onBack = { nav.popBackStack() }, onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) }) }
            composable(
                Routes.DETAILS_PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                DetailsScreen(id = entry.arguments?.getLong("id") ?: -1L, onBack = { nav.popBackStack() })
            }
            composable(Routes.STORAGE) { StorageScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { InfoScreen(InfoKind.ABOUT, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable(Routes.HELP) { InfoScreen(InfoKind.HELP, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable(Routes.FAQ) { InfoScreen(InfoKind.FAQ, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable(Routes.PRIVACY) { InfoScreen(InfoKind.PRIVACY, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable(Routes.LICENSES) { InfoScreen(InfoKind.LICENSES, onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
        }
    }
}
