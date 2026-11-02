package com.projectfocus.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.app.booking.PlanEntitlementAccess
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EmailLoginError { EMAIL_REQUIRED, PASSWORD_REQUIRED, REJECTED, NETWORK, STORAGE }
data class EmailLoginState(val busy: Boolean = false, val error: EmailLoginError? = null, val loggedIn: Boolean = false)

@HiltViewModel
class EmailLoginViewModel @Inject constructor(private val gateway: OwnEmailLoginGateway,
    private val accounts: EmailAccountSessionStore, private val entitlement: PlanEntitlementAccess) : ViewModel() {
    private val mutableState = MutableStateFlow(EmailLoginState())
    val state = mutableState.asStateFlow()

    fun submit(email: String, password: String) {
        if (state.value.busy || state.value.loggedIn) return
        if (email.isEmpty()) { mutableState.value = EmailLoginState(error = EmailLoginError.EMAIL_REQUIRED); return }
        if (password.isEmpty()) { mutableState.value = EmailLoginState(error = EmailLoginError.PASSWORD_REQUIRED); return }
        mutableState.value = EmailLoginState(busy = true)
        val identity = TokenStore.snapshot()
        viewModelScope.launch {
            try {
                val account = gateway.login(email, password)
                currentCoroutineContext().ensureActive()
                try {
                    accounts.saveCancellable(account, identity)
                }
                catch (changed: EmailLoginIdentityChangedException) { throw changed }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    mutableState.value = EmailLoginState(error = EmailLoginError.STORAGE)
                    return@launch
                }
                // A subscription refresh is independent; success here is sign-in,
                // never premium eligibility, enabled plan, or flight acceptance.
                try { entitlement.refresh() } catch (_: Exception) { /* Sign-in remains independent of eligibility lookup. */ }
                mutableState.value = EmailLoginState(loggedIn = true)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                mutableState.value = EmailLoginState(error = EmailLoginError.NETWORK)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: EmailLoginRejectedException) { mutableState.value = EmailLoginState(error = EmailLoginError.REJECTED) }
            catch (_: Exception) { mutableState.value = EmailLoginState(error = EmailLoginError.NETWORK) }
            finally { if (state.value.busy) mutableState.value = state.value.copy(busy = false) }
        }
    }
}
