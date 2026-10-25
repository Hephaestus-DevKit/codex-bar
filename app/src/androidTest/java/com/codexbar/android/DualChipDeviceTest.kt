package com.codexbar.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.monitoring.*
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DualChipDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sharedPrefs(): EncryptedPrefsManager {
        lateinit var prefs: EncryptedPrefsManager
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            scenario.onActivity { prefs = it.prefs }
        }
        return prefs
    }

    @org.junit.Before fun stopPreviousMonitor() {
        context.stopService(android.content.Intent(context, LiveMonitorService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.chips.enabled = false
                activity.chips.clear()
                activity.prefs.deleteAllCredentials()
            }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5000
        while (manager.activeNotifications.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Previous test notifications must be removed", manager.activeNotifications.isEmpty())
    }

    @Test fun swipedCombinedNotificationRestoresCachedQuotaButStopDisablesRestoration() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        var restore: android.app.PendingIntent? = null
        lateinit var singleton: ChipNotifications
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val prefs = activity.prefs
                    prefs.deleteAllCredentials()
                    prefs.saveCredential(AiService.CODEX, "restore-openai", "OpenAI demo", Credential.CodexCredential("synthetic", "synthetic"))
                    prefs.saveCredential(AiService.CLAUDE, "restore-claude", "Claude demo", Credential.ClaudeCredential("synthetic", "synthetic"))
                    prefs.setCombinedChip(true)
                    listOf(AiService.CODEX, AiService.CLAUDE).forEachIndexed { index, service ->
                        val account = prefs.loadCredentialAccounts(service).first()
                        activity.readings.record(account, Result.Success(QuotaInfo(service, account.id, account.label,
                            listOf(UsageWindow("5-Hour", if (index == 0) .20 else .35, null)), null, fetchedAt = Instant.now())))
                    }
                    singleton = activity.chips
                    singleton.enabled = true
                    singleton.publish(activity.readings.readings())
                }
                fun ownRows() = manager.activeNotifications.filter { it.id == ChipNotifications.CONTROL_ID || it.id == ChipNotifications.ACCOUNT_ID }
                fun waitForRow() {
                    val deadline = System.currentTimeMillis() + 5000
                    while (ownRows().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                }
                waitForRow()
                assertEquals(1, ownRows().size)
                val initial = ownRows().single().notification
                assertEquals("OpenAI / Claude", initial.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertNull(initial.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertNull(initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                assertEquals(1, initial.actions.size)
                assertEquals("Stop", initial.actions.single().title.toString())
                restore = initial.deleteIntent
                assertNotNull(restore)
                fun deliverDismissal() {
                    val delivered = java.util.concurrent.CountDownLatch(1)
                    restore!!.send(context, 0, null, android.app.PendingIntent.OnFinished { _, _, _, _, _ -> delivered.countDown() }, null)
                    assertTrue(delivered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                manager.cancel(ChipNotifications.CONTROL_ID)
                val removedBy = System.currentTimeMillis() + 5000
                while (ownRows().isNotEmpty() && System.currentTimeMillis() < removedBy) Thread.sleep(50)
                assertTrue(ownRows().isEmpty())
                // Android sends this app-owned intent after a swipe, not after app cancellation.
                deliverDismissal()
                waitForRow()
                assertEquals(1, ownRows().size)
                assertEquals("OpenAI / Claude", ownRows().single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                if (Build.VERSION.SDK_INT >= 36) assertEquals("80/65", ownRows().single().notification.shortCriticalText)
                singleton.enabled = false
                singleton.clear()
                deliverDismissal()
                Thread.sleep(300)
                assertTrue(ownRows().isEmpty())
            } finally {
                singleton.enabled = false; singleton.clear()
                scenario.onActivity { it.prefs.deleteAllCredentials() }
            }
        }
    }

    @Test fun actualShadeSwipeRestoresQuotaNotification() {
        if (Build.VERSION.SDK_INT < 36) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = serviceInfo
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        var liveState: LiveQuotaState? = null
        val serviceIntent = android.content.Intent(context, LiveMonitorService::class.java)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.prefs.deleteAllCredentials()
                    activity.prefs.setCombinedChip(true)
                    listOf(AiService.CODEX, AiService.CLAUDE).forEachIndexed { index, service ->
                        val credential = if (service == AiService.CODEX) Credential.CodexCredential("synthetic", "synthetic")
                            else Credential.ClaudeCredential("synthetic", "synthetic")
                        activity.prefs.saveCredential(service, "gesture-${service.name}", "Demo", credential)
                        val account = activity.prefs.loadCredentialAccounts(service).first()
                        activity.readings.record(account, Result.Success(QuotaInfo(service, account.id, account.label,
                            listOf(UsageWindow("5-Hour", if (index == 0) .20 else .35, null)), null, fetchedAt = Instant.now())))
                    }
                    activity.chips.enabled = true
                    activity.chips.publish(activity.readings.readings())
                    liveState = activity.readings
                    activity.startForegroundService(serviceIntent)
                }
                // Network is disabled for synthetic accounts. Let the first polling cycle
                // finish so a later post can only come from the swipe's deleteIntent.
                val polledBy = System.currentTimeMillis() + 35_000
                while (liveState!!.readings().any { it.error == null } && System.currentTimeMillis() < polledBy) Thread.sleep(100)
                assertTrue("Initial offline polling cycle must finish before gesture", liveState!!.readings().all { it.error != null })
                fun primary() = manager.activeNotifications.firstOrNull { it.id == ChipNotifications.CONTROL_ID }
                val readyBy = System.currentTimeMillis() + 5000
                while (primary() == null && System.currentTimeMillis() < readyBy) Thread.sleep(50)
                assertTrue(primary()!!.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                instrumentation.waitForIdleSync()
                Thread.sleep(1000)
                shell("cmd statusbar expand-notifications")
                var target: android.view.accessibility.AccessibilityNodeInfo? = null
                fun nodes(root: android.view.accessibility.AccessibilityNodeInfo): Sequence<android.view.accessibility.AccessibilityNodeInfo> = sequence {
                    yield(root)
                    for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(nodes(it)) }
                }
                fun visibleNodes() = (instrumentation.uiAutomation.windows.mapNotNull { it.root } +
                    listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)).asSequence().flatMap { nodes(it) }
                val visibleBy = System.currentTimeMillis() + 15_000
                while (target == null && System.currentTimeMillis() < visibleBy) {
                    target = visibleNodes().firstOrNull { it.text?.toString() == "OpenAI / Claude" && it.isVisibleToUser }
                    if (target == null) {
                        shell("cmd statusbar expand-notifications")
                        Thread.sleep(500)
                    }
                }
                if (target == null) {
                    println("Notification shade nodes: " + visibleNodes().mapNotNull { it.text?.toString() }.toList())
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(context.cacheDir, "persistent-chip-shade-failure.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                assertNotNull("Combined quota row must be visible in notification shade", target)
                val bounds = android.graphics.Rect()
                target!!.getBoundsInScreen(bounds)
                val width = context.resources.displayMetrics.widthPixels
                val before = primary()!!.postTime
                shell("input swipe ${width / 8} ${bounds.centerY()} ${width - 5} ${bounds.centerY()} 250")
                val restoredBy = System.currentTimeMillis() + 8000
                while ((primary()?.postTime ?: 0) <= before && System.currentTimeMillis() < restoredBy) Thread.sleep(50)
                assertTrue("Swiping must cause an immediate repost, without a polling cycle", (primary()?.postTime ?: 0) > before)
                assertTrue(primary()!!.notification.hasPromotableCharacteristics())
                assertTrue(primary()!!.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
                assertEquals(1, manager.activeNotifications.count { it.id == ChipNotifications.CONTROL_ID || it.id == ChipNotifications.ACCOUNT_ID })
                shell("cmd statusbar collapse")
                Thread.sleep(500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "persistent-chip-restored.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val dismissIntent = primary()!!.notification.deleteIntent
                primary()!!.notification.actions.single().actionIntent.send()
                val stoppedBy = System.currentTimeMillis() + 5000
                while (primary() != null && System.currentTimeMillis() < stoppedBy) Thread.sleep(50)
                assertNull("Stop action must remove the foreground chip", primary())
                val delivered = java.util.concurrent.CountDownLatch(1)
                dismissIntent.send(context, 0, null, android.app.PendingIntent.OnFinished { _, _, _, _, _ -> delivered.countDown() }, null)
                assertTrue(delivered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertNull("A delayed dismissal after Stop must not resurrect the chip", primary())
            } finally {
                shell("cmd statusbar collapse")
                context.stopService(serviceIntent)
                scenario.onActivity { activity ->
                    activity.chips.enabled = false
                    activity.chips.clear()
                    activity.prefs.deleteAllCredentials()
                }
            }
        }
    }

    @Test fun browserLoginModelSurvivesActivityRecreation() {
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            var before: PhoneLoginViewModel? = null
            scenario.onActivity { before = androidx.lifecycle.ViewModelProvider(it)[PhoneLoginViewModel::class.java] }
            scenario.recreate()
            scenario.onActivity {
                assertSame(before, androidx.lifecycle.ViewModelProvider(it)[PhoneLoginViewModel::class.java])
            }
        }
    }

    @Test fun encryptionAndLogoutRejectLateRenewalAndAccountIdReuse() {
        val prefs = sharedPrefs()
        prefs.deleteAllCredentials()
        val old = Credential.ClaudeCredential("synthetic-old-access", "synthetic-old-refresh")
        prefs.saveCredential(AiService.CLAUDE, "device-test", "Claude", old)
        val snapshot = prefs.loadCredentialAccount(AiService.CLAUDE, "device-test")!!
        Thread.sleep(300)
        val xml = java.io.File(context.applicationInfo.dataDir, "shared_prefs/codexbar_secure_prefs.xml")
        assertFalse(xml.readText().contains("synthetic-old-access"))
        prefs.deleteCredential(AiService.CLAUDE, "device-test")
        assertFalse(prefs.saveRefreshed(snapshot, old.copy(accessToken = "late")))
        assertNull(prefs.loadCredentialAccount(AiService.CLAUDE, "device-test"))
        prefs.saveCredential(AiService.CLAUDE, "device-test", "Replacement", old.copy(accessToken = "replacement"))
        assertFalse(prefs.saveRefreshed(snapshot, old.copy(accessToken = "late")))
        assertEquals("replacement", prefs.loadCredentialAccount(AiService.CLAUDE, "device-test")!!.credential.accessToken)
        prefs.deleteAllCredentials()
    }

    @Test fun combinedChipKeepsBothQuotasVisibleAndLogoutCannotRepostIt() {
        dualNotificationsHaveIndependentIdentityAndPromotionRequests()
        val prefs = sharedPrefs()
        val chips = ChipNotifications(context, prefs).apply { enabled = true }
        val openai = prefs.loadCredentialAccounts(AiService.CODEX).first()
        val claude = prefs.loadCredentialAccounts(AiService.CLAUDE).first()
        fun reading(account: CredentialAccount, used: Double) = AccountReading(account,
            QuotaInfo(account.service, account.id, account.label, listOf(UsageWindow("5-Hour", used, null)), null, fetchedAt = Instant.now()))
        val readings = listOf(reading(openai, .20), reading(claude, .35))
        prefs.setCombinedChip(true)
        val notification = chips.combinedNotification(readings[0], readings[1])
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("80/65", notification.shortCriticalText)
            assertTrue(notification.hasPromotableCharacteristics())
        }
        chips.publish(readings)
        // Also prove that a stale snapshot cannot restore a logged-out account notification.
        prefs.deleteAllCredentials()
        chips.publish(readings)
        val manager = context.getSystemService(NotificationManager::class.java)
        val removedBy = System.currentTimeMillis() + 5000
        while (manager.activeNotifications.any { it.id == ChipNotifications.ACCOUNT_ID || it.id == ChipNotifications.CONTROL_ID } && System.currentTimeMillis() < removedBy) Thread.sleep(50)
        assertTrue(manager.activeNotifications.none { it.id == ChipNotifications.ACCOUNT_ID || it.id == ChipNotifications.CONTROL_ID })
        // Recreate only synthetic readings for visual inspection of the fallback.
        prefs.saveCredential(AiService.CODEX, openai.id, "OpenAI demo", openai.credential)
        prefs.saveCredential(AiService.CLAUDE, claude.id, "Claude demo", claude.credential)
        chips.publish(listOf(reading(prefs.loadCredentialAccounts(AiService.CODEX).first(), .20),
            reading(prefs.loadCredentialAccounts(AiService.CLAUDE).first(), .35)))
    }

    @Test fun loopbackRejectsWrongStateAndRemainsCancellable() {
        val client = com.codexbar.android.core.security.PhoneLoginClient()
        val opened = java.util.concurrent.CompletableFuture<String>()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val job = pool.submit { runCatching { client.claude { opened.complete(it) } } }
        try {
            val url = opened.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
            java.net.Socket("127.0.0.1", 53692).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /callback?code=synthetic&state=wrong HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("400 Bad Request"))
            }
            assertFalse(job.isDone)
        } finally { client.cancel(); job.get(5, java.util.concurrent.TimeUnit.SECONDS); pool.shutdownNow() }
    }

    @Test fun dualNotificationsHaveIndependentIdentityAndPromotionRequests() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val prefs = sharedPrefs()
        prefs.deleteAllCredentials()
        prefs.saveCredential(AiService.CODEX, "proof-openai", "OpenAI demo", Credential.CodexCredential("synthetic", "synthetic"))
        prefs.saveCredential(AiService.CLAUDE, "proof-claude", "Claude demo", Credential.ClaudeCredential("synthetic", "synthetic"))
        prefs.setCombinedChip(false)
        val chips = ChipNotifications(context, prefs).apply { enabled = true }
        val accounts = listOf(AiService.CODEX, AiService.CLAUDE).map { prefs.loadCredentialAccounts(it).first() }
        val readings = accounts.mapIndexed { index, account ->
            AccountReading(account, QuotaInfo(account.service, account.id, account.label,
                windows = listOf(UsageWindow("5-Hour", if (index == 0) .20 else .35, null)),
                extraUsage = null, fetchedAt = Instant.now()))
        }
        for (reading in readings) {
            val notification = chips.accountNotification(reading)
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(ChipNotifications.CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
            assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertNull(notification.group)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(notification.hasPromotableCharacteristics())
            }
        }
        chips.publish(readings)
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5000
        while (manager.activeNotifications.count { (it.id == ChipNotifications.ACCOUNT_ID || it.id == ChipNotifications.CONTROL_ID) } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val active = manager.activeNotifications.filter { (it.id == ChipNotifications.ACCOUNT_ID || it.id == ChipNotifications.CONTROL_ID) }
        assertEquals(2, active.size)
        assertEquals(2, active.map { it.tag to it.id }.distinct().size)
        assertTrue(active.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "OpenAI" })
        assertTrue(active.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Claude" })
        // Leave synthetic notifications for an external screenshot; no real account is used.
    }
}
