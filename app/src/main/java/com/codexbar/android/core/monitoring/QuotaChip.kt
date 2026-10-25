package com.codexbar.android.core.monitoring

import com.codexbar.android.core.domain.model.QuotaInfo
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

object QuotaChip {
    fun remaining(quota: QuotaInfo): Int? = quota.windows.firstOrNull()?.utilization
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { ((1 - it) * 100).roundToInt().coerceIn(0, 100) }

    fun shortText(quota: QuotaInfo?, failed: Boolean, now: Instant = Instant.now()): String {
        val value = quota?.let(::remaining) ?: return "?"
        if (failed || Duration.between(quota.fetchedAt, now).seconds > 150) return "~$value%"
        return "$value%"
    }

    /** O/C order; stale values become ? so the compact pair always fits seven characters. */
    fun pairText(openai: QuotaInfo?, openaiFailed: Boolean, claude: QuotaInfo?, claudeFailed: Boolean): String {
        fun part(quota: QuotaInfo?, failed: Boolean): String = shortText(quota, failed)
            .let { if (it.startsWith("~")) "?" else it.removeSuffix("%") }
        return "${part(openai, openaiFailed)}/${part(claude, claudeFailed)}"
    }

    fun id(service: String, accountId: String): String = "$service:$accountId"
}
