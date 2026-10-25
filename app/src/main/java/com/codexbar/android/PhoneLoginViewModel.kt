package com.codexbar.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.security.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the pending browser grant across activity recreation. Never saves secrets to a Bundle. */
@HiltViewModel
class PhoneLoginViewModel @Inject constructor(private val prefs: EncryptedPrefsManager, private val loginClients: PhoneLoginClientFactory) : ViewModel() {
    data class State(
        val message: String = "No developer server, analytics or Google Play services are required.",
        val url: String? = null,
        val code: String? = null,
        val service: AiService? = null,
        val connected: Boolean = false
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var client: PhoneLoginClient? = null
    private var job: Job? = null
    var browserPresented = false
        private set
    fun markBrowserPresented() { browserPresented = true }

    fun start(service: AiService) {
        if (job?.isActive == true) return
        val epoch = prefs.sessionEpoch()
        val attempt = loginClients.create().also { client = it }
        browserPresented = false
        mutable.value = State("Preparing official provider sign-in…", service = service)
        job = viewModelScope.launch {
            try {
                val credential = withContext(Dispatchers.IO) {
                    if (service == AiService.CLAUDE) attempt.claude { url ->
                        mutable.value = State("Finish Claude sign-in in the browser. If it cannot return, use Paste Claude callback below.", url, service = service)
                    } else attempt.codex { code, url ->
                        mutable.value = State("OpenAI one-time code: $code. Complete device authorization in the browser; waiting for confirmation…", url, code, service)
                    }
                }
                ensureActive()
                check(prefs.saveNewIfEpoch(service, credential, epoch))
                mutable.value = State("Account saved on this phone. Usage refresh is separate; open the dashboard to check it.", connected = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = when(e) {
                    is LoginHttpError -> "${e.stage} rejected (HTTP ${e.status})."
                    is LoginResponseError -> "${e.stage} returned a web page or invalid JSON instead of sign-in data."
                    is java.net.BindException -> "The local Claude callback port is already in use."
                    is java.net.UnknownHostException -> "Cannot reach the provider. Check network permission and DNS."
                    is java.net.SocketTimeoutException -> "Provider request timed out."
                    else -> "Sign-in did not finish. Check connectivity and retry."
                }
                mutable.value = State("$reason No new account was saved.")
            } finally { attempt.cancel(); if (client === attempt) client = null }
        }
    }
    fun submitClaudeCallback(input: String): Boolean = client?.submitClaudeCallback(input) == true
    fun interrupted() { mutable.value = State("Android ended the pending browser sign-in. Start a new sign-in attempt.") }
    fun cancel() { client?.cancel(); job?.cancel(); mutable.value = State("Sign-in cancelled") }
    override fun onCleared() { client?.cancel(); super.onCleared() }
}
