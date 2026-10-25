package com.codexbar.android.core.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

/** No automatic replay of token POSTs; server cooldowns also apply to manual refresh. */
class RetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = chain.request().url.host
        if ((blockedUntil[key] ?: 0) > System.currentTimeMillis()) {
            throw IOException("Provider cooldown is active; retry later")
        }
        return chain.proceed(chain.request()).also { response ->
            if (response.code == 429) {
                blockedUntil[key] = System.currentTimeMillis() + retryDelaySeconds(response.header("Retry-After")) * 1000L
            }
        }
    }
    companion object {
        private val blockedUntil = ConcurrentHashMap<String, Long>()
        fun retryDelaySeconds(value: String?, nowMillis: Long = System.currentTimeMillis()): Long {
            val seconds = value?.trim()?.toLongOrNull() ?: runCatching {
                (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis) / 1000
            }.getOrDefault(300L)
            return seconds.coerceIn(60, 86400)
        }
    }
}
