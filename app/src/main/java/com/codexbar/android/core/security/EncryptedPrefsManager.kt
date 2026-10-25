package com.codexbar.android.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.QuotaInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedPrefsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "codexbar_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val displayPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(context, "codexbar_display_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun createAccountId(service: AiService): String {
        val base = "${service.name.lowercase()}_${System.currentTimeMillis()}"
        val existing = loadAccountIds(service).toSet()
        if (base !in existing) return base
        var index = 2
        while ("${base}_$index" in existing) index += 1
        return "${base}_$index"
    }

    fun defaultAccountLabel(service: AiService, index: Int = 1): String {
        return if (index <= 1) service.displayName else "${service.displayName} $index"
    }

    fun saveCredential(service: AiService, credential: Credential) {
        val accountId = loadAccountIds(service).firstOrNull() ?: QuotaInfo.DEFAULT_ACCOUNT_ID
        val label = loadCredentialAccount(service, accountId)?.label ?: defaultAccountLabel(service)
        saveCredential(service, accountId, label, credential)
    }

    @Synchronized
    fun saveCredential(
        service: AiService,
        accountId: String,
        label: String,
        credential: Credential
    ) {
        val safeAccountId = accountId.ifBlank { createAccountId(service) }
        val accountIds = loadAccountIds(service).toMutableList()
        if (safeAccountId !in accountIds) accountIds.add(safeAccountId)

        val editor = prefs.edit()
        editor.putString(accountIdsKey(service), accountIds.joinToString(","))
        val nextGeneration = sessionEpoch() + 1
        editor.putLong("session_epoch", nextGeneration)
        editor.putLong("${accountPrefix(service, safeAccountId)}_generation", nextGeneration)
        writeCredential(editor, accountPrefix(service, safeAccountId), label, credential)
        editor.apply()
    }

    fun loadCredential(service: AiService): Credential? {
        return loadCredentialAccounts(service).firstOrNull()?.credential
    }

    @Synchronized
    fun loadCredentialAccount(service: AiService, accountId: String): CredentialAccount? {
        val credential = readCredential(service, accountPrefix(service, accountId)) ?: return null
        val label = prefs.getString("${accountPrefix(service, accountId)}_label", null)
            ?: defaultAccountLabel(service, loadAccountIds(service).indexOf(accountId) + 1)
        return CredentialAccount(
            id = accountId,
            service = service,
            label = label,
            credential = credential,
            generation = generation(service, accountId)
        )
    }

    @Synchronized
    fun loadCredentialAccounts(service: AiService): List<CredentialAccount> {
        migrateLegacyCredentialIfNeeded(service)
        return loadAccountIds(service).mapNotNull { accountId ->
            loadCredentialAccount(service, accountId)
        }
    }

    @Synchronized
    fun deleteCredential(service: AiService) {
        clearDisplayCache()
        val editor = prefs.edit().putLong("session_epoch", sessionEpoch() + 1)
        prefs.all.keys
            .filter { it.startsWith(service.name) || it.startsWith("reset_${service.name}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    @Synchronized
    fun deleteCredential(service: AiService, accountId: String) {
        clearDisplayCache()
        val editor = prefs.edit().putLong("session_epoch", sessionEpoch() + 1)
        val prefix = accountPrefix(service, accountId)
        prefs.all.keys
            .filter { it.startsWith("${prefix}_") || it.startsWith("reset_${service.name}_${accountId}_") }
            .forEach { editor.remove(it) }

        val remainingIds = loadAccountIds(service).filterNot { it == accountId }
        if (remainingIds.isEmpty()) {
            editor.remove(accountIdsKey(service))
        } else {
            editor.putString(accountIdsKey(service), remainingIds.joinToString(","))
        }
        editor.apply()
    }

    @Synchronized
    fun deleteAllCredentials() {
        clearDisplayCache()
        val editor = prefs.edit().putLong("session_epoch", sessionEpoch() + 1)
        prefs.all.keys
            .filter { key ->
                AiService.entries.any { service ->
                    key.startsWith(service.name) || key.startsWith("reset_${service.name}_")
                }
            }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    @Synchronized
    fun sessionEpoch(): Long = prefs.getLong("session_epoch", 0)

    @Synchronized
    fun saveNewIfEpoch(service: AiService, credential: Credential, expectedEpoch: Long): Boolean {
        if (sessionEpoch() != expectedEpoch) return false
        saveCredential(service, credential)
        return true
    }

    private fun clearDisplayCache() {
        displayPrefs.edit().clear().commit()
        android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, com.codexbar.android.core.tile.QuotaTileService::class.java))
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.codexbar.android.core.widget.QuotaGlanceWidget().updateAll(context) }
        }
    }

    private fun generation(service: AiService, accountId: String): Long =
        prefs.getLong("${accountPrefix(service, accountId)}_generation", 0)

    @Synchronized
    fun saveRefreshed(account: CredentialAccount, credential: Credential): Boolean {
        val current = loadCredentialAccount(account.service, account.id) ?: return false
        if (current.generation != account.generation || current.credential != account.credential) return false
        val editor = prefs.edit()
        writeCredential(editor, accountPrefix(account.service, account.id), current.label, credential)
        check(editor.commit()) { "Could not persist renewed session" }
        return true
    }

    @Synchronized
    fun isCurrent(account: CredentialAccount): Boolean =
        loadCredentialAccount(account.service, account.id)?.generation == account.generation

    @Synchronized
    fun runIfCurrent(account: CredentialAccount, action: () -> Unit): Boolean {
        if (!isCurrent(account)) return false
        action()
        return true
    }

    fun hasCredential(service: AiService): Boolean {
        return loadCredentialAccounts(service).isNotEmpty()
    }

    fun useCombinedChip(): Boolean = prefs.getBoolean("combined_chip", true)
    fun setCombinedChip(enabled: Boolean) { prefs.edit().putBoolean("combined_chip", enabled).apply() }

    fun getRefreshInterval(): Long {
        return prefs.getLong("refresh_interval_minutes", 30L)
    }

    fun setRefreshInterval(minutes: Long) {
        prefs.edit().putLong("refresh_interval_minutes", minutes).apply()
    }

    fun isNotificationsEnabled(): Boolean {
        return prefs.getBoolean("notifications_enabled", true)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun saveResetTimes(service: AiService, windows: List<Pair<String, Instant?>>) {
        saveResetTimes(service, QuotaInfo.DEFAULT_ACCOUNT_ID, windows)
    }

    fun saveResetTimes(service: AiService, accountId: String, windows: List<Pair<String, Instant?>>) {
        val editor = prefs.edit()
        windows.forEach { (label, resetsAt) ->
            val key = resetKey(service, accountId, label)
            if (resetsAt != null) {
                editor.putLong(key, resetsAt.epochSecond)
            } else {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun loadResetTimes(service: AiService): Map<String, Instant> {
        return loadResetTimes(service, QuotaInfo.DEFAULT_ACCOUNT_ID)
    }

    fun loadResetTimes(service: AiService, accountId: String): Map<String, Instant> {
        val prefix = "reset_${service.name}_${accountId}_"
        val suffix = "_resets_at"
        return prefs.all
            .filter { it.key.startsWith(prefix) && it.key.endsWith(suffix) }
            .mapNotNull { (key, value) ->
                val label = key.removePrefix(prefix).removeSuffix(suffix)
                val epochSecond = (value as? Long)?.takeIf { it > 0 } ?: return@mapNotNull null
                label to Instant.ofEpochSecond(epochSecond)
            }
            .toMap()
    }

    @Synchronized
    private fun migrateLegacyCredentialIfNeeded(service: AiService) {
        if (loadAccountIds(service).isNotEmpty()) return
        val legacy = readCredential(service, service.name) ?: return
        val editor = prefs.edit()
        val accountId = QuotaInfo.DEFAULT_ACCOUNT_ID
        prefs.all.keys.filter { it.startsWith("${service.name}_") }.forEach { editor.remove(it) }
        editor.putString(accountIdsKey(service), accountId)
        writeCredential(editor, accountPrefix(service, accountId), defaultAccountLabel(service), legacy)
        editor.apply()
    }

    private fun loadAccountIds(service: AiService): List<String> {
        return prefs.getString(accountIdsKey(service), null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun writeCredential(
        editor: SharedPreferences.Editor,
        prefix: String,
        label: String,
        credential: Credential
    ) {
        editor.putString("${prefix}_label", label.ifBlank { "Account" })
        editor.putString("${prefix}_access_token", credential.accessToken)
        editor.putOrRemove("${prefix}_refresh_token", credential.refreshToken)

        when (credential) {
            is Credential.ClaudeCredential -> {
                if (credential.expiresAt != null) {
                    editor.putLong("${prefix}_expires_at", credential.expiresAt.epochSecond)
                } else {
                    editor.remove("${prefix}_expires_at")
                }
                editor.putOrRemove("${prefix}_scopes", credential.scopes)
                editor.putOrRemove("${prefix}_rate_limit_tier", credential.rateLimitTier)
            }
            is Credential.CodexCredential -> {
                editor.putOrRemove("${prefix}_account_id", credential.accountId)
            }
            is Credential.GeminiCredential -> {
                editor.putLong("${prefix}_expires_at_ms", credential.expiresAtMs)
                editor.putString("${prefix}_oauth_client_id", credential.oauthClientId)
                editor.putString("${prefix}_oauth_client_secret", credential.oauthClientSecret)
            }
        }
    }

    private fun readCredential(service: AiService, prefix: String): Credential? {
        val accessToken = prefs.getString("${prefix}_access_token", null) ?: return null

        return when (service) {
            AiService.CLAUDE -> {
                val refreshToken = prefs.getString("${prefix}_refresh_token", null)
                val expiresAt = prefs.getLong("${prefix}_expires_at", -1L)
                    .takeIf { it > 0 }
                    ?.let { Instant.ofEpochSecond(it) }
                val scopes = prefs.getString("${prefix}_scopes", null)
                val rateLimitTier = prefs.getString("${prefix}_rate_limit_tier", null)
                Credential.ClaudeCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    scopes = scopes,
                    rateLimitTier = rateLimitTier
                )
            }
            AiService.CODEX -> {
                val refreshToken = prefs.getString("${prefix}_refresh_token", null) ?: return null
                val accountId = prefs.getString("${prefix}_account_id", null)
                Credential.CodexCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = accountId
                )
            }
            AiService.GEMINI -> {
                val refreshToken = prefs.getString("${prefix}_refresh_token", null) ?: return null
                val expiresAtMs = prefs.getLong("${prefix}_expires_at_ms", -1L)
                    .takeIf { it > 0 } ?: return null
                val clientId = prefs.getString("${prefix}_oauth_client_id", null) ?: return null
                val clientSecret = prefs.getString("${prefix}_oauth_client_secret", null) ?: return null
                Credential.GeminiCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = expiresAtMs,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret
                )
            }
        }
    }

    private fun accountIdsKey(service: AiService): String = "${service.name}_account_ids"

    private fun accountPrefix(service: AiService, accountId: String): String {
        return "${service.name}_account_$accountId"
    }

    private fun resetKey(service: AiService, accountId: String, label: String): String {
        return "reset_${service.name}_${accountId}_${label}_resets_at"
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            putString(key, value)
        }
    }
}
