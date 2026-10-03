package com.oxclub.universaldownloader.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oxclub.universaldownloader.domain.model.ThemeMode
import com.oxclub.universaldownloader.ui.navigation.MainNav
import com.oxclub.universaldownloader.ui.onboarding.OnboardingScreen
import com.oxclub.universaldownloader.ui.theme.UniversalDownloaderTheme

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }
    LaunchedEffect(s.themeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (s.themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }
    UniversalDownloaderTheme(s.themeMode, s.accent) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (!s.onboardingDone) OnboardingScreen() else MainNav(s)
        }
    }
}
