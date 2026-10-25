package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.CredentialAccount
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI, WorkManager and live monitoring share the same token-rotation lock. */
object AccountLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withAccount(account: CredentialAccount, block: suspend () -> T): T =
        locks.getOrPut("${account.service}:${account.id}") { Mutex() }.withLock { block() }
}
