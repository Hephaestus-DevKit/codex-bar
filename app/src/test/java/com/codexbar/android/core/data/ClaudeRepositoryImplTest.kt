package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.network.claude.ClaudeApiService
import com.codexbar.android.core.network.claude.ClaudeTokenRefreshService
import com.codexbar.android.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import okhttp3.MediaType.Companion.toMediaType
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit

private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any()

class ClaudeRepositoryImplTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: ClaudeApiService
    private lateinit var tokenRefreshService: ClaudeTokenRefreshService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: ClaudeRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val testCredential = Credential.ClaudeCredential(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token"
    )

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val client = OkHttpClient.Builder().build()
        val contentType = "application/json".toMediaType()

        apiService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ClaudeApiService::class.java)

        tokenRefreshService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ClaudeTokenRefreshService::class.java)

        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(
            listOf(testAccount(testCredential))
        )

        `when`(prefsManager.loadCredentialAccount(AiService.CLAUDE, "claude-test")).thenAnswer {
            prefsManager.loadCredentialAccounts(AiService.CLAUDE).firstOrNull()
        }
        `when`(prefsManager.saveRefreshed(anyArg(), anyArg())).thenReturn(true)

        repository = ClaudeRepositoryImpl(apiService, tokenRefreshService, prefsManager)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchQuota returns success with all windows`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42, "resets_at": "2025-06-01T12:00:00Z" },
            "seven_day": { "utilization": 15, "resets_at": "2025-06-07T00:00:00Z" },
            "seven_day_oauth_apps": { "utilization": 10 },
            "seven_day_opus": { "utilization": 30 },
            "seven_day_sonnet": { "utilization": 20 },
            "iguana_necktie": { "utilization": 5 },
            "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 50.0,
                "used_credits": 12.5,
                "utilization": 0.25,
                "currency": "USD"
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value

        assertEquals(AiService.CLAUDE, quotaInfo.service)
        assertEquals("Claude Test", quotaInfo.accountLabel)
        assertEquals(6, quotaInfo.windows.size)
        assertEquals("5-Hour", quotaInfo.windows[0].label)
        assertEquals(0.42, quotaInfo.windows[0].utilization, 0.001)
        assertEquals("Extended", quotaInfo.windows[5].label)
        assertNotNull(quotaInfo.extraUsage)
        assertEquals(50.0, quotaInfo.extraUsage!!.monthlyLimit, 0.001)
        assertEquals(12.5, quotaInfo.extraUsage!!.usedCredits, 0.001)
    }

    @Test
    fun `fetchQuota handles response without iguana_necktie`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 },
            "seven_day": { "utilization": 15 }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(2, quotaInfo.windows.size)
        assertTrue(quotaInfo.windows.none { it.label == "Extended" })
    }

    @Test
    fun `fetchQuota returns AuthError on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        // Token refresh also fails
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }

    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.RateLimited)
    }

    @Test
    fun `fetchQuota returns CredentialNotFound when no credential saved`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(emptyList())

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }

    @Test
    fun `fetchQuota extracts tier from response headers`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 }
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("anthropic-ratelimit-tier", "pro")
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals("Pro", quotaInfo.tier)
    }

    @Test
    fun `fetchQuota extracts tier from x-ratelimit-tier fallback header`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 10 }
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("x-ratelimit-tier", "free")
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals("Free", (result as Result.Success).value.tier)
    }

    @Test
    fun `fetchQuota extracts tier from JWT access token`() = runTest {
        // Build a fake JWT: header.payload.signature
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"rate_limit_tier":"max","sub":"user"}""".toByteArray())
        val fakeJwt = "eyJhbGciOiJSUzI1NiJ9.$payload.fake-signature"

        val jwtCredential = Credential.ClaudeCredential(
            accessToken = fakeJwt,
            refreshToken = "test-refresh-token"
        )
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(
            listOf(testAccount(jwtCredential))
        )

        val responseJson = """
        {
            "five_hour": { "utilization": 20 }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals("Max", (result as Result.Success).value.tier)
    }

    @Test
    fun `fetchQuota extra_usage null when disabled`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 },
            "extra_usage": { "is_enabled": false, "monthly_limit": 0, "used_credits": 0, "utilization": 0, "currency": "USD" }
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertTrue(quotaInfo.extraUsage == null)
    }

    @Test fun `expired token renews once across parallel fetches and persists rotated token`() = kotlinx.coroutines.runBlocking {
        var current = testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))
        org.mockito.Mockito.doAnswer { current }.`when`(prefsManager).loadCredentialAccount(AiService.CLAUDE, current.id)
        `when`(prefsManager.saveRefreshed(anyArg(), anyArg())).thenAnswer { invocation ->
            current = current.copy(credential = invocation.getArgument<Credential>(1)); true
        }
        mockWebServer.enqueue(MockResponse().setBody("""{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600}"""))
        repeat(2) { mockWebServer.enqueue(MockResponse().setBody("""{"five_hour":{"utilization":35}}""")) }
        val snapshot = current
        val results = kotlinx.coroutines.coroutineScope {
            listOf(async { repository.fetchQuota(snapshot) }, async { repository.fetchQuota(snapshot) }).map { it.await() }
        }
        assertTrue(results.all { it is Result.Success })
        assertEquals("rotated-refresh", current.credential.refreshToken)
        val renewal = mockWebServer.takeRequest()
        assertEquals("/v1/oauth/token", renewal.path)
        assertTrue(renewal.getHeader("Content-Type")!!.startsWith("application/json"))
        val renewalBody = renewal.body.readUtf8()
        assertTrue(renewalBody.contains("\"grant_type\":\"refresh_token\""))
        assertTrue(renewalBody.contains(ClaudeTokenRefreshService.CLAUDE_CLIENT_ID))
        repeat(2) { assertEquals("Bearer rotated-access", mockWebServer.takeRequest().getHeader("Authorization")) }
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test fun `logout during renewal prevents retry with the discarded new token`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(listOf(testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))))
        `when`(prefsManager.saveRefreshed(anyArg(), anyArg())).thenReturn(false)
        mockWebServer.enqueue(MockResponse().setBody("""{"access_token":"discarded","refresh_token":"discarded","expires_in":3600}"""))
        assertTrue(repository.fetchQuota() is Result.Failure)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test fun `missing usage is an error rather than a fresh zero`() = runTest {
        mockWebServer.enqueue(MockResponse().setBody("""{"five_hour":{}}"""))
        assertTrue(repository.fetchQuota() is Result.Failure)
    }

    @Test fun `nullable disabled extra usage does not break valid quota`() = runTest {
        mockWebServer.enqueue(MockResponse().setBody("""{
            "five_hour":{"utilization":23,"resets_at":null},
            "seven_day":null,
            "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null,"currency":null}
        }"""))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quota = (result as Result.Success).value
        assertEquals(0.23, quota.windows.first().utilization, 0.0001)
        assertTrue(quota.extraUsage == null)
    }

    @Test fun `invalid usage JSON produces safe diagnostic without server body`() = runTest {
        mockWebServer.enqueue(MockResponse().setBody("<html>private-server-response-marker</html>"))
        val result = repository.fetchQuota() as Result.Failure
        val error = result.error as AppError.ParseError
        assertTrue(!error.message.contains("private-server-response-marker"))
        assertTrue(!error.message.contains("offset"))
    }

    @Test fun `temporary renewal failure keeps credential and recovers on next fetch`() = runTest {
        var current = testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))
        org.mockito.Mockito.doAnswer { current }.`when`(prefsManager).loadCredentialAccount(AiService.CLAUDE, current.id)
        `when`(prefsManager.saveRefreshed(anyArg(), anyArg())).thenAnswer { invocation ->
            current = current.copy(credential = invocation.getArgument<Credential>(1)); true
        }
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AppError.ServiceUnavailable, (repository.fetchQuota() as Result.Failure).error)
        assertEquals("test-refresh-token", current.credential.refreshToken)
        mockWebServer.enqueue(MockResponse().setBody("""{"access_token":"renewed","refresh_token":"rotated","expires_in":3600}"""))
        mockWebServer.enqueue(MockResponse().setBody("""{"five_hour":{"utilization":25}}"""))
        assertTrue(repository.fetchQuota() is Result.Success)
        assertEquals("rotated", current.credential.refreshToken)
        org.mockito.Mockito.verify(prefsManager, org.mockito.Mockito.never()).deleteCredential(AiService.CLAUDE)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test fun `renewal rate limit is not a request to sign in again`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(listOf(testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))))
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        assertEquals(AppError.RateLimited, (repository.fetchQuota() as Result.Failure).error)
    }

    @Test fun `renewed token followed by usage rate limit remains nonterminal`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setBody("""{"access_token":"renewed","expires_in":3600}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        assertEquals(AppError.RateLimited, (repository.fetchQuota() as Result.Failure).error)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test fun `provider usage refusal preserves account and includes status safely`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("private-server-response-marker"))
        val error = (repository.fetchQuota() as Result.Failure).error as AppError.AuthError
        assertTrue(!error.isTerminal)
        assertTrue(error.message.contains("403"))
        assertTrue(!error.message.contains("private-server-response-marker"))
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test fun `invalid grant explicitly requires new login`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(listOf(testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))))
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"private-server-response-marker"}"""))
        val error = (repository.fetchQuota() as Result.Failure).error as AppError.AuthError
        assertTrue(error.isTerminal)
        assertTrue(!error.message.contains("private-server-response-marker"))
    }

    @Test fun `HTML during renewal does not establish invalid grant`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(listOf(testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))))
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("<html>invalid_grant</html>"))
        val error = (repository.fetchQuota() as Result.Failure).error as AppError.AuthError
        assertTrue(!error.isTerminal)
    }

    @Test fun `renewal forbidden by provider is nonterminal`() = runTest {
        `when`(prefsManager.loadCredentialAccounts(AiService.CLAUDE)).thenReturn(listOf(testAccount(testCredential.copy(expiresAt = java.time.Instant.EPOCH))))
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("<html>browser challenge</html>"))
        val error = (repository.fetchQuota() as Result.Failure).error as AppError.AuthError
        assertTrue(!error.isTerminal)
        assertTrue(error.message.contains("renewal"))
    }

    @Test fun `renewed access token rejected with 401 requires login`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setBody("""{"access_token":"renewed","expires_in":3600}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val error = (repository.fetchQuota() as Result.Failure).error as AppError.AuthError
        assertTrue(error.isTerminal)
    }

    private fun testAccount(credential: Credential.ClaudeCredential): CredentialAccount {
        return CredentialAccount(
            id = "claude-test",
            service = AiService.CLAUDE,
            label = "Claude Test",
            credential = credential
        )
    }
}
