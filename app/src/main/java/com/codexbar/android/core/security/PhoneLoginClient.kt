package com.codexbar.android.core.security

import com.codexbar.android.core.domain.model.Credential
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Only fixed provider HTTPS origins. Error bodies, tokens and callback URLs are never logged. */
class PhoneLoginClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
) {
    @Volatile private var callback: ServerSocket? = null
    @Volatile private var cancelled = false
    @Volatile private var activePkce: OAuthPkce? = null
    private val manualCodes = java.util.concurrent.ArrayBlockingQueue<String>(1)
    fun submitClaudeCallback(input: String): Boolean {
        val code = activePkce?.manualCode(input) ?: return false
        return !cancelled && manualCodes.offer(code)
    }

    fun cancel() { cancelled = true; runCatching { callback?.close() }; client.dispatcher.cancelAll() }

    private fun post(url: String, data: JSONObject): JSONObject = execute(
        Request.Builder().url(url).post(data.toString().toRequestBody("application/json".toMediaType())).build()
    )

    private fun stage(request: Request): String = when (request.url.encodedPath) {
        "/api/accounts/deviceauth/usercode" -> "OpenAI device code"
        "/api/accounts/deviceauth/token" -> "OpenAI device confirmation"
        "/oauth/token" -> "OpenAI token exchange"
        else -> "Claude token exchange"
    }

    private fun execute(request: Request): JSONObject {
        check(!cancelled) { "Sign-in cancelled" }
        return client.newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use {
            if (!it.isSuccessful) throw LoginHttpError(it.code, stage(request))
            val body = it.body ?: error("Empty authorization response")
            require(body.contentLength() <= 1_048_576) { "Authorization response too large" }
            val source = body.source()
            source.request(1_048_577)
            require(source.buffer.size <= 1_048_576) { "Authorization response too large" }
            val type = body.contentType()
            if (type?.subtype?.contains("json") != true) throw LoginResponseError(stage(request))
            try { JSONObject(source.readUtf8()) } catch (_: org.json.JSONException) { throw LoginResponseError(stage(request)) }
        }
    }

    fun claude(openBrowser: (String) -> Unit): Credential.ClaudeCredential {
        val pkce = OAuthPkce()
        activePkce = pkce
        val socket = ServerSocket(53692, 4, InetAddress.getByName("127.0.0.1"))
        callback = socket
        socket.soTimeout = 1000
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        try {
            openBrowser(pkce.authorizeUrl())
            while (!cancelled && System.nanoTime() < deadline) {
                var code: String? = manualCodes.poll()
                val incoming = if (code == null) try { socket.accept() } catch (_: SocketTimeoutException) { continue } else null
                try { incoming?.use { peer ->
                    peer.soTimeout = 3000
                    val firstLine = StringBuilder()
                    while (firstLine.length <= 4096) {
                        val b = peer.getInputStream().read()
                        if (b < 0 || b == 10) break
                        firstLine.append(b.toChar())
                    }
                    val parts = firstLine.toString().trim().split(' ')
                    if (parts.size == 3 && parts[0] == "GET") code = pkce.callbackCode(parts[1])
                    val message = if (code != null) "Sign-in received. Return to AI Quota." else "Invalid sign-in response. Return to AI Quota and retry."
                    val body = "<!doctype html><meta name=referrer content=no-referrer><p>$message</p>".toByteArray()
                    val status = if (code != null) "200 OK" else "400 Bad Request"
                    runCatching {
                        peer.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                    }
                }
                } catch (_: java.io.IOException) { continue }
                if (code != null) {
                    val token = post("https://platform.claude.com/v1/oauth/token", JSONObject()
                        .put("grant_type", "authorization_code").put("client_id", OAuthPkce.CLAUDE_CLIENT_ID)
                        .put("code", code).put("state", pkce.state).put("redirect_uri", OAuthPkce.REDIRECT_URI)
                        .put("code_verifier", pkce.verifier))
                    val scopes = token.optString("scope")
                    require(scopes.isBlank() || "user:profile" in scopes.split(' ')) { "Usage permission was not granted" }
                    return Credential.ClaudeCredential(
                        token.getString("access_token"), token.getString("refresh_token"),
                        Instant.now().plusSeconds(token.getLong("expires_in")), scopes = scopes.ifBlank { "user:profile" }
                    )
                }
            }
            error("Sign-in expired or cancelled; try again")
        } finally { socket.close(); callback = null; activePkce = null; manualCodes.clear() }
    }

    fun codex(showCode: (String, String) -> Unit): Credential.CodexCredential {
        val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
        val code = post("https://auth.openai.com/api/accounts/deviceauth/usercode", JSONObject().put("client_id", clientId))
        showCode(code.optString("user_code", code.optString("usercode")), "https://auth.openai.com/codex/device")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15)
        val interval = code.optLong("interval", 5).coerceIn(1, 30)
        while (!cancelled && System.nanoTime() < deadline) {
            Thread.sleep(interval * 1000)
            val pending = try {
                post("https://auth.openai.com/api/accounts/deviceauth/token", JSONObject()
                    .put("device_auth_id", code.getString("device_auth_id"))
                    .put("user_code", code.optString("user_code", code.optString("usercode"))))
            } catch (e: LoginHttpError) {
                if (e.status == 403 || e.status == 404) continue else throw e
            }
            val form = FormBody.Builder().add("grant_type", "authorization_code")
                .add("client_id", clientId).add("code", pending.getString("authorization_code"))
                .add("redirect_uri", "https://auth.openai.com/deviceauth/callback")
                .add("code_verifier", pending.getString("code_verifier")).build()
            val token = execute(Request.Builder().url("https://auth.openai.com/oauth/token").post(form).build())
            val access = token.getString("access_token")
            val accountId = listOf(token.optString("id_token"), access).firstNotNullOfOrNull { jwt ->
                runCatching {
                    JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1])))
                        .optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            return Credential.CodexCredential(access, token.getString("refresh_token"), accountId)
        }
        error("Sign-in expired or cancelled; try again")
    }
}

class LoginHttpError(val status: Int, val stage: String) : Exception("Provider rejected sign-in (HTTP $status)")

class LoginResponseError(val stage: String) : Exception("Provider sign-in response was not a JSON object")

class PhoneLoginClientFactory @javax.inject.Inject constructor() {
    fun create(): PhoneLoginClient = PhoneLoginClient()
}
