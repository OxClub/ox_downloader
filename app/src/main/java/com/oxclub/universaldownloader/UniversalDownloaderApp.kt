package com.oxclub.universaldownloader

import android.app.Application

class UniversalDownloaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.downloadManager.start()
    }
}
