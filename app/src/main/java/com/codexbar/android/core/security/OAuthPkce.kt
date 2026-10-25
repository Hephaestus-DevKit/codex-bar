package com.codexbar.android.core.security

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A fresh, app-owned grant; no credentials are copied from another CLI. */
class OAuthPkce {
    val verifier: String = randomValue()
    val state: String = randomValue()
    val challenge: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(): String = "https://claude.ai/oauth/authorize?" + mapOf(
        "code" to "true", "client_id" to CLAUDE_CLIENT_ID, "response_type" to "code",
        "redirect_uri" to REDIRECT_URI, "scope" to "user:profile",
        "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state
    ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    fun callbackCode(target: String): String? = runCatching {
        require(target.length <= 4096)
        val uri = URI(target)
        require(uri.path == "/callback")
        require((uri.isAbsolute || uri.rawAuthority == null) && (!uri.isAbsolute || (uri.scheme == "http" && uri.host == "localhost" && uri.port == 53692)))
        val pairs = uri.rawQuery.orEmpty().split("&").map {
            val p = it.split("=", limit = 2)
            require(p.size == 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val params = pairs.toMap()
        require(!params.containsKey("error"))
        require(MessageDigest.isEqual(state.toByteArray(), params["state"].orEmpty().toByteArray()))
        params["code"]?.takeIf { it.isNotBlank() && it.length <= 2048 }
    }.getOrNull()

    /** Manual fallback still requires the exact state from this attempt. */
    fun manualCode(input: String): String? {
        val value = input.trim()
        if (value.startsWith("http://")) return callbackCode(value)
        val parts = value.split('#')
        if (parts.size != 2 || parts[0].isBlank() || parts[0].length > 2048) return null
        return parts[0].takeIf { MessageDigest.isEqual(state.toByteArray(), parts[1].toByteArray()) }
    }

    companion object {
        const val CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val REDIRECT_URI = "http://localhost:53692/callback"
        private fun randomValue(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
