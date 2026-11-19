package com.projectfocus.app.admission.freeflight

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Candidate only: actual generated Hilt component/Application/owner; no HTTP admission call. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [28])
class FreeFlightAdmissionBindingsTest {
    private lateinit var app: ProjectFocusApp
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(10_000) { app.awaitInitialAccountRestore() } }
    }
    @After fun teardown() { TokenStore.clear(); shutdownRealApplicationAfterTest(app) }

    @Test fun actualHiltGraphUsesOneRealAdmissionAndTheExistingApplicationIoOwner() {
        val graph = EntryPointAccessors.fromApplication(app, FreeFlightAdmissionEntryPoint::class.java)
        assertThat(graph.preparation()).isSameInstanceAs(graph.actual())
        assertThat(graph.formal()).isSameInstanceAs(graph.actual())
        assertThat(ReflectionHelpers.getField<Any>(graph.actual(), "applicationScope")).isSameInstanceAs(app.admissionApplicationScope)
        assertThat(graph.identities()).isInstanceOf(SharedFreeFlightAdmissionIdentityProvider::class.java)
    }

    @Test fun actualIdentityAdapterUsesFactoryHostAndRejectsRepublishedAccountWithoutMinting() = runBlocking(Dispatchers.IO) {
        val graph = EntryPointAccessors.fromApplication(app, FreeFlightAdmissionEntryPoint::class.java)
        assertThat(graph.identities().current()).isNull()
        TokenStore.setCancellable("binding-fixture-account")
        val factory = FreeFlightHostOwnerFactory(app, graph.ownership())
        val owner = factory.open()
        val captured = requireNotNull(graph.identities().capture(owner))
        assertThat(captured.bearer).isEqualTo("binding-fixture-account")
        assertThat(graph.identities().current()).isEqualTo(captured)
        assertThat(graph.identities().isCurrent(captured)).isTrue()
        assertThat(graph.identities().current()?.owner).isEqualTo(owner)
        TokenStore.setCancellable("binding-fixture-account")
        assertThat(graph.identities().isCurrent(captured)).isFalse()
        assertThat(graph.identities().current()).isNull()
        assertThat(factory.isCurrent(owner)).isFalse()
    }
}
