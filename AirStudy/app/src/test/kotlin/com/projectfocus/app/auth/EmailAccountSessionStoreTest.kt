package com.projectfocus.app.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EmailAccountSessionStoreTest {
    private lateinit var real: SharedPreferences

    @Before fun setUp() {
        real = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE)
        check(real.edit().clear().commit())
        TokenStore.clear()
    }
    @After fun tearDown() { real.edit().clear().commit(); TokenStore.clear() }

    @Test fun `real committed account restores through a newly constructed store when anonymous`() {
        EmailAccountSessionStore(real).save(OLD)
        assertThat(TokenStore.peek()).isEqualTo(OLD.token)
        val savedBytes = real.all.toMap()
        assertThat(savedBytes).isNotEmpty()
        TokenStore.clear()
        EmailAccountSessionStore(real).restoreIfAnonymous()
        assertThat(TokenStore.peek()).isEqualTo(OLD.token)
        assertThat(real.all).isEqualTo(savedBytes)
    }

    @Test fun `restore never replaces an already authenticated different account`() {
        EmailAccountSessionStore(real).save(OLD)
        TokenStore.set("different-current-account")
        val identity = TokenStore.snapshot()
        EmailAccountSessionStore(real).restoreIfAnonymous()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
    }

    @Test fun `failed commit after real preference mutation rolls back prior account and leaves TokenStore untouched`() {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val identity = TokenStore.snapshot()
        val boundary = FailFirstCommitPreferences(real)
        val outcome = runCatching { EmailAccountSessionStore(boundary).save(NEW, identity) }
        assertThat(outcome.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(boundary.commits.get()).isEqualTo(2)
        assertThat(real.all).isEqualTo(before)
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        TokenStore.clear()
        EmailAccountSessionStore(real).restoreIfAnonymous()
        assertThat(TokenStore.peek()).isEqualTo(OLD.token)
    }

    @Test fun `failed first save removes partially applied new account instead of making it restorable`() {
        TokenStore.set("current-before-save")
        val identity = TokenStore.snapshot()
        val boundary = FailFirstCommitPreferences(real)
        assertThat(runCatching { EmailAccountSessionStore(boundary).save(NEW, identity) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(real.all).isEmpty()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        TokenStore.clear()
        val anonymous = TokenStore.snapshot()
        EmailAccountSessionStore(real).restoreIfAnonymous()
        assertThat(TokenStore.snapshot()).isEqualTo(anonymous)
    }

    @Test fun `same token changed generation before save rejects without writing preferences`() {
        TokenStore.set("same-session-token")
        val expected = TokenStore.snapshot()
        TokenStore.set("same-session-token")
        val newer = TokenStore.snapshot()
        assertThat(newer).isNotEqualTo(expected)
        val boundary = CommitBoundaryPreferences(real)
        assertThat(runCatching { EmailAccountSessionStore(boundary).save(NEW, expected) }.exceptionOrNull())
            .isInstanceOf(EmailLoginIdentityChangedException::class.java)
        assertThat(boundary.commits.get()).isEqualTo(0)
        assertThat(real.all).isEmpty()
        assertThat(TokenStore.snapshot()).isEqualTo(newer)
    }

    /** Reproduces the reviewed ca37cd12 baseline's unguarded commit -> token publish window. */
    @Test fun `generation changed while real commit returns rolls back and cannot overwrite newer identity`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val boundary = HeldCommitPreferences(real)
        val pending = async(Dispatchers.Default) {
            runCatching { EmailAccountSessionStore(boundary).save(NEW) }
        }
        val newer: TokenStore.Snapshot
        try {
            assertThat(boundary.committed.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(real.all).isNotEqualTo(before) // A real SP write happened; this is not a fake store.
            TokenStore.set(OLD.token) // Same String, different account generation.
            newer = TokenStore.snapshot()
        } finally { boundary.release.countDown() }
        val outcome = pending.await()
        assertThat(outcome.exceptionOrNull()).isInstanceOf(EmailLoginIdentityChangedException::class.java)
        assertThat(boundary.commits.get()).isEqualTo(2)
        assertThat(real.all).isEqualTo(before)
        assertThat(TokenStore.snapshot()).isEqualTo(newer)
    }

    @Test fun `restoration abandoned after same anonymous token generation changes while reading SP`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        TokenStore.clear()
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boundary = object : SharedPreferences by real {
            override fun getString(key: String?, defValue: String?): String? {
                val value = real.getString(key, defValue)
                read.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release SP read" }
                return value
            }
        }
        val pending = async(Dispatchers.Default) { EmailAccountSessionStore(boundary).restoreIfAnonymous() }
        val newer: TokenStore.Snapshot
        try {
            assertThat(read.await(3, TimeUnit.SECONDS)).isTrue()
            TokenStore.clear() // null -> null must still invalidate the captured generation.
            newer = TokenStore.snapshot()
        } finally { release.countDown() }
        pending.await()
        assertThat(TokenStore.snapshot()).isEqualTo(newer)
    }

    private companion object {
        val OLD = EmailAccount("old-id", "old@example.test", "old-own-token")
        val NEW = EmailAccount("new-id", "new@example.test", "new-own-token")
    }
}
