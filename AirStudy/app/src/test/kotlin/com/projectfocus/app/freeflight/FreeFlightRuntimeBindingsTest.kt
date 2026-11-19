package com.projectfocus.app.freeflight

import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.admission.freeflight.FreeFlightAdmissionEntryPoint
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.service.focus.freeflight.AndroidFreeFlightTimeSource
import com.projectfocus.service.focus.freeflight.LeaseBoundFreeFlightRuntimeEffects
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual generated production graph, with no overridden map/admission/runtime provider. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [28])
class FreeFlightRuntimeBindingsTest {
    private lateinit var app: ProjectFocusApp
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(10_000) { app.awaitInitialAccountRestore() } }
    }
    @After fun teardown() = shutdownRealApplicationAfterTest(app)

    @Test fun actualRepositoryUsesRealStorageClockAdmissionOwnershipAndApplicationLifetime() {
        val graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        val admission = EntryPointAccessors.fromApplication(app, FreeFlightAdmissionEntryPoint::class.java)
        val repository = graph.repository()
        assertSame(repository, graph.repository())
        assertSame(app.admissionApplicationScope, ReflectionHelpers.getField<Any>(repository, "admissionScope"))
        assertSame(admission.actual(), ReflectionHelpers.getField<Any>(repository, "admission"))
        assertSame(graph.owners(), ReflectionHelpers.getField<Any>(repository, "ownership"))
        assertTrue(ReflectionHelpers.getField<Any>(repository, "time") is AndroidFreeFlightTimeSource)
        assertTrue(ReflectionHelpers.getField<Any>(repository, "store") is RoomFreeFlightArchiveStore)
        assertTrue(ReflectionHelpers.getField<Any>(repository, "effects") is LeaseBoundFreeFlightRuntimeEffects)
    }

    @Test fun runtimeAndArrivalUseTheExactActualApplicationAndHomeMapAdapters() {
        val graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        assertSame(graph.maps(), graph.presence())
        assertSame(graph.maps(), ReflectionHelpers.getField<Any>(graph.arrival(), "maps"))
        assertSame(app.admissionApplicationScope, ReflectionHelpers.getField<Any>(graph.registry(), "scope"))
        assertSame(graph.registry(), ReflectionHelpers.getField<Any>(graph.operations(), "registry"))
        assertSame(graph.arrival(), ReflectionHelpers.getField<Any>(graph.operations(), "arrival"))
        assertSame(graph.presence(), ReflectionHelpers.getField<Any>(graph.repository(), "mapPresence"))
    }
}
