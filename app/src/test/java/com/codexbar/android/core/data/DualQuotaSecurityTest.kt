package com.codexbar.android.core.data

import com.codexbar.android.core.security.OAuthPkce
import com.codexbar.android.core.network.RetryInterceptor
import com.codexbar.android.core.monitoring.QuotaChip
import com.codexbar.android.core.domain.model.*
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DualQuotaSecurityTest {
    @Test fun oauthRejectsWrongStateDuplicateParametersAndForeignCallbacks() {
        val grant = OAuthPkce()
        assertEquals("secret-code", grant.callbackCode("/callback?code=secret-code&state=${grant.state}"))
        assertNull(grant.callbackCode("/callback?code=x&state=attacker"))
        assertNull(grant.callbackCode("/callback?code=x&state=${grant.state}&state=attacker"))
        assertNull(grant.callbackCode("http://evil.example/callback?code=x&state=${grant.state}"))
        assertNull(grant.callbackCode("//evil.example/callback?code=x&state=${grant.state}"))
        assertNull(grant.callbackCode("/other?code=x&state=${grant.state}"))
        assertNull(grant.callbackCode("/callback?error=denied&code=x&state=${grant.state}"))
        assertNotEquals(grant.state, OAuthPkce().state)
        assertNotEquals(grant.verifier, OAuthPkce().verifier)
        assertTrue(grant.authorizeUrl().contains("scope=user%3Aprofile"))
        assertFalse(grant.authorizeUrl().contains("inference"))
        assertFalse(grant.authorizeUrl().contains(grant.verifier))
    }

    @Test fun remainingQuotaNeverTurnsMissingOrStaleIntoFreshZero() {
        val now = Instant.parse("2026-10-25T00:00:00Z")
        val quota = QuotaInfo(AiService.CLAUDE, windows = listOf(UsageWindow("5-Hour", .35, null)), extraUsage = null, fetchedAt = now)
        assertEquals("65%", QuotaChip.shortText(quota, false, now))
        assertEquals("~65%", QuotaChip.shortText(quota, true, now))
        assertEquals("~65%", QuotaChip.shortText(quota, false, now.plusSeconds(151)))
        assertEquals("?", QuotaChip.shortText(null, true, now))
        assertNull(QuotaChip.remaining(quota.copy(windows = emptyList())))
        assertNull(QuotaChip.remaining(quota.copy(windows = listOf(UsageWindow("invalid", Double.NaN, null)))))
    }

    @Test fun compactPairHasFixedProviderOrderAndDoesNotPresentStaleDataAsLive() {
        val now = Instant.now()
        val openai = QuotaInfo(AiService.CODEX, windows = listOf(UsageWindow("5-Hour", .20, null)), extraUsage = null, fetchedAt = now)
        val claude = openai.copy(service = AiService.CLAUDE, windows = listOf(UsageWindow("5-Hour", .35, null)))
        assertEquals("80/65", QuotaChip.pairText(openai, false, claude, false))
        assertEquals("?/65", QuotaChip.pairText(openai, true, claude, false))
        assertEquals("80/?", QuotaChip.pairText(openai, false, claude.copy(fetchedAt = now.minusSeconds(151)), false))
        val full = openai.copy(windows = listOf(UsageWindow("5-Hour", 0.0, null)))
        assertEquals("100/100", QuotaChip.pairText(full, false, full, false))
    }

    @Test fun serverRetryAfterIsRespectedWithoutAnImmediateRetryLoop() {
        assertEquals(3600L, RetryInterceptor.retryDelaySeconds("3600"))
        assertEquals(60L, RetryInterceptor.retryDelaySeconds("-1"))
        assertEquals(300L, RetryInterceptor.retryDelaySeconds("invalid"))
        assertEquals(86400L, RetryInterceptor.retryDelaySeconds("999999999"))
        assertEquals(600L, RetryInterceptor.retryDelaySeconds("Sun, 25 Oct 2026 00:10:00 GMT", Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()))
    }

    @Test fun rateLimitResponseIsNotReplayedAndSubsequentCallsWait() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429).addHeader("Retry-After", "600"))
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(RetryInterceptor()).build()
            val request = okhttp3.Request.Builder().url(server.url("/quota")).build()
            client.newCall(request).execute().use { assertEquals(429, it.code) }
            assertEquals(1, server.requestCount)
            try { client.newCall(request).execute().close(); fail("Cooldown must block this call") }
            catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun simultaneousRefreshesForOneAccountAreSerializedButOtherAccountsProceed() = runBlocking {
        val account = CredentialAccount("one", AiService.CLAUDE, "One", Credential.ClaudeCredential("synthetic", "synthetic"))
        var active = 0
        var highest = 0
        coroutineScope {
            repeat(8) { launch {
                AccountLocks.withAccount(account) { active++; highest = maxOf(highest, active); delay(5); active-- }
            } }
        }
        assertEquals(1, highest)
        val firstInside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { AccountLocks.withAccount(account) { firstInside.complete(Unit); release.await() } }
        firstInside.await()
        withTimeout(1000) { AccountLocks.withAccount(account.copy(id = "two")) { assertTrue(true) } }
        release.complete(Unit); first.join()
    }
}
