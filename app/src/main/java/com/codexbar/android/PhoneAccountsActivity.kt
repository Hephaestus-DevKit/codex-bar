package com.codexbar.android

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.monitoring.ChipNotifications
import com.codexbar.android.core.monitoring.LiveMonitorService
import com.codexbar.android.core.monitoring.LiveQuotaState
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.workmanager.RefreshScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class PhoneAccountsActivity : ComponentActivity() {
    @Inject lateinit var prefs: EncryptedPrefsManager
    @Inject lateinit var chips: ChipNotifications
    @Inject lateinit var readings: LiveQuotaState
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var loginModel: PhoneLoginViewModel
    private var copiedCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        loginModel = ViewModelProvider(this)[PhoneLoginViewModel::class.java]
        if (savedInstanceState?.getBoolean("pending_signin") == true && loginModel.state.value.service == null && !loginModel.state.value.connected) loginModel.interrupted()
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginModel.state.collect { state ->
                    render()
                    status.text = state.message
                    if (state.connected) RefreshScheduler.enqueueManualQuotaRefresh(this@PhoneAccountsActivity, "phone_signin")
                    if (state.url != null && !loginModel.browserPresented) {
                        loginModel.markBrowserPresented()
                        presentBrowserSignIn()
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) { render(); status.text = loginModel.state.value.message } }

    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 12, 0, 12)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }

    private fun render() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 40) }
        content.addView(text("AI Quota", 30f))
        content.addView(text("OpenAI + Claude · on this phone", 18f))
        content.addView(text("The status bar shows remaining quota of the first window (usually 5 hours). OpenAI / Claude order in combined mode. Android may display only one chip even when two notifications are promoted. Weekly limits are in the dashboard."))
        for (service in listOf(AiService.CODEX, AiService.CLAUDE)) {
            val account = prefs.loadCredentialAccounts(service).firstOrNull()
            val provider = if (service == AiService.CODEX) "OpenAI / Codex" else "Claude"
            content.addView(text("$provider — ${account?.label ?: "not connected"}", 21f))
            content.addView(button(if (account == null) "Connect $provider" else "Reconnect $provider") {
                if (service == AiService.CLAUDE) {
                    AlertDialog.Builder(this).setTitle("Connect Claude on this phone")
                        .setMessage("Sign in on claude.ai in your browser. AI Quota requests the profile permission to read usage and stores its own access/refresh tokens encrypted on this phone. This is an unofficial integration using the public Claude OAuth client; Anthropic may reject or change it. No computer or API key is used.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> connect(service) }.show()
                } else connect(service)
            })
            if (account != null) content.addView(button("Disconnect $provider") {
                AlertDialog.Builder(this).setTitle("Disconnect $provider?")
                    .setMessage("Remove local credentials and cached usage for this provider. This does not revoke credentials on the provider's server.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Disconnect") { _, _ ->
                        loginModel.cancel()
                        prefs.deleteCredential(service)
                        chips.clear()
                        chips.publish(readings.readings())
                        render()
                    }.show()
            })
        }
        content.addView(text("Live monitoring", 22f))
        content.addView(text("A native chip requires a silent notification in the notification shade. Swiping restores it while monitoring is on; tap Stop to remove it. About once per minute while the monitor is running. Provider rate limits, Doze, lost connectivity and GrapheneOS network permissions can delay updates. No push feed is available. A ~ prefix means an old or failed reading."))
        content.addView(Switch(this).apply {
            text = "One chip with both quotas (OpenAI / Claude)"
            isChecked = prefs.useCombinedChip()
            setOnCheckedChangeListener { _, checked ->
                prefs.setCombinedChip(checked)
                chips.publish(readings.readings())
                status.text = if (checked) "Combined mode: OpenAI first / Claude second. ? means missing, old or failed."
                    else "Two separate notifications requested. Android may still show only one chip."
            }
        })
        content.addView(button("Start live monitoring") { startLive() })
        content.addView(button("Stop live monitoring") { stopService(Intent(this, LiveMonitorService::class.java)); chips.clear(); status.text = "Live monitoring stopped" })
        content.addView(button("Allow status-bar chips") {
            val normal = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= 36) {
                runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                    .onFailure { startActivity(normal) }
            } else startActivity(normal)
        })
        content.addView(button("Usage dashboard & account names") { startActivity(Intent(this, MainActivity::class.java)) })
        content.addView(button("Cancel sign-in") { loginModel.cancel(); clearCopiedCode(); status.text = "Sign-in cancelled" })
        if (loginModel.state.value.url != null) {
            content.addView(button("Reopen pending browser sign-in") { presentBrowserSignIn() })
            if (loginModel.state.value.service == AiService.CLAUDE) content.addView(button("Paste Claude callback") {
                val input = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    hint = "http://localhost:53692/callback?code=…&state=… or code#state"
                }
                AlertDialog.Builder(this).setTitle("Complete Claude sign-in")
                    .setMessage("Paste the final localhost URL from the browser, or code#state shown by Claude. Keep this value private. It must belong to the current sign-in attempt.")
                    .setView(input).setNegativeButton("Cancel", null)
                    .setPositiveButton("Complete") { _, _ ->
                        val accepted = loginModel.submitClaudeCallback(input.text.toString())
                        input.text.clear()
                        status.text = if (accepted) "Completing Claude sign-in…" else "Callback did not match this attempt. Retry sign-in."
                    }.show()
            })
        }
        status = text(loginModel.state.value.message)
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun startLive() {
        if (listOf(AiService.CODEX, AiService.CLAUDE).none { prefs.hasCredential(it) }) { status.text = "Connect an account first"; return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 18)
            status.text = "Allow notifications, then tap Start live monitoring again"; return
        }
        val intent = Intent(this, LiveMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        val promoted = Build.VERSION.SDK_INT < 36 || getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        status.text = if (promoted) "Live monitor started. Open another app to see eligible chips." else "Monitor started. Tap Allow status-bar chips to enable promoted notifications."
    }

    private fun connect(service: AiService) { loginModel.start(service) }

    private fun presentBrowserSignIn() {
        val state = loginModel.state.value
        val url = state.url ?: return
        val code = state.code
        if (code == null) { openBrowser(url); return }
        AlertDialog.Builder(this).setTitle("OpenAI device sign-in")
            .setMessage("One-time code: $code\n\nVerify auth.openai.com in the browser. Enter this code only for the sign-in you started. After browser authorization, return here and wait for Account saved.")
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy code and open sign-in") { _, _ ->
                val clip = ClipData.newPlainText("One-time sign-in code", code)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                copiedCode = code
                openBrowser(url)
            }.show()
    }

    private fun openBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { loginModel.cancel(); status.text = "No browser is available" }
    }

    private fun clearCopiedCode() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (copiedCode != null && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == copiedCode) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        copiedCode = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_signin", loginModel.state.value.service != null)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { clearCopiedCode(); super.onDestroy() }
}
