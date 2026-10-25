package com.codexbar.android.core.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.codexbar.android.PhoneAccountsActivity
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChipNotifications @Inject constructor(@ApplicationContext private val context: Context,
    private val prefs: com.codexbar.android.core.security.EncryptedPrefsManager) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    @Volatile var enabled: Boolean = false
    private data class Posted(val tag: String?, val id: Int)
    private val shown = mutableSetOf<Posted>()
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Account quota chips", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Silent quota notification; Android requires it for the status-bar chip"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    private fun openApp() = PendingIntent.getActivity(context, 7100,
        Intent(context, PhoneAccountsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun restoreAfterSwipe() = PendingIntent.getBroadcast(context, 7103,
        Intent(context, RestoreChipReceiver::class.java).setAction(RestoreChipReceiver.RESTORE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun stopAction() = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_quota), "Stop",
        PendingIntent.getService(context, 7101, Intent(context, LiveMonitorService::class.java).setAction(LiveMonitorService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    fun controller(): Notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_quota).setContentTitle("AI Quota")
        .setShowWhen(false)
        .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
        .setDeleteIntent(restoreAfterSwipe())
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .addAction(stopAction())
        .build()

    @Synchronized fun publish(readings: List<AccountReading>) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openai = readings.firstOrNull { it.account.service == AiService.CODEX }
        val claude = readings.firstOrNull { it.account.service == AiService.CLAUDE }
        val paired = prefs.useCombinedChip() && openai != null && claude != null
        val visible = if (paired) listOf(openai!!) else readings
        // The first quota notification also owns the foreground service. Combined mode
        // therefore needs one shade row rather than a controller plus a duplicate quota row.
        fun identity(index: Int, reading: AccountReading) = if (index == 0) Posted(null, CONTROL_ID)
            else Posted(QuotaChip.id(reading.account.service.name, reading.account.id), ACCOUNT_ID)
        val current = visible.mapIndexed(::identity).toSet()
        (shown - current).forEach { manager.cancel(it.tag, it.id) }
        shown.retainAll(current)
        visible.forEachIndexed { index, reading ->
            val posted = identity(index, reading)
            prefs.runIfCurrent(reading.account) {
                if (paired && !prefs.isCurrent(claude!!.account)) return@runIfCurrent
                val notification = if (paired) combinedNotification(openai!!, claude!!) else accountNotification(reading)
                manager.notify(posted.tag, posted.id, notification)
                shown.add(posted)
            }
        }
        // Remove quota rows posted by an older version/process, using only app-owned IDs.
        manager.activeNotifications.filter { it.id == ACCOUNT_ID && Posted(it.tag, it.id) !in current }
            .forEach { manager.cancel(it.tag, it.id) }
        if (visible.isEmpty()) manager.notify(CONTROL_ID, controller())
    }

    fun combinedNotification(openai: AccountReading, claude: AccountReading): Notification {
        val short = QuotaChip.pairText(openai.quota, openai.error != null, claude.quota, claude.error != null)
        val first = accountNotification(openai)
        val builder = Notification.Builder.recoverBuilder(context, first)
            .setContentTitle("OpenAI / Claude")
        if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(short)
        return builder.build()
    }

    fun accountNotification(reading: AccountReading): Notification {
        val account = reading.account
        val quota = reading.quota
        val short = QuotaChip.shortText(quota, reading.error != null)
        val provider = if (account.service == AiService.CODEX) "OpenAI" else "Claude"
        val icon = if (account.service == AiService.CODEX) R.drawable.ic_service_codex else R.drawable.ic_service_claude
        val publicVersion = Notification.Builder(context, CHANNEL).setSmallIcon(icon)
            .setContentTitle("AI Quota").setShowWhen(false).build()
        val builder = Notification.Builder(context, CHANNEL)
            // A nonempty title is required for native chip promotion. Keep the body
            // and expanded text absent; detailed quotas remain in the dashboard.
            .setSmallIcon(icon).setContentTitle(provider)
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setDeleteIntent(restoreAfterSwipe()).addAction(stopAction())
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setColorized(true)
            .setColor(if (account.service == AiService.CODEX) 0xff10a37f.toInt() else 0xffd4a574.toInt())
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(short)
            // Android 16 initial release uses colorized+ongoing. Later releases add an explicit request API.
            val explicitRequest = runCatching { builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType).invoke(builder, true) }.isSuccess
            // Newer Android requires the explicit request and disallows colorization.
            builder.setColorized(!explicitRequest)
        }
        return builder.build()
    }

    @Synchronized fun clear() {
        // Also remove notifications left by a previous process.
        manager.activeNotifications.filter { it.id == ACCOUNT_ID || it.id == CONTROL_ID || it.id == 1001 || it.id >= 2000 }
            .forEach { manager.cancel(it.tag, it.id) }
        shown.clear()
    }

    companion object {
        const val CHANNEL = "dual_account_live"
        const val ACCOUNT_ID = 7102
        const val CONTROL_ID = 7100
    }
}
