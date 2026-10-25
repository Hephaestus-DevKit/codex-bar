package com.codexbar.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.codexbar.android.core.workmanager.RefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CodexBarApplication : Application(), Configuration.Provider {
    @Inject lateinit var factory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(factory).setMinimumLoggingLevel(android.util.Log.ERROR).build()
    override fun onCreate() { super.onCreate(); RefreshScheduler.scheduleAll(this) }
}
