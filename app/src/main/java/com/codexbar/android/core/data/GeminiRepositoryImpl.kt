package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.gemini.GeminiApiService
import com.codexbar.android.core.network.gemini.GeminiDto
import com.codexbar.android.core.network.gemini.GeminiTokenRefreshService
import com.codexbar.android.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class GeminiRepositoryImpl @Inject constructor(
    private val apiService: GeminiApiService,
    private val tokenRefreshService: GeminiTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = prefsManager.loadCredentialAccounts(AiService.GEMINI).firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.GEMINI))
        return fetchQuota(account)
    }

    override suspend fun fetchQuota(account: CredentialAccount): Result<QuotaInfo, AppError> =
        AccountLocks.withAccount(account) {
            val current = prefsManager.loadCredentialAccount(account.service, account.id)
                ?: return@withAccount Result.Failure(AppError.CredentialNotFound(account.service))
            if (current.generation != account.generation) return@withAccount Result.Failure(AppError.CredentialNotFound(account.service))
            fetchCurrent(current)
        }

    private suspend fun fetchCurrent(account: CredentialAccount): Result<QuotaInfo, AppError> {
        val credential = account.credential as? Credential.GeminiCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.GEMINI))

        val workingCredential = ensureValidToken(account, credential)
            ?: return Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))

        return try {
            val result = fetchQuotaWithToken(account, workingCredential)
            if (result is Result.Failure && result.error is AppError.AuthError) {
                // Token might be expired despite expiresAtMs - try refresh once
                val refreshed = refreshToken(account, workingCredential)
                if (refreshed != null) {
                    fetchQuotaWithToken(account, refreshed)
                } else {
                    Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))
                }
            } else {
                result
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
        }
    }

    private suspend fun fetchQuotaWithToken(
        account: CredentialAccount,
        credential: Credential.GeminiCredential
    ): Result<QuotaInfo, AppError> {
        // Step 1: loadCodeAssist to get projectId and tier
        val loadResponse = apiService.loadCodeAssist(
            authorization = "Bearer ${credential.accessToken}",
            body = GeminiDto.LoadCodeAssistRequest()
        )

        if (!loadResponse.isSuccessful) {
            return handleErrorResponse(loadResponse.code())
        }

        val loadBody = loadResponse.body()
            ?: return Result.Failure(AppError.ParseError("Empty loadCodeAssist response"))

        val projectId = extractProjectId(loadBody)
        val tierRaw = loadBody.currentTier?.id
        val tier = mapTier(tierRaw)

        // Step 2: retrieveUserQuota
        val quotaRequest = GeminiDto.RetrieveUserQuotaRequest(
            project = projectId ?: ""
        )
        val quotaResponse = apiService.retrieveUserQuota(
            authorization = "Bearer ${credential.accessToken}",
            body = quotaRequest
        )

        if (!quotaResponse.isSuccessful) {
            return handleErrorResponse(quotaResponse.code())
        }

        val quotaBody = quotaResponse.body()
            ?: return Result.Failure(AppError.ParseError("Empty quota response"))

        return Result.Success(mapToQuotaInfo(quotaBody, tier, account))
    }

    private fun extractProjectId(response: GeminiDto.LoadCodeAssistResponse): String? {
        val element = response.cloudaicompanionProject ?: return null
        // cloudaicompanionProject can be String or Object {"id": "..."}
        return when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> element["id"]?.jsonPrimitive?.content
            else -> null
        }
    }

    private fun mapTier(tierId: String?): String? {
        return when (tierId) {
            "free-tier" -> "Free"
            "standard-tier" -> "Paid"
            "legacy-tier" -> "Legacy"
            null -> null
            else -> tierId
        }
    }

    private fun mapToQuotaInfo(
        response: GeminiDto.QuotaResponse,
        tier: String?,
        account: CredentialAccount
    ): QuotaInfo {
        // Group all buckets into "Flash" vs "Pro" categories
        val groups = response.buckets.groupBy { bucket ->
            if (bucket.modelId.contains("flash", ignoreCase = true)) "Flash" else "Pro"
        }

        // Per group: worst-case utilization, earliest reset time
        val windows = listOfNotNull(
            groups["Pro"]?.let { buckets ->
                UsageWindow(
                    label = "Pro",
                    utilization = buckets.maxOf { (1.0 - it.remainingFraction).coerceIn(0.0, 1.0) },
                    resetsAt = buckets.mapNotNull { it.resetTime?.let { ts -> parseInstant(ts) } }.minOrNull()
                )
            },
            groups["Flash"]?.let { buckets ->
                UsageWindow(
                    label = "Flash",
                    utilization = buckets.maxOf { (1.0 - it.remainingFraction).coerceIn(0.0, 1.0) },
                    resetsAt = buckets.mapNotNull { it.resetTime?.let { ts -> parseInstant(ts) } }.minOrNull()
                )
            }
        )

        return QuotaInfo(
            service = AiService.GEMINI,
            accountId = account.id,
            accountLabel = account.label,
            windows = windows,
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private suspend fun ensureValidToken(
        account: CredentialAccount,
        credential: Credential.GeminiCredential
    ): Credential.GeminiCredential? {
        if (System.currentTimeMillis() < credential.expiresAtMs - 60_000) {
            return credential
        }
        return refreshToken(account, credential)
    }

    private suspend fun refreshToken(
        account: CredentialAccount,
        credential: Credential.GeminiCredential
    ): Credential.GeminiCredential? {
        return try {
            val request = GeminiDto.TokenRefreshRequest(
                refreshToken = credential.refreshToken,
                clientId = credential.oauthClientId,
                clientSecret = credential.oauthClientSecret
            )
            val response = tokenRefreshService.refreshToken(request)
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                val expiresIn = body.expiresIn ?: 3600
                val newCredential = Credential.GeminiCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: credential.refreshToken,
                    expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                    oauthClientId = credential.oauthClientId,
                    oauthClientSecret = credential.oauthClientSecret
                )
                if (!prefsManager.saveRefreshed(account.copy(credential = credential), newCredential)) return null
                newCredential
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> handleErrorResponse(code: Int): Result<T, AppError> {
        return when (code) {
            401 -> Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = false))
            429 -> Result.Failure(AppError.RateLimited)
            503 -> Result.Failure(AppError.ServiceUnavailable)
            else -> Result.Failure(AppError.NetworkError("HTTP $code"))
        }
    }

    private fun parseInstant(isoString: String): Instant? {
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            null
        }
    }
}
