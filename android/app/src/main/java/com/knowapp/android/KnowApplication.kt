package com.knowapp.android

import android.app.Application

class KnowApplication : Application() {
    // Simple manual DI — one container built once, handed to the ViewModels
    // that need it. No Hilt/kapt: keeps first-time build setup lighter for
    // a project this size; revisit if the dependency graph grows a lot.
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A sleeping server needs a moment to start, so nudge it as soon as the app opens
        Thread {
            try {
                java.net.URL("${BuildConfig.API_BASE_URL}/api/health").openConnection().apply { connectTimeout = 60000; readTimeout = 60000 }.getInputStream().close()
            } catch (_: Exception) { }
        }.start()
    }
}
