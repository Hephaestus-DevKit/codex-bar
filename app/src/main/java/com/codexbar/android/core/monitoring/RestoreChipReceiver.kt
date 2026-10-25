package com.codexbar.android.core.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** User-selected persistence: restore a swiped row while the monitor is already running.
 * Uses cached readings only, never starts a service or a network request. */
@AndroidEntryPoint
class RestoreChipReceiver : BroadcastReceiver() {
    @Inject lateinit var chips: ChipNotifications
    @Inject lateinit var state: LiveQuotaState

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RESTORE || !chips.enabled) return
        chips.publish(state.readings())
    }

    companion object { const val RESTORE = "com.local.aquota.RESTORE_ACTIVE_CHIPS" }
}
