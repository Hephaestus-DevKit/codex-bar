package com.codexbar.android.core.data

import com.codexbar.android.PhoneLoginViewModel
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.security.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

private fun <T> callbackArg(): T = org.mockito.ArgumentMatchers.any()

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneLoginViewModelTest {
    @Test fun `browser grant saves without an attached activity observer`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val grant = CountDownLatch(1)
        val started = CountDownLatch(1)
        val client = mock(PhoneLoginClient::class.java)
        val factory = mock(PhoneLoginClientFactory::class.java)
        val prefs = mock(EncryptedPrefsManager::class.java)
        val credential = Credential.CodexCredential("fake", "fake-refresh")
        `when`(factory.create()).thenReturn(client)
        `when`(prefs.sessionEpoch()).thenReturn(7L)
        `when`(prefs.saveNewIfEpoch(AiService.CODEX, credential, 7L)).thenReturn(true)
        doAnswer {
            val showCode = it.getArgument<(String, String) -> Unit>(0)
            showCode("ABCD-EFGH", "https://auth.openai.com/codex/device")
            started.countDown()
            check(grant.await(5, TimeUnit.SECONDS))
            credential
        }.`when`(client).codex(callbackArg())
        val model = PhoneLoginViewModel(prefs, factory)
        try {
            model.start(AiService.CODEX)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals("ABCD-EFGH", model.state.value.code)
            // The screen/browser may be absent: no UI callback is needed to persist the grant.
            grant.countDown()
            withContext(Dispatchers.Default) {
                withTimeout(5000) { model.state.first { it.connected } }
            }
            verify(prefs).saveNewIfEpoch(AiService.CODEX, credential, 7L)
            assertNull(model.state.value.code)
            assertNull(model.state.value.url)
        } finally { grant.countDown(); model.cancel(); Dispatchers.resetMain() }
    }
}
