package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.codex.CodexApiService
import com.codexbar.android.core.network.codex.CodexDto
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class CodexRepositoryImpl @Inject constructor(
    private val apiService: CodexApiService,
    private val tokenRefreshService: CodexTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = prefsManager.loadCredentialAccounts(AiService.CODEX).firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CODEX))
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
        val credential = account.credential as? Credential.CodexCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CODEX))

        return try {
            val response = apiService.getUsage(
                authorization = "Bearer ${credential.accessToken}",
                accountId = credential.accountId
            )

            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body, account))
                }
                401 -> {
                    val refreshed = refreshToken(account, credential)
                    if (refreshed != null) {
                        val retryResponse = apiService.getUsage(
                            authorization = "Bearer ${refreshed.accessToken}",
                            accountId = refreshed.accountId
                        )
                        if (retryResponse.isSuccessful) {
                            val body = retryResponse.body()
                                ?: return Result.Failure(AppError.ParseError("Empty response body"))
                            Result.Success(mapToQuotaInfo(body, account))
                        } else {
                            Result.Failure(AppError.AuthError(AiService.CODEX, isTerminal = true))
                        }
                    } else {
                        Result.Failure(AppError.AuthError(AiService.CODEX, isTerminal = true))
                    }
                }
                429 -> Result.Failure(AppError.RateLimited)
                else -> Result.Failure(
                    AppError.NetworkError("HTTP ${response.code()}: ${response.message()}")
                )
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("OpenAI returned an unsupported usage response. Reconnect or retry later."))
        }
    }

    private suspend fun refreshToken(
        account: CredentialAccount,
        credential: Credential.CodexCredential
    ): Credential.CodexCredential? {
        return try {
            val request = CodexDto.TokenRefreshRequest(refreshToken = credential.refreshToken)
            val response = tokenRefreshService.refreshToken(request)

            if (response.isSuccessful) {
                val body = response.body() ?: return null
                val newCredential = Credential.CodexCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: credential.refreshToken,
                    accountId = credential.accountId
                )
                if (!prefsManager.saveRefreshed(account.copy(credential = credential), newCredential)) return null
                newCredential
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val isTerminal = CodexDto.TERMINAL_ERROR_CODES.any { errorBody.contains(it) }
                // Preserve the account for an explicit reconnect; never delete a replacement.
                if (isTerminal) return null
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToQuotaInfo(response: CodexDto.UsageResponse, account: CredentialAccount): QuotaInfo {
        val windows = buildList {
            response.rateLimit?.primaryWindow?.let { window ->
                add(mapRateLimitWindow("primary", window))
            }
            response.rateLimit?.secondaryWindow?.let { window ->
                add(mapRateLimitWindow("secondary", window))
            }
        }

        require(windows.isNotEmpty()) { "No usage windows returned" }
        return QuotaInfo(
            service = AiService.CODEX,
            accountId = account.id,
            accountLabel = account.label,
            windows = windows,
            extraUsage = null,
            tier = response.planType?.replaceFirstChar { it.uppercase() },
            fetchedAt = Instant.now()
        )
    }

    private fun mapRateLimitWindow(type: String, window: CodexDto.RateLimitWindow): UsageWindow {
        val label = when (window.limitWindowSeconds) {
            18000L -> "5-Hour"
            604800L -> "7-Day"
            else -> {
                val seconds = window.limitWindowSeconds ?: 0L
                if (seconds > 0) "${seconds / 3600}h" else type.replaceFirstChar { it.uppercase() }
            }
        }

        require(window.usedPercent.isFinite() && window.usedPercent in 0.0..100.0) { "Invalid usage value" }
        return UsageWindow(
            label = label,
            utilization = (window.usedPercent / 100.0).coerceIn(0.0, 1.0),
            resetsAt = window.resetAt?.let { Instant.ofEpochSecond(it) }
        )
    }

    companion object {
        fun parseBalance(element: kotlinx.serialization.json.JsonElement?): Double? {
            if (element == null) return null
            return when (element) {
                is JsonPrimitive -> {
                    element.doubleOrNull ?: element.content.toDoubleOrNull()
                }
                else -> null
            }
        }
    }
}
