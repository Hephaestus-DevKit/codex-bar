package com.codexbar.android.core.data

import com.codexbar.android.core.security.*
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PhoneLoginClientTest {
    private fun client(reply: (Request) -> Pair<Int, String>): PhoneLoginClient = PhoneLoginClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    )

    @Test fun `Codex polls pending authorization then exchanges using verifier`() {
        var polls = 0
        val seen = mutableListOf<String>()
        val login = client { request ->
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            val payload = buffer.readUtf8()
            seen += request.url.encodedPath
            when(request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> 200 to """{"device_auth_id":"fake-id","user_code":"ABCD-EFGH","interval":1}"""
                "/api/accounts/deviceauth/token" -> {
                    assertTrue(payload.contains("fake-id")); assertTrue(payload.contains("ABCD-EFGH"))
                    if (++polls == 1) 403 to "{}" else 200 to """{"authorization_code":"fake-code","code_verifier":"fake-verifier"}"""
                }
                "/oauth/token" -> {
                    assertTrue(payload.contains("code_verifier=fake-verifier"))
                    assertTrue(payload.contains("grant_type=authorization_code"))
                    200 to """{"access_token":"fake-access","refresh_token":"fake-refresh"}"""
                }
                else -> error("Unexpected endpoint")
            }
        }
        try {
            val credential = login.codex { code, url -> assertEquals("ABCD-EFGH", code); assertEquals("https://auth.openai.com/codex/device", url) }
            assertEquals("fake-refresh", credential.refreshToken)
            assertEquals(2, polls)
            assertEquals("/oauth/token", seen.last())
        } finally { login.cancel() }
    }

    @Test fun `Claude manual fallback rejects foreign state and exchanges own callback`() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var login: PhoneLoginClient
        login = client { request ->
            assertEquals("https://platform.claude.com/v1/oauth/token", request.url.toString())
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = org.json.JSONObject(buffer.readUtf8())
            assertEquals("fake-claude-code", body.getString("code"))
            assertEquals(OAuthPkce.REDIRECT_URI, body.getString("redirect_uri"))
            assertTrue(body.getString("code_verifier").length >= 43)
            200 to """{"access_token":"fake-access","refresh_token":"fake-refresh","expires_in":3600,"scope":"user:profile"}"""
        }
        try {
            val future = executor.submit<Boolean> {
                val credential = login.claude { url ->
                    val params = URI(url).rawQuery.split('&').associate { val p = it.split('=', limit = 2); p[0] to URLDecoder.decode(p[1], "UTF-8") }
                    assertEquals("user:profile", params["scope"])
                    assertFalse(login.submitClaudeCallback("fake-claude-code#wrong"))
                    assertTrue(login.submitClaudeCallback("http://localhost:53692/callback?code=fake-claude-code&state=${params["state"]}"))
                }
                credential.refreshToken == "fake-refresh"
            }
            assertTrue(future.get(5, TimeUnit.SECONDS))
        } finally { login.cancel(); executor.shutdownNow() }
    }

    @Test fun `malformed sign-in JSON does not expose response body`() {
        val login = client { 200 to "<html>private-response-marker</html>" }
        try {
            login.codex { _, _ -> fail("Should not open browser") }
            fail("Expected safe error")
        } catch (e: LoginResponseError) {
            assertFalse(e.message.orEmpty().contains("private-response-marker"))
        } finally { login.cancel() }
    }

    @Test fun `manual fallback never accepts bare code or mismatched callback origin`() {
        val pkce = OAuthPkce()
        assertNull(pkce.manualCode("bare-code"))
        assertNull(pkce.manualCode("http://evil.test:53692/callback?code=secret&state=${pkce.state}"))
        assertNull(pkce.manualCode("secret#wrong"))
        assertEquals("secret", pkce.manualCode("secret#${pkce.state}"))
    }
}
