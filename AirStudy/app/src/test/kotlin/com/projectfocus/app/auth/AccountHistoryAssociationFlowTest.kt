package com.projectfocus.app.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FlightRecordSceneEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightClockReading
import com.projectfocus.core.model.flight.freeflight.FreeFlightEngine
import com.projectfocus.core.model.flight.freeflight.FreeFlightScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.RecordedFlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountHistoryAssociationFlowTest {
    @get:Rule val lifetime = object : org.junit.rules.ExternalResource() {
        override fun before() { Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher()) }
        override fun after() {
            try { shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext()) }
            finally { Dispatchers.resetMain() }
        }
    }
    private lateinit var app: ProjectFocusApp
    private lateinit var accounts: EmailAccountSessionStore
    private lateinit var reader: FlightHistoryReader
    private val first = EmailAccount("history-account-a", "shared@example.test", "history-credential-a1")
    private val second = EmailAccount("history-account-b", "shared@example.test", "history-credential-b1")
    private val wall = 1_793_665_800_000L

    @Before fun open() = runBlocking(Dispatchers.IO) {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() }
        app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear()
        accounts = EmailAccountSessionStore(app)
        reader = FlightHistoryReader(app.database.historyReadDao())
    }
    @After fun close() {
        TokenStore.clear()
        app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun loaded(): HistoryReadState.Loaded {
        val result = withTimeout(15_000) { reader.records.first { it !is HistoryReadState.Loading } }
        assertTrue("History read did not complete successfully: $result", result is HistoryReadState.Loaded)
        return result as HistoryReadState.Loaded
    }
    private fun countdown(id: String, owner: String) = FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","Beijing","CN",40.0,116.0,
        "TSN","Tianjin Airport","Tianjin","CN",39.0,117.0,wall,wall+300_000,25,5,99.25,
        "CANCELLED",null,"01A",false,false,false,null,false,wall,wall,ownerScope=owner)

    @Test fun bothRealStoredModesAndEditedScenesFollowTheirAcceptedAccountAcrossNewCredentials() = runBlocking(Dispatchers.IO) {
        accounts.saveCancellable(first)
        val owner = TokenStore.storageScope()
        app.database.flightHistoryDao().insert(countdown("same-history-id", owner))
        app.database.flightHistoryDao().insert(countdown("anonymous-history", "local"))
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0, 0, wall, "account-history-clock")
        val start = engine.start(FreeFlightStartRequest("same-history-id", owner,
            PlannedAirport(1,"PEK","Beijing Airport","Beijing","CN",40.0,116.0),90.0,"01A",FreeFlightScene(2,"Reading","book"),"AS42"), now)
        val end = now.copy(elapsedRealtimeMs=90_000, uptimeMillis=90_000, wallMs=wall+90_000)
        val cancelled = engine.cancel(start, end).copy(revision=1)
        val archives = RoomFreeFlightArchiveStore(app.database.freeFlightArchiveDao())
        assertTrue(archives.commit(null, start, null))
        assertTrue(archives.commit(0, cancelled, null))
        val scene = PlannedScene("account-reading", "Reading", "reading", android.graphics.Color.BLUE, false)
        for (kind in listOf("COUNTDOWN", "COUNT_UP")) app.database.flightRecordSceneDao().insert(FlightRecordSceneEntity(
            kind,"same-history-id",owner,if(kind=="COUNTDOWN")"same-history-id" else null,
            if(kind=="COUNT_UP")"same-history-id" else null,wall,1,
            requireNotNull(PlannedFlightMetadataCodec.encodeScene(scene)),wall))
        val original = loaded()
        assertEquals(2, original.records.size)
        assertEquals(2, original.records.map { it.key }.toSet().size)
        assertTrue(original.records.all { it.editedScene?.snapshot == scene })
        accounts.saveCancellable(first.copy(token="history-credential-a2"))
        val renewed = loaded()
        assertEquals(owner, renewed.account.scope)
        assertEquals(original.records, renewed.records)
        assertFalse(original.account.isCurrent())
        assertTrue(renewed.account.isCurrent())
        assertEquals(owner, (renewed.records.filterIsInstance<RecordedFlight.Countdown>().single()).record.ownerScope)
        TokenStore.clear()
        assertEquals(listOf("anonymous-history"), loaded().records.map { it.key.id })
        accounts.saveCancellable(second)
        assertTrue(loaded().records.isEmpty())
        app.database.flightHistoryDao().insert(countdown("account-b-history", TokenStore.storageScope()))
        assertEquals(listOf("account-b-history"), loaded().records.map { it.key.id })
        accounts.saveCancellable(first.copy(token="history-credential-a3"))
        assertEquals(original.records, loaded().records)
        TokenStore.clear()
        assertNotNull(EmailAccountSessionStore(app).restoreIfAnonymous())
        assertEquals(owner, TokenStore.storageScope())
        assertEquals(original.records, loaded().records)
    }
}
