package com.projectfocus.app.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AccountRestoreGateTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var accounts: EmailAccountSessionStore
    @Before fun setup() {
        preferences = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("r169_restore_gate_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        accounts = EmailAccountSessionStore(preferences)
        accounts.save(ACCOUNT)
        TokenStore.clear()
    }
    @After fun cleanup() { preferences.edit().clear().commit(); TokenStore.clear() }

    @Test fun actualRestorePublishesExactSnapshotsAndOpensBarrierOnce() = runTest {
        val before = TokenStore.snapshot()
        val saved = preferences.all.toMap()
        val gate = AccountRestoreGate()
        val waiting = async { gate.await() }
        runCurrent(); assertThat(waiting.isCompleted).isFalse()
        gate.restore(accounts)
        val publication = checkNotNull(waiting.await())
        assertThat(publication.before).isEqualTo(before)
        assertThat(publication.after).isEqualTo(TokenStore.snapshot())
        assertThat(publication.after.token).isEqualTo(ACCOUNT.token)
        assertThat(preferences.all).isEqualTo(saved)
        gate.complete(null)
        assertThat(gate.await()).isSameInstanceAs(publication)
    }

    @Test fun generationChangedBeforeRealRestoreCannotBecomeBootstrap() = runTest {
        val gate = AccountRestoreGate()
        TokenStore.clear()
        gate.restore(accounts)
        assertThat(TokenStore.peek()).isEqualTo(ACCOUNT.token)
        assertThat(gate.await()).isNull()
    }

    @Test fun firstManualSignInIsNotAnInitialPreferenceRestoration() = runTest {
        val gate = AccountRestoreGate()
        accounts.save(ACCOUNT)
        val signedIn = TokenStore.snapshot()
        gate.restore(accounts)
        assertThat(gate.await()).isNull()
        assertThat(TokenStore.snapshot()).isEqualTo(signedIn)
    }

    @Test fun failedActualRestoreReleasesBarrierWithoutPublication() = runTest {
        check(preferences.edit().putString("account", "broken saved account").commit())
        val before = TokenStore.snapshot()
        val gate = AccountRestoreGate()
        assertThat(runCatching { gate.restore(accounts) }.isFailure).isTrue()
        assertThat(gate.await()).isNull()
        assertThat(TokenStore.snapshot()).isEqualTo(before)
    }

    private companion object {
        val ACCOUNT = EmailAccount("r169-id", "gate@example.test", "r169-synthetic-restore-token")
    }
}
