package com.codexbar.android.core.workmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    private const val QUOTA_WORK_NAME = "quota_periodic_refresh"
    private const val TOKEN_WORK_NAME = "token_periodic_refresh"
    private const val MANUAL_QUOTA_WORK_NAME = "quota_manual_refresh"
    private const val DEFAULT_INTERVAL_MINUTES = 30L

    fun scheduleAll(context: Context, intervalMinutes: Long = currentRefreshInterval(context)) {
        schedulePeriodicRefresh(context, intervalMinutes)
        WorkManager.getInstance(context).cancelUniqueWork(TOKEN_WORK_NAME)
    }

    fun schedulePeriodicRefresh(context: Context, intervalMinutes: Long = currentRefreshInterval(context)) {
        if (intervalMinutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(QUOTA_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(
            intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("quota_refresh")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            QUOTA_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleTokenRefresh(context: Context, intervalMinutes: Long = currentRefreshInterval(context)) {
        WorkManager.getInstance(context).cancelUniqueWork(TOKEN_WORK_NAME)
    }

    fun enqueueManualQuotaRefresh(context: Context, reason: String = "manual") {
        val request = OneTimeWorkRequestBuilder<QuotaRefreshWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("quota_refresh_$reason")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_QUOTA_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    private fun currentRefreshInterval(context: Context): Long {
        return runCatching { EncryptedPrefsManager(context).getRefreshInterval() }
            .getOrDefault(DEFAULT_INTERVAL_MINUTES)
    }
}
