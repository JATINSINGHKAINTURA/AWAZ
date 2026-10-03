package com.awaz.app

import android.app.Application
import com.awaz.app.live.LiveSessionController
import com.awaz.app.service.VoiceForegroundService
import com.awaz.app.ui.NavigationStateManager

class AwazApplication : Application() {

    lateinit var navigationStateManager: NavigationStateManager
        private set

    lateinit var sessionController: LiveSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        navigationStateManager = NavigationStateManager(
            serviceStatusProvider = { VoiceForegroundService.isRunning.value }
        )
        sessionController = LiveSessionController(this)
    }

    companion object {
        lateinit var instance: AwazApplication
            private set
    }
}
