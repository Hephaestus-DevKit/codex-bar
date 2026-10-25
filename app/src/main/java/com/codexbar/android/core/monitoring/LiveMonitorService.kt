package com.codexbar.android.core.monitoring

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.di.ClaudeRepository
import com.codexbar.android.di.CodexRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

/** Explicitly user-started and stoppable. No hidden restart at boot or after a force stop. */
@AndroidEntryPoint
class LiveMonitorService : Service() {
    @Inject lateinit var prefs: EncryptedPrefsManager
    @Inject lateinit var state: LiveQuotaState
    @Inject lateinit var chips: ChipNotifications
    @Inject @ClaudeRepository lateinit var claude: QuotaRepository
    @Inject @CodexRepository lateinit var codex: QuotaRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { chips.enabled = false; stopSelf(); return START_NOT_STICKY }
        chips.enabled = true
        if (Build.VERSION.SDK_INT >= 34) startForeground(ChipNotifications.CONTROL_ID, chips.controller(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ChipNotifications.CONTROL_ID, chips.controller())
        chips.publish(state.readings())
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                val accounts = listOf(AiService.CODEX, AiService.CLAUDE).flatMap { prefs.loadCredentialAccounts(it) }
                if (accounts.isEmpty()) { stopSelf(); break }
                chips.publish(state.readings())
                supervisorScope {
                    accounts.map { account -> launch {
                        val result = try { (if (account.service == AiService.CODEX) codex else claude).fetchQuota(account) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { com.codexbar.android.core.domain.model.Result.Failure(com.codexbar.android.core.domain.model.AppError.NetworkError("Update failed")) }
                        state.record(account, result)
                    } }.joinAll()
                }
                chips.publish(state.readings())
                delay(60_000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { chips.enabled = false; scope.cancel(); chips.clear(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val STOP = "com.local.aquota.STOP_LIVE" }
}
