package com.codexbar.android.core.widget

import android.content.Context
import android.content.SharedPreferences
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.QuotaInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages per-widget configuration (selected services) and cached quota data.
 * Account labels and cached usage are encrypted with the Android Keystore master key.
 */
@Singleton
class WidgetPrefsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: com.codexbar.android.core.security.EncryptedPrefsManager
) {
    private val prefs: SharedPreferences by lazy {
        securePrefs.displayPrefs
    }

    // --- Per-widget service selection ---

    fun saveSelectedServices(appWidgetId: Int, services: Set<AiService>) {
        val key = "widget_${appWidgetId}_services"
        prefs.edit().putStringSet(key, services.map { it.name }.toSet()).commit()
    }

    fun getSelectedServices(appWidgetId: Int): Set<AiService> {
        val key = "widget_${appWidgetId}_services"
        val names = prefs.getStringSet(key, null) ?: return emptySet()
        return names.mapNotNull { name ->
            try { AiService.valueOf(name) } catch (_: Exception) { null }
        }.toSet()
    }

    fun deleteWidgetConfig(appWidgetId: Int) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("widget_${appWidgetId}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // --- Cached quota data for widgets ---

    fun cacheQuotaData(service: AiService, label: String, utilization: Double, resetsAtEpochSecond: Long?) {
        cacheQuotaData(service, QuotaInfo.DEFAULT_ACCOUNT_ID, service.displayName, label, utilization, resetsAtEpochSecond)
    }

    fun cacheQuotaData(
        service: AiService,
        accountId: String,
        accountLabel: String,
        label: String,
        utilization: Double,
        resetsAtEpochSecond: Long?
    ) {
        val prefix = cachePrefix(service, accountId)
        val labels = getCachedLabels(service, accountId).plus(label).distinct()
        val editor = prefs.edit()
            .putString("${prefix}_labels", labels.joinToString(","))
            .putString("${prefix}_account_label", accountLabel)
            .putFloat("${prefix}_${label}_util", utilization.toFloat())
        if (resetsAtEpochSecond != null) {
            editor.putLong("${prefix}_${label}_resets", resetsAtEpochSecond)
        } else {
            editor.remove("${prefix}_${label}_resets")
        }
        saveCachedAccountId(editor, service, accountId)
        editor.apply()
    }

    fun cacheAllQuotaData(service: AiService, windows: List<Triple<String, Double, Long?>>) {
        cacheAllQuotaData(service, QuotaInfo.DEFAULT_ACCOUNT_ID, service.displayName, windows)
    }

    fun cacheAllQuotaData(
        service: AiService,
        accountId: String,
        accountLabel: String,
        windows: List<Triple<String, Double, Long?>>
    ) {
        val prefix = cachePrefix(service, accountId)
        val editor = prefs.edit()
        // Clear old cache for this account
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }

        val labels = windows.map { it.first }
        editor.putString("${prefix}_labels", labels.joinToString(","))
        editor.putString("${prefix}_account_label", accountLabel)
        for ((label, utilization, resetsAt) in windows) {
            editor.putFloat("${prefix}_${label}_util", utilization.toFloat())
            if (resetsAt != null) {
                editor.putLong("${prefix}_${label}_resets", resetsAt)
            }
        }
        editor.putLong("${prefix}_updated_at", System.currentTimeMillis())
        saveCachedAccountId(editor, service, accountId)
        editor.apply()
    }

    fun getCachedLabels(service: AiService): List<String> {
        return getCachedLabels(service, QuotaInfo.DEFAULT_ACCOUNT_ID)
    }

    fun getCachedLabels(service: AiService, accountId: String): List<String> {
        val raw = prefs.getString("${cachePrefix(service, accountId)}_labels", null) ?: return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun getCachedUtilization(service: AiService, label: String): Float {
        return getCachedUtilization(service, QuotaInfo.DEFAULT_ACCOUNT_ID, label)
    }

    fun getCachedUtilization(service: AiService, accountId: String, label: String): Float {
        return prefs.getFloat("${cachePrefix(service, accountId)}_${label}_util", 0f)
    }

    fun getCachedResetsAt(service: AiService, label: String): Long? {
        return getCachedResetsAt(service, QuotaInfo.DEFAULT_ACCOUNT_ID, label)
    }

    fun getCachedResetsAt(service: AiService, accountId: String, label: String): Long? {
        val value = prefs.getLong("${cachePrefix(service, accountId)}_${label}_resets", -1L)
        return if (value > 0) value else null
    }

    fun getCachedUpdatedAt(service: AiService): Long {
        return getCachedUpdatedAt(service, QuotaInfo.DEFAULT_ACCOUNT_ID)
    }

    fun getCachedUpdatedAt(service: AiService, accountId: String): Long {
        return prefs.getLong("${cachePrefix(service, accountId)}_updated_at", 0L)
    }

    /** Returns the highest utilization across all cached windows for this service. */
    fun getMaxCachedUtilization(service: AiService): Float {
        val labels = getCachedLabels(service)
        if (labels.isEmpty()) return 0f
        return labels.maxOf { getCachedUtilization(service, it) }
    }

    fun cacheTier(service: AiService, tier: String?) {
        cacheTier(service, QuotaInfo.DEFAULT_ACCOUNT_ID, tier)
    }

    fun cacheTier(service: AiService, accountId: String, tier: String?) {
        val key = "${cachePrefix(service, accountId)}_tier"
        if (tier != null) {
            prefs.edit().putString(key, tier).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    fun getCachedTier(service: AiService): String? {
        return getCachedTier(service, QuotaInfo.DEFAULT_ACCOUNT_ID)
    }

    fun getCachedTier(service: AiService, accountId: String): String? {
        return prefs.getString("${cachePrefix(service, accountId)}_tier", null)
    }

    fun getCachedAccountIds(service: AiService): List<String> {
        val raw = prefs.getString("cache_${service.name}_account_ids", null)
        val ids = raw?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        return ids.ifEmpty {
            if (getCachedLabels(service, QuotaInfo.DEFAULT_ACCOUNT_ID).isEmpty()) emptyList()
            else listOf(QuotaInfo.DEFAULT_ACCOUNT_ID)
        }
    }

    fun getCachedAccountLabel(service: AiService, accountId: String): String {
        return prefs.getString("${cachePrefix(service, accountId)}_account_label", null)
            ?: service.displayName
    }

    private fun cachePrefix(service: AiService, accountId: String): String {
        return "cache_${service.name}_account_$accountId"
    }

    private fun saveCachedAccountId(
        editor: SharedPreferences.Editor,
        service: AiService,
        accountId: String
    ) {
        val ids = getCachedAccountIds(service).toMutableList()
        if (accountId !in ids) ids.add(accountId)
        editor.putString("cache_${service.name}_account_ids", ids.joinToString(","))
    }
}
