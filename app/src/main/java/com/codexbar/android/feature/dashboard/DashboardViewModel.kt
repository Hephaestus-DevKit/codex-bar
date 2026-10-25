package com.codexbar.android.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.di.ClaudeRepository
import com.codexbar.android.di.CodexRepository
import com.codexbar.android.di.GeminiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ClaudeRepository private val claudeRepository: QuotaRepository,
    @CodexRepository private val codexRepository: QuotaRepository,
    @GeminiRepository private val geminiRepository: QuotaRepository,
    private val liveState: com.codexbar.android.core.monitoring.LiveQuotaState,
    private val chips: com.codexbar.android.core.monitoring.ChipNotifications,
    private val prefsManager: EncryptedPrefsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var lastRefreshFinishedAt: Instant? = null
    private var lastAccountKeys: Set<String> = emptySet()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (_isRefreshing.value) return@launch
            _isRefreshing.value = true
            _uiState.value = DashboardUiState.Loading

            val accounts = AiService.entries.flatMap { service ->
                prefsManager.loadCredentialAccounts(service)
            }
            val accountKeys = accounts.map { "${it.service.name}:${it.id}" }.toSet()

            if (accounts.isEmpty()) {
                _uiState.value = DashboardUiState.Success(emptyList(), Instant.now())
                _isRefreshing.value = false
                lastRefreshFinishedAt = Instant.now()
                lastAccountKeys = emptySet()
                return@launch
            }

            val deferreds = accounts.map { account ->
                async { account to repositoryFor(account.service).fetchQuota(account) }
            }

            val results = deferreds.map { it.await() }

            results.forEach { (account, result) -> liveState.record(account, result) }
            chips.publish(liveState.readings())
            val successCards = mutableListOf<ServiceCardData>()
            val errors = mutableMapOf<String, AppError>()

            for ((account, result) in results) {
                if (!prefsManager.isCurrent(account)) continue
                when (result) {
                    is Result.Success -> {
                        successCards.add(mapToCardData(result.value))
                    }
                    is Result.Failure -> {
                        errors[account.displayName] = result.error
                        successCards.add(
                            ServiceCardData(
                                service = account.service,
                                accountId = account.id,
                                accountLabel = account.label,
                                windows = emptyList(),
                                extraUsage = null,
                                tier = null,
                                fetchedAt = Instant.now(),
                                error = result.error
                            )
                        )
                    }
                }
            }

            // Sort by highest utilization first
            val sortedCards = successCards.sortedByDescending { card ->
                card.windows.maxOfOrNull { it.utilization } ?: 0.0
            }

            _uiState.value = if (errors.isEmpty()) {
                DashboardUiState.Success(sortedCards, Instant.now())
            } else {
                DashboardUiState.PartialSuccess(sortedCards, errors)
            }

            _isRefreshing.value = false
            lastRefreshFinishedAt = Instant.now()
            lastAccountKeys = accountKeys
        }
    }

    fun refreshIfStale(maxAgeSeconds: Long = 60L) {
        val currentAccountKeys = AiService.entries.flatMap { service ->
            prefsManager.loadCredentialAccounts(service).map { "${it.service.name}:${it.id}" }
        }.toSet()
        val lastRefresh = lastRefreshFinishedAt
        if (
            lastRefresh == null ||
            currentAccountKeys != lastAccountKeys ||
            Duration.between(lastRefresh, Instant.now()).seconds >= maxAgeSeconds
        ) {
            refresh()
        }
    }

    fun deleteAccount(service: AiService, accountId: String) {
        prefsManager.deleteCredential(service, accountId)
        refresh()
    }

    private fun mapToCardData(quotaInfo: QuotaInfo): ServiceCardData {
        return ServiceCardData(
            service = quotaInfo.service,
            accountId = quotaInfo.accountId,
            accountLabel = quotaInfo.accountLabel ?: quotaInfo.service.displayName,
            windows = quotaInfo.windows.map { window ->
                UsageWindowUi(
                    label = window.label,
                    utilization = window.utilization,
                    resetsAt = window.resetsAt
                )
            },
            extraUsage = quotaInfo.extraUsage?.let { extra ->
                ExtraUsageUi(
                    monthlyLimit = extra.monthlyLimit,
                    usedCredits = extra.usedCredits,
                    utilization = extra.utilization,
                    currency = extra.currency
                )
            },
            tier = quotaInfo.tier,
            fetchedAt = quotaInfo.fetchedAt
        )
    }

    private fun repositoryFor(service: AiService): QuotaRepository {
        return when (service) {
            AiService.CLAUDE -> claudeRepository
            AiService.CODEX -> codexRepository
            AiService.GEMINI -> geminiRepository
        }
    }
}
