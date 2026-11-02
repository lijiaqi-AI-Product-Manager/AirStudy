package com.projectfocus.service.focus.session

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room + production Repository/Controller; no fake history DAO or mirrored SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlannedFlightMetadataRoomTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var preferenceScope: CoroutineScope
    private var mono = 0L
    private var nextId = 0
    private val departure = PlannedDeparture("2026-10-31", 7, 10, "Asia/Shanghai")
    private var wall = departure.instant().toEpochMilli()
    private val scene = PlannedScene(
        optionId = "source:reading:second",
        name = "原始名称",
        iconKey = "reading",
        color = 0xFFFDAA0F.toInt(),
        isCustom = true,
        cloudId = 42L,
        clientUuid = "client-second",
        displayName = "冻结的阅读标题",
    )

    @Before
    fun setUp() {
        mono = 0L
        nextId = 0
        wall = departure.instant().toEpochMilli()
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
        preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(
            scope = preferenceScope,
            produceFile = { File(temporary.newFolder(), "metadata.preferences_pb") },
        ))
    }

    @After
    fun tearDown() {
        TokenStore.clear()
        runBlocking { preferenceScope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun theAccountAtActualStartRemainsTheOwnerOfCheckpointsAndTheFinalArchive() = runBlocking {
        TokenStore.setCancellable("countdown-start-account")
        val owner = TokenStore.storageScope()
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan(latitude = 0.12))).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        assertThat(database.flightHistoryDao().findById(id)!!.ownerScope).isEqualTo(owner)
        TokenStore.setCancellable("account-published-after-start")
        ticks(repository, 60)
        val finished = database.flightHistoryDao().findById(id)!!
        assertThat(finished.status).isEqualTo(SessionRepository.STATUS_COMPLETED)
        assertThat(finished.ownerScope).isEqualTo(owner)
        assertThat(repository.settle()!!.ownerScope).isEqualTo(owner)
        reopenDatabase()
        assertThat(database.flightHistoryDao().findById(id)!!.ownerScope).isEqualTo(owner)
    }

    @Test
    fun naturalSnapshotRoundtrip() = runBlocking {
        val plan = plan(latitude = 0.12)
        assertThat(plan.durationMinutes).isEqualTo(1)
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan)).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        val ongoing = database.flightHistoryDao().findById(id)!!
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(ongoing.sceneSnapshotJson)).isEqualTo(scene)
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(ongoing.plannedDepartureJson)).isEqualTo(departure)
        assertThat(ongoing.categoryId).isNull()
        assertThat(repository.currentRoute().scenarioLabel).isEqualTo(scene.displayName)

        ticks(repository, 60)
        assertThat(repository.state.value).isInstanceOf(SessionState.Arrived::class.java)
        val completed = database.flightHistoryDao().findById(id)!!
        assertThat(completed.status).isEqualTo(SessionRepository.STATUS_COMPLETED)
        assertThat(completed.durationMinutes).isEqualTo(1)
        assertThat(completed.sceneSnapshotJson).isEqualTo(ongoing.sceneSnapshotJson)
        assertThat(completed.plannedDepartureJson).isEqualTo(ongoing.plannedDepartureJson)
        val settled = repository.settle()!!
        assertThat(settled.sceneSnapshotJson).isEqualTo(ongoing.sceneSnapshotJson)
        assertThat(settled.plannedDepartureJson).isEqualTo(ongoing.plannedDepartureJson)

        reopenDatabase()
        val reread = database.flightHistoryDao().findById(id)!!
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(reread.sceneSnapshotJson)).isEqualTo(scene)
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(reread.plannedDepartureJson)).isEqualTo(departure)
        assertThat(reread.categoryId).isNull()
    }

    @Test
    fun latestSnapshotOnCancel() = runBlocking {
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan())).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        ticks(repository, 60)
        val latest = database.flightHistoryDao().findById(id)!!
        val changed = scene.copy(optionId = "other-id-same-icon", name = "B", displayName = "B")
        val opaque = PlannedFlightMetadataCodec.encodeScene(changed)!!.dropLast(1) +
            ",\"futureMetadata\":{\"revision\":9}}"
        database.flightHistoryDao().update(latest.copy(
            sceneSnapshotJson = opaque,
            plannedDepartureJson = null,
        ))

        ticks(repository, 60)
        val checkpoint = database.flightHistoryDao().findById(id)!!
        assertThat(checkpoint.actualFocusMinutes).isEqualTo(2)
        assertThat(checkpoint.sceneSnapshotJson).isEqualTo(opaque)
        assertThat(checkpoint.plannedDepartureJson).isNull()
        assertThat(repository.abandon()).isTrue()
        val terminal = database.flightHistoryDao().findById(id)!!
        assertThat(terminal.status).isEqualTo(SessionRepository.STATUS_CANCELLED)
        assertThat(terminal.sceneSnapshotJson).isEqualTo(opaque)
        assertThat(terminal.plannedDepartureJson).isNull()
        assertThat(repository.settle()!!.sceneSnapshotJson).isEqualTo(opaque)
    }

    @Test
    fun restoreSnapshotCheckpoint() = runBlocking {
        val builtin = scene.copy(name = "", isCustom = false, displayName = "学习")
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan().copy(scene = builtin))).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        ticks(repository, 60)
        reopenDatabase()
        wall += 86_400_000L

        val restored = newRepository()
        val running = restored.restore() as SessionState.Running
        assertThat(running.accumulatedMs).isEqualTo(60_000L)
        assertThat(restored.currentRoute().scene).isEqualTo(builtin)
        assertThat(restored.currentRoute().plannedDeparture).isEqualTo(departure)
        assertThat(restored.currentRoute().scenarioLabel).isEqualTo("学习")
        assertThat(restored.abandon()).isTrue()
        val saved = database.flightHistoryDao().findById(id)!!
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(saved.sceneSnapshotJson)).isEqualTo(builtin)
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(saved.plannedDepartureJson)).isEqualTo(departure)
    }

    @Test
    fun invalidSceneKeepsClock() = runBlocking {
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan())).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        val row = database.flightHistoryDao().findById(id)!!
        val badJson = "{future-incomplete-scene"
        database.flightHistoryDao().update(row.copy(sceneSnapshotJson = badJson))
        reopenDatabase()

        val restored = newRepository()
        assertThat(restored.restore()).isInstanceOf(SessionState.Running::class.java)
        assertThat(restored.currentRoute().scene).isNull()
        assertThat(restored.currentRoute().plannedDeparture).isEqualTo(departure)
        assertThat(restored.abandon()).isTrue()
        val terminal = database.flightHistoryDao().findById(id)!!
        assertThat(terminal.sceneSnapshotJson).isEqualTo(badJson)
        assertThat(terminal.plannedDepartureJson).isEqualTo(row.plannedDepartureJson)
    }

    @Test
    fun legacyNullMetadata() = runBlocking {
        val repository = newRepository()
        assertThat(repository.start(SessionRepository.NO_CATEGORY, 60_000L, "AAA", "BBB")).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        reopenDatabase()
        val restored = newRepository()
        restored.restore()
        assertThat(restored.currentRoute().scene).isNull()
        assertThat(restored.currentRoute().plannedDeparture).isNull()
        ticks(restored, 60)
        val row = database.flightHistoryDao().findById(id)!!
        assertThat(row.status).isEqualTo(SessionRepository.STATUS_COMPLETED)
        assertThat(row.sceneSnapshotJson).isNull()
        assertThat(row.plannedDepartureJson).isNull()
    }

    @Test
    fun retrySnapshotAfterInsert() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_metadata_insert BEFORE INSERT ON flight_history " +
                "BEGIN SELECT RAISE(ABORT, 'test-only initial write outage'); END",
        )
        val repository = newRepository()
        assertThat(repository.startPlannedFlight(plan())).isTrue()
        val id = (repository.state.value as SessionState.Running).sessionId
        assertThat(database.flightHistoryDao().findById(id)).isNull()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_metadata_insert")

        assertThat(repository.abandon()).isTrue()
        val saved = database.flightHistoryDao().findById(id)!!
        assertThat(saved.status).isEqualTo(SessionRepository.STATUS_CANCELLED)
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(saved.sceneSnapshotJson)).isEqualTo(scene)
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(saved.plannedDepartureJson)).isEqualTo(departure)
    }

    private fun plan(latitude: Double = 0.30): FlightLegPlan = FlightLegPlan.between(
        PlannedAirport(101, "AAA", "Origin", "Origin City", "Test", 0.0, 0.0),
        PlannedAirport(102, "BBB", "Destination", "Destination City", "Test", latitude, 0.0),
    ).copy(scene = scene, plannedDeparture = departure, seat = "1A")

    private fun newRepository(): SessionRepository = SessionRepository(
        controller = SessionController(
            monotonic = MonotonicSource { mono },
            wallClock = { wall },
            idGenerator = { "metadata-session-${++nextId}" },
        ),
        settings = settings,
        historyDao = database.flightHistoryDao(),
        airportDao = database.airportDao(),
        widgetPush = NoOpWidgetPush,
        todayNotifier = TodayStatsNotifier(),
        io = Dispatchers.IO,
    )

    private suspend fun ticks(repository: SessionRepository, count: Int) {
        repeat(count) {
            mono += 1_000L
            wall += 1_000L
            repository.tick()
        }
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
        .allowMainThreadQueries()
        .build()

    private fun reopenDatabase() {
        database.close()
        database = openDatabase()
    }

    private companion object {
        const val DB_NAME = "m.db"
    }
}
