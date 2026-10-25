package com.codexbar.android.core.network.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class ClaudeRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("client_id") val clientId: String = ClaudeTokenRefreshService.CLAUDE_CLIENT_ID
)

interface ClaudeTokenRefreshService {
    @POST("v1/oauth/token")
    suspend fun refreshToken(@Body body: ClaudeRefreshRequest): Response<ClaudeDto.TokenRefreshResponse>
    companion object {
        const val BASE_URL = "https://platform.claude.com/"
        const val CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    }
}
