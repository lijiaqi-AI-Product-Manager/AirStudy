package com.projectfocus.app.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.booking.PlanEntitlementAccess
import com.projectfocus.app.booking.PlanEntitlementState
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** VM + actual own HTTP + real account preferences; no pretend authenticated gateway. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EmailLoginViewModelHttpTest {
    private lateinit var application: Application
    private lateinit var real: SharedPreferences
    private lateinit var client: OkHttpClient
    private lateinit var server: MockWebServer
    private val models = mutableListOf<EmailLoginViewModel>()
    private val canceledCall = CountDownLatch(1)
    private var releaseResponse: CountDownLatch? = null

    @Before fun setUp() {
        // Real deadlines and OkHttp threads; no virtual-time jump past network completion.
        Dispatchers.setMain(Dispatchers.Unconfined)
        application = ApplicationProvider.getApplicationContext()
        real = application.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE)
        check(real.edit().clear().commit())
        TokenStore.clear()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceledCall.countDown()
            }
        }).build()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        releaseResponse?.countDown()
        models.forEach { it.viewModelScope.cancel() }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
        real.edit().clear().commit()
        TokenStore.clear()
        Dispatchers.resetMain()
    }

    @Test fun `empty fields stay local without save refresh or service start`() {
        val access = RecordingEntitlement()
        val vm = model(access)
        val identity = TokenStore.snapshot()
        vm.submit("", "password")
        assertThat(vm.state.value.error).isEqualTo(EmailLoginError.EMAIL_REQUIRED)
        vm.submit("u@example.test", "")
        assertThat(vm.state.value.error).isEqualTo(EmailLoginError.PASSWORD_REQUIRED)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(real.all).isEmpty()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertNoAdmissionOrService(access)
    }

    @Test fun `successful real sign in saves once refreshes subscription but never admits or starts a flight`() = runBlocking {
        respond(valid)
        val access = RecordingEntitlement()
        val vm = model(access)
        vm.submit("  login@example.test  ", "  fixture-password  ")
        vm.submit("ignored@example.test", "duplicate-click")
        val state = settled(vm)
        assertThat(state.loggedIn).isTrue()
        assertThat(state.error).isNull()
        assertThat(state.busy).isFalse()
        assertThat(TokenStore.peek()).isEqualTo("accepted-own-token")
        assertThat(access.refreshes.get()).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(1)
        vm.submit("ignored-after-success@example.test", "duplicate-success")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(access.mutable.value.qualified).isFalse()
        assertNoAdmissionOrService(access)
        val stored = real.all.toMap()
        assertThat(stored.values.any { it.toString().contains("fixture-password") }).isFalse()
        TokenStore.clear()
        EmailAccountSessionStore(real).restoreIfAnonymous()
        assertThat(TokenStore.peek()).isEqualTo("accepted-own-token")
        assertThat(real.all).isEqualTo(stored)
    }

    @Test fun `subscription refresh failure does not turn a committed login into failed sign in`() = runBlocking {
        respond(valid)
        val access = RecordingEntitlement().apply { onRefresh = { throw IOException("own subscription unavailable") } }
        val vm = model(access)
        vm.submit("login@example.test", "fixture-password")
        val state = settled(vm)
        assertThat(state.loggedIn).isTrue()
        assertThat(state.error).isNull()
        assertThat(TokenStore.peek()).isEqualTo("accepted-own-token")
        assertThat(real.all).isNotEmpty()
        assertThat(access.refreshes.get()).isEqualTo(1)
        assertNoAdmissionOrService(access)
    }

    @Test fun `rejection malformed payload and timeout never replace a real saved account`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val identity = TokenStore.snapshot()
        val cases = listOf(
            MockResponse.Builder().code(401).body(valid).build() to EmailLoginError.REJECTED,
            MockResponse.Builder().body("""{"success":false,"data":null}""").build() to EmailLoginError.REJECTED,
            MockResponse.Builder().body("""{"data":{"token":"not-flat","user":{"id":1}}}""").build() to EmailLoginError.NETWORK,
            MockResponse.Builder().body(valid).headersDelay(1, TimeUnit.SECONDS).build() to EmailLoginError.NETWORK,
        )
        for ((index, case) in cases.withIndex()) {
            server.enqueue(case.first)
            val access = RecordingEntitlement()
            val vm = model(access, timeoutMillis = if (index == cases.lastIndex) 150 else 3_000)
            vm.submit("login@example.test", "fixture-password")
            val state = settled(vm)
            assertThat(state.loggedIn).isFalse()
            assertThat(state.error).isEqualTo(case.second)
            assertThat(real.all).isEqualTo(before)
            assertThat(TokenStore.snapshot()).isEqualTo(identity)
            assertThat(access.refreshes.get()).isEqualTo(0)
            assertNoAdmissionOrService(access)
        }
    }

    @Test fun `canceling the VM cancels HTTP and never saves a late response`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val identity = TokenStore.snapshot()
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(1, TimeUnit.SECONDS).build())
        val access = RecordingEntitlement()
        val vm = model(access)
        vm.submit("login@example.test", "fixture-password")
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull()
        vm.viewModelScope.cancel()
        withTimeout(3_000) { vm.state.first { !it.busy } }
        assertThat(canceledCall.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(vm.state.value.loggedIn).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(real.all).isEqualTo(before)
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(access.refreshes.get()).isEqualTo(0)
        assertNoAdmissionOrService(access)
    }

    @Test fun `same token newer generation during real request cannot log in or persist`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val seen = CountDownLatch(1)
        val release = CountDownLatch(1).also { releaseResponse = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release HTTP response" }
                return MockResponse.Builder().body(valid).build()
            }
        }
        val access = RecordingEntitlement()
        val vm = model(access)
        vm.submit("login@example.test", "fixture-password")
        val newer: TokenStore.Snapshot
        try {
            assertThat(seen.await(3, TimeUnit.SECONDS)).isTrue()
            TokenStore.set(OLD.token)
            newer = TokenStore.snapshot()
        } finally { release.countDown() }
        val state = settled(vm)
        assertThat(state.loggedIn).isFalse()
        assertThat(state.error).isEqualTo(EmailLoginError.NETWORK)
        assertThat(real.all).isEqualTo(before)
        assertThat(TokenStore.snapshot()).isEqualTo(newer)
        assertThat(access.refreshes.get()).isEqualTo(0)
        assertNoAdmissionOrService(access)
    }

    @Test fun `real commit failure stays storage error without publishing auth or refreshing access`() = runBlocking {
        EmailAccountSessionStore(real).save(OLD)
        val before = real.all.toMap()
        val identity = TokenStore.snapshot()
        respond(valid)
        val access = RecordingEntitlement()
        val boundary = FailFirstCommitPreferences(real)
        val vm = model(access, EmailAccountSessionStore(boundary))
        vm.submit("login@example.test", "fixture-password")
        val state = settled(vm)
        assertThat(state.loggedIn).isFalse()
        assertThat(state.error).isEqualTo(EmailLoginError.STORAGE)
        assertThat(boundary.commits.get()).isEqualTo(2)
        assertThat(real.all).isEqualTo(before)
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(access.refreshes.get()).isEqualTo(0)
        assertNoAdmissionOrService(access)
    }

    @Test fun `login persistence waits off Main for real lease while Main remains responsive`() = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "login-main-test") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val mainThread = withContext(main) { Thread.currentThread() }
        val identity = TokenStore.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async(Dispatchers.IO) { TokenStore.withSnapshotLease(identity) { entered.complete(Unit); release.await() } }
        entered.await()
        val boundary = RecordingCommitPreferences(real)
        val access = RecordingEntitlement()
        respond(valid)
        val vm = model(access, EmailAccountSessionStore(boundary))
        try {
            try {
            vm.submit("login@example.test", "fixture-password")
            withTimeout(3_000) { boundary.committed.await() }
            assertThat(boundary.thread.get()).isNotSameInstanceAs(mainThread)
            withTimeout(3_000) { withContext(main) { assertThat(vm.state.value.busy).isTrue() } }
            assertThat(TokenStore.snapshot()).isEqualTo(identity)
            } finally { release.complete(Unit); held.await() }
            assertThat(settled(vm).loggedIn).isTrue()
            assertNoAdmissionOrService(access)
        }
        finally { vm.viewModelScope.cancel(); Dispatchers.setMain(Dispatchers.Unconfined); main.close() }
    }

    @Test fun `cancel during held publication lease rolls preferences back and never publishes later`() = runBlocking {
        val identity = TokenStore.snapshot()
        val before = real.all.toMap()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async(Dispatchers.IO) { TokenStore.withSnapshotLease(identity) { entered.complete(Unit); release.await() } }
        entered.await()
        val boundary = RecordingCommitPreferences(real)
        val access = RecordingEntitlement()
        respond(valid)
        val vm = model(access, EmailAccountSessionStore(boundary))
        try {
            vm.submit("login@example.test", "fixture-password")
            withTimeout(3_000) { boundary.committed.await() }
            vm.viewModelScope.cancel()
            withTimeout(3_000) { vm.state.first { !it.busy } }
            assertThat(TokenStore.snapshot()).isEqualTo(identity)
            assertThat(real.all).isEqualTo(before)
            assertThat(vm.state.value.loggedIn).isFalse()
        } finally { release.complete(Unit); held.await() }
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertNoAdmissionOrService(access)
    }

    private class RecordingCommitPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        val committed = CompletableDeferred<Unit>()
        val thread = AtomicReference<Thread>()
        override fun edit(): SharedPreferences.Editor {
            val edit = delegate.edit()
            return object : SharedPreferences.Editor by edit {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { edit.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { edit.remove(key); return this }
                override fun commit(): Boolean {
                    thread.set(Thread.currentThread())
                    val result = edit.commit()
                    committed.complete(Unit)
                    return result
                }
            }
        }
    }

    private fun model(access: RecordingEntitlement,
                      store: EmailAccountSessionStore = EmailAccountSessionStore(real),
                      timeoutMillis: Long = 3_000): EmailLoginViewModel =
        EmailLoginViewModel(OwnEmailLoginGateway(client, server.url("/").toString(), timeoutMillis), store, access)
            .also { models += it }
    private suspend fun settled(vm: EmailLoginViewModel): EmailLoginState = withTimeout(4_000) {
        vm.state.first { !it.busy && (it.loggedIn || it.error != null) }
    }
    private fun assertNoAdmissionOrService(access: RecordingEntitlement) {
        assertThat(access.admissions.get()).isEqualTo(0)
        assertThat(shadowOf(application).nextStartedService).isNull()
    }
    private fun respond(text: String) { server.enqueue(MockResponse.Builder().body(text).build()) }
    private class RecordingEntitlement : PlanEntitlementAccess {
        val mutable = MutableStateFlow(PlanEntitlementState("email-test-owner", false))
        override val state = mutable.asStateFlow()
        val refreshes = AtomicInteger()
        val admissions = AtomicInteger()
        var onRefresh: () -> Unit = {}
        override fun admit(): Boolean { admissions.incrementAndGet(); return mutable.value.qualified }
        override fun refresh() { refreshes.incrementAndGet(); onRefresh() }
    }
    private companion object {
        val OLD = EmailAccount("old-id", "old@example.test", "old-own-token")
        const val valid = """{"success":null,"data":{"id":"accepted-id","email":"accepted@example.test","token":"accepted-own-token"}}"""
    }
}
