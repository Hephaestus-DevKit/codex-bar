package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.ExtraUsage
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.claude.ClaudeApiService
import com.codexbar.android.core.network.claude.ClaudeDto
import com.codexbar.android.core.network.claude.ClaudeTokenRefreshService
import com.codexbar.android.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class ClaudeRepositoryImpl @Inject constructor(
    private val apiService: ClaudeApiService,
    private val tokenRefreshService: ClaudeTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = prefsManager.loadCredentialAccounts(AiService.CLAUDE).firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CLAUDE))
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
        val credential = account.credential as? Credential.ClaudeCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CLAUDE))

        val workingCredential = when (val valid = ensureValidToken(account, credential)) {
            is Result.Success -> valid.value
            is Result.Failure -> return valid
        }

        return try {
            val response = apiService.getUsage("Bearer ${workingCredential.accessToken}")

            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    val discoveredTier = extractTierFromHeaders(response)
                        ?: extractTierFromJwt(workingCredential.accessToken)
                    val effectiveTier = discoveredTier ?: workingCredential.rateLimitTier
                    if (discoveredTier != null && discoveredTier != workingCredential.rateLimitTier) {
                        prefsManager.saveRefreshed(account.copy(credential = workingCredential), workingCredential.copy(rateLimitTier = discoveredTier))
                    }
                    Result.Success(mapToQuotaInfo(body, effectiveTier, account))
                }
                401 -> {
                    // Try refresh once, then retry
                    val refreshed = when (val renewal = refreshToken(account, workingCredential)) {
                        is Result.Success -> renewal.value
                        is Result.Failure -> return renewal
                    }
                    val retryResponse = apiService.getUsage("Bearer ${refreshed.accessToken}")
                    if (retryResponse.isSuccessful) {
                        val body = retryResponse.body()
                            ?: return Result.Failure(AppError.ParseError("Empty response body"))
                        val discoveredTier = extractTierFromHeaders(retryResponse)
                            ?: extractTierFromJwt(refreshed.accessToken)
                        val effectiveTier = discoveredTier ?: refreshed.rateLimitTier
                        if (discoveredTier != null && discoveredTier != refreshed.rateLimitTier) {
                            prefsManager.saveRefreshed(account.copy(credential = refreshed), refreshed.copy(rateLimitTier = discoveredTier))
                        }
                        Result.Success(mapToQuotaInfo(body, effectiveTier, account))
                    } else {
                        usageFailure(retryResponse.code())
                    }
                }
                else -> usageFailure(response.code())
            }
        } catch (e: CancellationException) { throw e
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError("Claude usage request failed; account is still saved"))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("Claude returned an unsupported usage response. Account is still saved; retry later."))
        }
    }

    private fun usageFailure(status: Int): Result.Failure<AppError> = Result.Failure(when (status) {
        401 -> AppError.AuthError(AiService.CLAUDE, true, "Claude rejected the renewed sign-in (HTTP 401). Reconnect this account.")
        403 -> AppError.AuthError(AiService.CLAUDE, false, "Claude refused the usage request (HTTP 403). Account is still saved; retry later.")
        429 -> AppError.RateLimited
        in 500..599 -> AppError.ServiceUnavailable
        else -> AppError.NetworkError("Claude usage HTTP $status")
    })

    private suspend fun ensureValidToken(
        account: CredentialAccount,
        credential: Credential.ClaudeCredential
    ): Result<Credential.ClaudeCredential, AppError> {
        val expiresAt = credential.expiresAt
        if (expiresAt == null || Instant.now().isBefore(expiresAt.minusSeconds(60))) return Result.Success(credential)
        return refreshToken(account, credential)
    }

    private suspend fun refreshToken(
        account: CredentialAccount,
        credential: Credential.ClaudeCredential
    ): Result<Credential.ClaudeCredential, AppError> {
        val refreshToken = credential.refreshToken
            ?: return Result.Failure(AppError.AuthError(AiService.CLAUDE, true))
        return try {
            val response = tokenRefreshService.refreshToken(com.codexbar.android.core.network.claude.ClaudeRefreshRequest(refreshToken))
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.Failure(AppError.ParseError("Claude renewal returned no data; account is still saved"))
                require(body.accessToken.isNotBlank() && body.expiresIn > 0)
                val newCredential = Credential.ClaudeCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: refreshToken,
                    expiresAt = Instant.now().plusSeconds(body.expiresIn.toLong()),
                    scopes = credential.scopes,
                    rateLimitTier = credential.rateLimitTier
                )
                if (!prefsManager.saveRefreshed(account.copy(credential = credential), newCredential)) {
                    return Result.Failure(AppError.CredentialNotFound(AiService.CLAUDE))
                }
                Result.Success(newCredential)
            } else {
                Result.Failure(when (response.code()) {
                    401 -> AppError.AuthError(AiService.CLAUDE, true, "Claude rejected sign-in renewal (HTTP 401). Reconnect this account.")
                    400 -> if (isInvalidGrant(response)) AppError.AuthError(AiService.CLAUDE, true,
                        "Claude revoked or expired this sign-in. Reconnect this account.")
                        else AppError.AuthError(AiService.CLAUDE, false,
                            "Claude renewal failed (HTTP 400). Account is still saved; retry later.")
                    429 -> AppError.RateLimited
                    in 500..599 -> AppError.ServiceUnavailable
                    else -> AppError.AuthError(AiService.CLAUDE, false,
                        "Claude renewal refused (HTTP ${response.code()}). Account is still saved; retry later.")
                })
            }
        } catch (e: CancellationException) { throw e
        } catch (_: IOException) {
            Result.Failure(AppError.NetworkError("Claude renewal could not reach the provider; account is still saved"))
        } catch (_: Exception) {
            Result.Failure(AppError.ParseError("Claude renewal returned an unsupported response; account is still saved"))
        }
    }

    /** Only an explicit, bounded OAuth error can turn a 400 into a reconnect instruction. */
    private fun isInvalidGrant(response: retrofit2.Response<*>): Boolean = runCatching {
        response.errorBody()?.use { body ->
            val source = body.source()
            source.request(16_385)
            if (source.buffer.size > 16_384) return@use false
            val error = Json.parseToJsonElement(source.readUtf8()).jsonObject["error"]
            val type = when (error) {
                is JsonPrimitive -> error.content
                is JsonObject -> (error["type"] as? JsonPrimitive)?.content
                else -> null
            }
            type == "invalid_grant"
        } == true
    }.getOrDefault(false)

    private fun extractTierFromHeaders(response: retrofit2.Response<*>): String? {
        val raw = response.headers()["anthropic-ratelimit-tier"]
            ?: response.headers()["x-ratelimit-tier"]
            ?: return null
        return raw.trim().replaceFirstChar { it.uppercase() }
    }

    private fun extractTierFromJwt(accessToken: String): String? {
        return try {
            val parts = accessToken.split(".")
            if (parts.size != 3) return null
            val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(payload).jsonObject
            val tier = obj["rate_limit_tier"]?.jsonPrimitive?.content
                ?: obj["rateLimitTier"]?.jsonPrimitive?.content
                ?: return null
            tier.replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToQuotaInfo(
        response: ClaudeDto.OAuthUsageResponse,
        tier: String? = null,
        account: CredentialAccount
    ): QuotaInfo {
        val windows = buildList {
            response.fiveHour?.let { add(mapWindow("5-Hour", it)) }
            response.sevenDay?.let { add(mapWindow("7-Day", it)) }
            response.sevenDayOauthApps?.let { add(mapWindow("OAuth Apps", it)) }
            response.sevenDayOpus?.let { add(mapWindow("Opus", it)) }
            response.sevenDaySonnet?.let { add(mapWindow("Sonnet", it)) }
            response.iguanaNecktie?.let { add(mapWindow("Extended", it)) }
        }

        require(windows.isNotEmpty()) { "No usage windows returned" }
        val extraUsage = response.extraUsage?.takeIf { it.isEnabled && it.monthlyLimit != null && it.usedCredits != null && it.utilization != null }?.let {
            ExtraUsage(
                isEnabled = it.isEnabled,
                monthlyLimit = requireNotNull(it.monthlyLimit),
                usedCredits = requireNotNull(it.usedCredits),
                utilization = requireNotNull(it.utilization),
                currency = it.currency ?: "USD"
            )
        }

        return QuotaInfo(
            service = AiService.CLAUDE,
            accountId = account.id,
            accountLabel = account.label,
            windows = windows,
            extraUsage = extraUsage,
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private fun mapWindow(label: String, window: ClaudeDto.OAuthUsageWindow): UsageWindow {
        val usage = window.utilization ?: throw IllegalArgumentException("Missing usage value")
        require(usage.isFinite() && usage in 0.0..100.0) { "Invalid usage value" }
        return UsageWindow(
            label = label,
            utilization = usage / 100.0,
            resetsAt = window.resetsAt?.let { parseInstant(it) }
        )
    }

    private fun parseInstant(isoString: String): Instant? {
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            null
        }
    }
}
