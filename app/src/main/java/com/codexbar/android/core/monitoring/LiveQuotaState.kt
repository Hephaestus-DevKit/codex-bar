package com.codexbar.android.core.monitoring

import com.codexbar.android.core.domain.model.CredentialAccount
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.security.EncryptedPrefsManager
import javax.inject.Inject
import javax.inject.Singleton

data class AccountReading(val account: CredentialAccount, val quota: QuotaInfo? = null, val error: String? = null)

@Singleton
class LiveQuotaState @Inject constructor(private val prefs: EncryptedPrefsManager) {
    private val values = mutableMapOf<String, AccountReading>()

    @Synchronized fun record(account: CredentialAccount, result: Result<QuotaInfo, AppError>) {
        prefs.runIfCurrent(account) {
            val key = QuotaChip.id(account.service.name, account.id)
            val previous = values[key]?.takeIf { it.account.generation == account.generation }
            values[key] = when (result) {
                is Result.Success -> AccountReading(account, result.value)
                is Result.Failure -> AccountReading(account, previous?.quota, when (result.error) {
                    is AppError.AuthError -> if (result.error.isTerminal) "Reconnect this account" else "Provider refused update; account retained, retry later"
                    is AppError.RateLimited -> "Provider rate limit; waiting before retry"
                    else -> "Update failed; last successful reading retained"
                })
            }
        }
    }

    @Synchronized fun readings(): List<AccountReading> {
        val accounts = listOf(AiService.CODEX, AiService.CLAUDE).flatMap { prefs.loadCredentialAccounts(it) }
        val ids = accounts.map { QuotaChip.id(it.service.name, it.id) }.toSet()
        values.keys.retainAll(ids)
        return accounts.map { account ->
            values[QuotaChip.id(account.service.name, account.id)]
                ?.takeIf { it.account.generation == account.generation } ?: AccountReading(account)
        }
    }
}
