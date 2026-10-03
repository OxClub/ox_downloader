package com.oxclub.universaldownloader.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class AdPlacement { HOME_BOTTOM, DOWNLOADS_BOTTOM, SETTINGS_BOTTOM }

/**
 * Ad abstraction. The core download UI never depends on a concrete ad SDK. Ad unit ids come from
 * BuildConfig (set through the `adBannerUnitId` Gradle property), never from source code.
 * This build ships with [NoOpAdProvider]; plug an SDK-backed implementation into AppContainer to enable ads.
 */
interface AdProvider {
    val isEnabled: Boolean

    @Composable
    fun Banner(placement: AdPlacement, modifier: Modifier)
}

object NoOpAdProvider : AdProvider {
    override val isEnabled: Boolean = false

    @Composable
    override fun Banner(placement: AdPlacement, modifier: Modifier) = Unit
}
