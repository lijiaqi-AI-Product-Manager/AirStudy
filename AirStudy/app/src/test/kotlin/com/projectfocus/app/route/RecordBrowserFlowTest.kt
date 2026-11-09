package com.projectfocus.app.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.app.Dialog
import androidx.room.withTransaction
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.ui.RecordCardView
import com.projectfocus.feature.record.ui.RecordPageFragment
import com.projectfocus.feature.record.ui.RecordDeleteDialog
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import com.projectfocus.feature.record.R as RecordR

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h936dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordBrowserFlowTest {
    @get:Rule val lifetime = object : org.junit.rules.ExternalResource() {
        override fun before() {
            Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        }
        override fun after() {
            try { shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext()) }
            finally { Dispatchers.resetMain() }
        }
    }
    private lateinit var app: ProjectFocusApp
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var host: RecordListHostFragment
    private val wall = System.currentTimeMillis()
    private var payload: FreeFlightArchive? = null
    private lateinit var cloud: RecordCloudHttpFixture

    @Before fun open() = runBlocking(Dispatchers.IO) {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() }
        TokenStore.setCancellable("record-browser-account-one")
        cloud = RecordCloudHttpFixture(app.recordCloudMutations, app.recordCloudSync)
    }
    @After fun close() {
        scenario?.close()
        TokenStore.clear()
        if (::cloud.isInitialized) cloud.close()
    }
    private fun countdown(id: String, status: String = "COMPLETED", at: Long = wall, owner: String = TokenStore.storageScope()) = FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,25,5,99.25,status,
        null,"12A",false,false,false,null,false,at,at,ownerScope=owner)

    private suspend fun saveIndependentRecord(cancelled: Boolean = false) {
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0,0,wall+1_000,"record-browser-clock")
        val start = engine.start(FreeFlightStartRequest("shared-id",TokenStore.storageScope(),
            PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",
            FreeFlightScene(3,"阅读","reading"),"AS42"),now)
        val at = now.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=now.wallMs+90_001)
        val completed = if (cancelled) engine.cancel(start, at).copy(revision = 1) else {
            val selection = engine.prepareLanding(start,at)
            val picked = engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
            engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,at),at).copy(revision=1)
        }
        val store = RoomFreeFlightArchiveStore(app.database.freeFlightArchiveDao())
        assertTrue(store.commit(null,start,null))
        assertTrue(store.commit(0,completed,null))
        payload = completed
    }

    private fun showRecords() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { actual ->
            activity = actual
            val navHost = actual.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            navHost.navController.navigate(R.id.recordListFragment)
        }
        idle()
        host = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
            .childFragmentManager.fragments.first() as RecordListHostFragment
        layout()
        await { host.childFragmentManager.fragments.any { it is RecordPageFragment && it.view != null } }
    }
    private fun layout() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        val width = (activity.resources.configuration.screenWidthDp*density+0.5f).toInt()
        val height = (activity.resources.configuration.screenHeightDp*density+0.5f).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        content.layout(0,0,width,height)
        idle()
    }
    private fun page() = host.childFragmentManager.fragments.filterIsInstance<RecordPageFragment>().single()
    private fun list() = page().requireView().findViewById<RecyclerView>(RecordR.id.pf_record_page_flight_records_list)
    private fun field(id: Int) = page().requireView().findViewById<TextView>(id)
    private fun card(position: Int): RecordCardView {
        layout()
        return list().findViewHolderForAdapterPosition(position)?.itemView as RecordCardView
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)) }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime()<end) { idle(); Thread.sleep(10) }
        idle()
        assertTrue(diagnostics(),condition())
        layout()
    }

    private fun diagnostics(): String {
        val page=host.childFragmentManager.fragments.filterIsInstance<RecordPageFragment>().firstOrNull()
        val hostModel=androidx.lifecycle.ViewModelProvider(host)[com.projectfocus.feature.record.list.RecordPageViewModel::class.java]
        val state=hostModel.state.value
        val pageModel=page?.let {
            org.robolectric.util.ReflectionHelpers.getField<Lazy<com.projectfocus.feature.record.list.RecordPageViewModel>>(it,"viewModel\$delegate").value
        }
        val lease=TokenStore.tryWithSnapshotLease(TokenStore.snapshot()) { true }.javaClass.simpleName
        return "Record screen state: host=${host.lifecycle.currentState}, page=${page?.lifecycle?.currentState}, " +
            "view=${page?.view?.let { page.viewLifecycleOwner.lifecycle.currentState }}, loading=${state.loading}, failed=${state.failed}, " +
            "items=${state.items.size}, accountCurrent=${state.account?.isCurrent()}, generation=${state.account?.generation}, " +
            "currentGeneration=${TokenStore.snapshot().generation}, sameModel=${hostModel===pageModel}, pageItems=${pageModel?.state?.value?.items?.size}, " +
            "sceneSave=${hostModel.sceneSave.value.failure}, savingScene=${hostModel.sceneSave.value.saving}, requests=${cloud.requestCount}, recovery=${hostModel.recoveryStatus.value}, " +
            "lease=$lease, hint=${page?.view?.findViewById<TextView>(RecordR.id.pf_record_page_empty_records_hint)?.text}"
    }

    @Test fun cloudRecordsRestoreAndReceiveRemoteChangesThroughTheNormalPage() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("shared-id"))
            saveIndependentRecord()
            assertEquals(2, app.recordCloudSync.synchronize().uploaded)
            app.database.withTransaction {
                app.database.flightHistoryDao().deleteAll()
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM free_flight_archive")
                for (row in app.database.appSettingDao().withPrefix("record.cloud.")) app.database.appSettingDao().delete(row.key)
            }
        }
        showRecords()
        runBlocking(Dispatchers.IO) { app.recordCloudSync.synchronize() }
        val model = androidx.lifecycle.ViewModelProvider(host)[com.projectfocus.feature.record.list.RecordPageViewModel::class.java]
        await { list().adapter?.itemCount == 2 && model.state.value.items.map { it.kind }.toSet() ==
            setOf(com.projectfocus.service.focus.history.FlightRecordKind.COUNTDOWN, com.projectfocus.service.focus.history.FlightRecordKind.COUNT_UP) }
        assertEquals(setOf("shared-id"), model.state.value.items.map { it.id }.toSet())
        capture("records-cloud-restored")
        runBlocking(Dispatchers.IO) {
            assertTrue(cloud.mutationGateway.changeScene(TokenStore.snapshot(), com.projectfocus.core.network.dto.FlightRecordMode.DOWN,
                "shared-id", com.projectfocus.core.network.dto.FlightRecordScenePatch(2, "工作"),
                com.projectfocus.core.network.dto.FlightRecordMutationOptions("another-device-scene")) is com.projectfocus.core.network.record.RecordMutationOutcome.Acknowledged)
            app.recordCloudSync.synchronize()
        }
        await { model.state.value.items.single { it.kind == com.projectfocus.service.focus.history.FlightRecordKind.COUNTDOWN }.sceneTitle == "工作" }
        runBlocking(Dispatchers.IO) {
            assertTrue(cloud.mutationGateway.delete(TokenStore.snapshot(), com.projectfocus.core.network.dto.FlightRecordMode.DOWN,
                "shared-id", com.projectfocus.core.network.dto.FlightRecordMutationOptions("another-device-removal")) is com.projectfocus.core.network.record.RecordMutationOutcome.Acknowledged)
            app.recordCloudSync.synchronize()
        }
        await { list().adapter?.itemCount == 1 && model.state.value.items.single().kind == com.projectfocus.service.focus.history.FlightRecordKind.COUNT_UP }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("shared-id") })
        assertNotNull(runBlocking(Dispatchers.IO) { app.database.freeFlightArchiveDao().get("shared-id", TokenStore.storageScope()) })
    }

    @Test fun incompleteCloudRecordsExplainTheirStatusAndRecoverThroughTheVisibleRetry() {
        val identity = TokenStore.snapshot()
        val mode = com.projectfocus.core.network.dto.FlightRecordMode.DOWN
        val original = countdown("older-cloud-record")
        val saved = runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(original)
            assertEquals(1, app.recordCloudSync.synchronize().uploaded)
            val remote = requireNotNull(cloud.record(identity, mode, original.id))
            cloud.seed(identity, mode, original.id)
            app.database.withTransaction {
                app.database.flightHistoryDao().deleteAll()
                for (row in app.database.appSettingDao().withPrefix("record.cloud.")) app.database.appSettingDao().delete(row.key)
            }
            assertEquals(1, app.recordCloudSync.synchronize(identity).pending)
            remote
        }
        showRecords()
        await { field(RecordR.id.pf_record_recovery_summary).text.toString() == "1 条记录等待恢复" &&
            page().requireView().findViewById<View>(RecordR.id.pf_record_recovery_notice).visibility == View.VISIBLE }
        assertEquals(0, list().adapter?.itemCount)
        assertTrue(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().getAll().isEmpty() })
        capture("records-recovery-pending")
        field(RecordR.id.pf_record_recovery_details).performClick()
        await { ShadowDialog.getLatestDialog().let { it is androidx.appcompat.app.AlertDialog && it.isShowing } }
        val prompt = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(prompt.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("缺少完整资料"))
        capturePrompt(prompt, "records-recovery-details")
        cloud.seed(identity, mode, original.id, requireNotNull(saved.archive), saved.scene)
        val requests = cloud.requestCount
        prompt.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        await { list().adapter?.itemCount == 1 &&
            page().requireView().findViewById<View>(RecordR.id.pf_record_recovery_notice).visibility == View.GONE }
        assertTrue(cloud.requestCount > requests)
        val restored = requireNotNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(original.id) })
        assertEquals(original.actualFocusMinutes, restored.actualFocusMinutes)
        assertEquals(original.departureLatitude, restored.departureLatitude, 0.0)
        assertEquals(original.destinationLongitude, restored.destinationLongitude, 0.0)
        capture("records-recovery-completed")
    }


    @Test fun bothStoredModesUseTheRealPagePreviewAndAccountBoundary() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("shared-id"))
            (1..9).forEach { app.database.flightHistoryDao().insert(countdown("countdown-$it",at=wall-it*1_000)) }
            app.database.flightHistoryDao().insert(countdown("active",status="ONGOING",at=wall-20_000))
            app.database.flightHistoryDao().insert(countdown("cancelled",status="CANCELLED",at=wall-30_000))
            val other = TokenStore.storageScope(TokenStore.Snapshot("record-browser-account-two",0))
            app.database.flightHistoryDao().insert(countdown("other-account",owner=other))
            saveIndependentRecord()
        }
        showRecords()
        await { list().adapter?.itemCount==10 }
        val first = card(0)
        assertEquals("AS42",first.findViewById<TextView>(RecordR.id.pf_record_card_text_flight_number).text.toString())
        assertEquals("正计时",first.findViewById<TextView>(RecordR.id.pf_record_card_text_flight_type_badge).text.toString())
        assertEquals("倒计时",card(1).findViewById<TextView>(RecordR.id.pf_record_card_text_flight_type_badge).text.toString())
        assertEquals(22f,host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_title).textSize,0.01f)
        val tabLabels=ArrayList<View>()
        page().requireView().findViewById<TabLayout>(RecordR.id.pf_record_page_record_tabs)
            .findViewsWithText(tabLabels,"全部",View.FIND_VIEWS_WITH_TEXT)
        assertEquals(1,tabLabels.filterIsInstance<TextView>().single().lineCount)
        capture("records-page-day")
        first.performClick()
        await { activity.findViewById<View>(RecordR.id.pf_record_page_share_preview)!=null }
        assertTrue(activity.findViewById<View>(RecordR.id.pf_record_page_share_card_image).contentDescription.toString().contains("正计时"))
        assertFalse(activity.findViewById<View>(RecordR.id.pf_record_page_share_card_image).contentDescription.toString().contains("计划"))
        assertEquals(0,host.childFragmentManager.backStackEntryCount)
        activity.onBackPressedDispatcher.onBackPressed()
        await { activity.findViewById<View>(RecordR.id.pf_record_page_share_preview)==null }
        page().requireView().findViewById<View>(RecordR.id.pf_record_page_btn_next_page).performClick()
        await { list().adapter?.itemCount==3 }
        assertEquals("第 2 页",field(RecordR.id.pf_record_page_text_page_indicator).text.toString())
        val spinner = page().requireView().findViewById<MaterialAutoCompleteTextView>(RecordR.id.pf_record_page_spinner_status)
        spinner.onItemClickListener!!.onItemClick(null,null,2,2)
        await { list().adapter?.itemCount==1 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString()=="第 1 页" }
        assertEquals("飞行中",card(0).findViewById<TextView>(RecordR.id.pf_record_card_text_status_desc).text.toString().trim())
        card(0).performClick()
        await { ShadowToast.getTextOfLatestToast()?.toString()==activity.getString(RecordR.string.pf_record_delete_blocked) }
        assertNull(activity.findViewById<View>(RecordR.id.pf_record_page_share_preview))
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertEquals(0,list().adapter?.itemCount)
        assertNull(activity.findViewById<View>(RecordR.id.pf_record_page_share_preview))
        assertEquals("—",host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_subtitle).text.toString())
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("record-browser-account-two") }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await { list().adapter?.itemCount==0 && activity.findViewById<View>(RecordR.id.pf_record_page_share_preview)==null }
        spinner.onItemClickListener!!.onItemClick(null,null,0,0)
        await { list().adapter?.itemCount==1 }
        assertTrue(card(0).contentDescription.toString().contains("倒计时"))
        assertEquals(13,runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().observeAll().first().size })
    }

    @Test fun cancelledRecordsDeleteThroughConfirmationAndKeepModeIdentityAndPagination() {
        val owner = TokenStore.storageScope()
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("completed-total"))
            app.database.flightHistoryDao().insert(countdown("shared-id", status = "CANCELLED"))
            (1..9).forEach { app.database.flightHistoryDao().insert(countdown("cancelled-$it", status = "CANCELLED", at = wall - it * 1_000)) }
            saveIndependentRecord(cancelled = true)
        }
        showRecords()
        await { list().adapter?.itemCount == 10 }
        val total = host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_subtitle).text.toString()
        val spinner = page().requireView().findViewById<MaterialAutoCompleteTextView>(RecordR.id.pf_record_page_spinner_status)
        spinner.onItemClickListener!!.onItemClick(null, null, 3, 3)
        await { spinner.text.toString() == "已取消" && list().adapter?.itemCount == 10 &&
            field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 1 页" }
        page().requireView().findViewById<View>(RecordR.id.pf_record_page_btn_next_page).performClick()
        await { list().adapter?.itemCount == 1 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 2 页" }
        card(0).performClick()
        val prompt = deletionPrompt()
        assertNull(activity.findViewById<View>(RecordR.id.pf_record_page_share_preview))
        capturePrompt(prompt, "records-delete-confirmation")
        val confirm = prompt.findViewById<View>(RecordR.id.pf_record_delete_confirm)
        confirm.performClick()
        confirm.performClick()
        await { list().adapter?.itemCount == 10 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 1 页" }
        await { !prompt.isShowing }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("cancelled-9") })
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("cancelled-added", status = "CANCELLED", at = wall - 30_000))
        }
        await { list().adapter?.itemCount == 10 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 1 页" &&
            page().requireView().findViewById<View>(RecordR.id.pf_record_page_btn_next_page).isEnabled }
        card(0).performClick()
        val countUpPrompt = deletionPrompt()
        countUpPrompt.findViewById<View>(RecordR.id.pf_record_delete_confirm).performClick()
        await { !countUpPrompt.isShowing && list().adapter?.itemCount == 10 }
        runBlocking(Dispatchers.IO) {
            assertNull(app.database.freeFlightArchiveDao().get("shared-id", owner))
            assertNotNull(app.database.flightHistoryDao().findById("shared-id"))
        }
        card(0).performClick()
        deletionPrompt().findViewById<View>(RecordR.id.pf_record_delete_confirm).performClick()
        await { list().adapter?.itemCount == 9 }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("shared-id") })
        assertEquals(total, host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_subtitle).text.toString())
        capture("records-cancelled-after-removal")
    }

    @Test fun aFailedDatabaseRemovalKeepsTheRecordAndAllowsTheSamePromptToRetry() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("retry-delete", status = "CANCELLED"))
            app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_record_removal BEFORE DELETE ON flight_history BEGIN SELECT RAISE(ABORT, 'record temporarily unavailable'); END")
        }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        card(0).performClick()
        val prompt = deletionPrompt()
        val confirm = prompt.findViewById<View>(RecordR.id.pf_record_delete_confirm)
        confirm.performClick()
        await { confirm.isEnabled && ShadowToast.getTextOfLatestToast()?.toString() == activity.getString(RecordR.string.pf_record_delete_failed) }
        assertTrue(prompt.isShowing)
        assertEquals(1, list().adapter?.itemCount)
        assertNotNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("retry-delete") })
        runBlocking(Dispatchers.IO) { app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_record_removal") }
        confirm.performClick()
        await { list().adapter?.itemCount == 0 && !prompt.isShowing }
        await { ShadowToast.getTextOfLatestToast()?.toString() == activity.getString(RecordR.string.pf_record_delete_success) }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("retry-delete") })
    }

    @Test fun obsoleteConfirmationsCannotDeleteAnUpdatedRecordOrAnotherAccountsHistory() {
        val row = countdown("updated-record", status = "CANCELLED")
        runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().insert(row) }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        card(0).performClick()
        val original = deletionPrompt()
        val oldConfirm = original.findViewById<View>(RecordR.id.pf_record_delete_confirm)
        runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().update(row.copy(status = "COMPLETED")) }
        await { !original.isShowing }
        oldConfirm.performClick()
        assertEquals("COMPLETED", runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(row.id) }?.status)
        runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().update(row) }
        await { card(0).findViewById<TextView>(RecordR.id.pf_record_card_text_status_desc).text.toString().trim() == "取消" }
        card(0).performClick()
        val owned = deletionPrompt()
        val ownedConfirm = owned.findViewById<View>(RecordR.id.pf_record_delete_confirm)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("record-browser-account-two") }
        await { !owned.isShowing && list().adapter?.itemCount == 0 }
        ownedConfirm.performClick()
        scenario!!.recreate()
        scenario!!.onActivity { actual ->
            activity = actual
            host = (actual.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
                .childFragmentManager.fragments.first() as RecordListHostFragment
        }
        await { list().adapter?.itemCount == 0 }
        assertEquals(row, runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(row.id) })
    }

    @Test fun aRemoteRejectionKeepsTheConfirmedRecordAndTheActualPageAllowsRetry() {
        cloud.rejectionStatus = 503
        val row = countdown("cloud-retry-record", status = "CANCELLED")
        runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().insert(row) }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        card(0).performClick()
        val prompt = deletionPrompt()
        val model = androidx.lifecycle.ViewModelProvider(host)[com.projectfocus.feature.record.list.RecordRemovalViewModel::class.java]
        val attempt = model.state.value.attempt
        prompt.findViewById<View>(RecordR.id.pf_record_delete_confirm).performClick()
        await { model.state.value.attempt > attempt && !model.state.value.saving &&
            ShadowToast.getTextOfLatestToast()?.toString() == activity.getString(RecordR.string.pf_record_cloud_delete_failed) }
        assertTrue(prompt.isShowing)
        assertEquals(row, runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(row.id) })
        assertTrue(cloud.requestCount > 0)
        assertEquals(1, runBlocking(Dispatchers.IO) { app.database.appSettingDao().withPrefix("record.cloud.pending.").size })
        cloud.rejectionStatus = null
        prompt.findViewById<View>(RecordR.id.pf_record_delete_confirm).performClick()
        await { list().adapter?.itemCount == 0 && !prompt.isShowing }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(row.id) })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.appSettingDao().withPrefix("record.cloud.pending.").isEmpty() })
    }

    @Test fun aGuestRecordOffersTheLoginEntryAndNeverDeletesBeforeAuthentication() {
        TokenStore.clear()
        val row = countdown("guest-record", status = "CANCELLED", owner = "local")
        runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().insert(row) }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        card(0).performClick()
        await { activity.findViewById<View>(RecordR.id.pf_record_login_gate) != null }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(240))
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val background = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        try {
            content.draw(Canvas(background))
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE,
                background.getPixel(8, background.height - 8)) >= 4.5)
        } finally { background.recycle() }
        capture("records-login-gate")
        activity.findViewById<View>(RecordR.id.pf_record_login_button).performClick()
        await { activity.findViewById<View>(RecordR.id.pf_record_login_gate) == null }
        assertEquals(com.projectfocus.app.auth.AuthLoginActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull(TokenStore.peek())
        assertEquals(row, runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(row.id) })
    }

    private fun deletionPrompt(): RecordDeleteDialog {
        await { (ShadowDialog.getLatestDialog() as? RecordDeleteDialog)?.isShowing == true }
        return ShadowDialog.getLatestDialog() as RecordDeleteDialog
    }

    private fun capturePrompt(prompt: Dialog, name: String) {
        val decor = prompt.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = prompt.window!!.attributes.width.takeIf { it > 0 }
            ?: ((activity.resources.configuration.screenWidthDp - 48) * density).toInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((activity.resources.configuration.screenHeightDp * density).toInt(), View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File("build/reports/records/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun corruptArchiveOffersRetryAndTheSameScreenRecoversAfterRepair() {
        runBlocking(Dispatchers.IO) {
            saveIndependentRecord()
            app.database.openHelper.writableDatabase.execSQL("UPDATE free_flight_archive SET payloadJson = ? WHERE sessionId = ?",
                arrayOf("broken-json","shared-id"))
        }
        showRecords()
        await { field(RecordR.id.pf_record_page_empty_records_hint).text.toString()=="读取失败，点此重试" }
        assertEquals(0,list().adapter?.itemCount)
        runBlocking(Dispatchers.IO) {
            app.database.openHelper.writableDatabase.execSQL("UPDATE free_flight_archive SET payloadJson = ? WHERE sessionId = ?",
                arrayOf(FreeFlightArchiveCodec.encode(requireNotNull(payload)),"shared-id"))
        }
        field(RecordR.id.pf_record_page_empty_records_hint).performClick()
        await { list().adapter?.itemCount==1 }
        assertEquals("AS42",card(0).findViewById<TextView>(RecordR.id.pf_record_card_text_flight_number).text.toString())
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h432dp-land-mdpi")
    fun wideWindowsLayOutTheTwoActualModesInSeparateColumns() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("shared-id"))
            saveIndependentRecord()
        }
        showRecords()
        await { list().adapter?.itemCount==2 }
        assertEquals(2,(list().layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)
        val first = card(0)
        val second = card(1)
        assertTrue(first.left < second.left)
        assertEquals(first.top,second.top)
        capture("records-page-wide")
    }

    private fun scenePicker(position: Int): AnchoredScenePickerDialog {
        val edit = card(position).findViewById<View>(RecordR.id.pf_record_card_img_focus_scene_edit)
        assertEquals(View.VISIBLE, edit.visibility)
        assertTrue(edit.isEnabled)
        edit.performClick()
        await { ShadowDialog.getLatestDialog().let { it is AnchoredScenePickerDialog && it.isShowing } }
        assertNull(activity.findViewById<View>(RecordR.id.pf_record_page_share_preview))
        return (ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog).also {
            assertEquals(View.GONE, it.findViewById<View>(UiR.id.pf_scene_seat).visibility)
        }
    }

    private fun sceneChoice(dialog: Dialog, label: String): View {
        val tags = dialog.findViewById<ViewGroup>(UiR.id.pf_scene_tags)
        return (0 until tags.childCount).map(tags::getChildAt)
            .filterIsInstance<TextView>().single { it.text.toString() == label }
    }

    private fun sceneText(position: Int) = card(position).findViewById<TextView>(RecordR.id.pf_record_card_text_focus_scene).text.toString()
    private fun shownScene(position: Int): String? {
        layout()
        return list().findViewHolderForAdapterPosition(position)?.itemView
            ?.findViewById<TextView>(RecordR.id.pf_record_card_text_focus_scene)?.text?.toString()
    }

    @Test fun bothModesKeepSceneChangesAfterPageRecreationWithoutRewritingTheFlight() {
        val original = countdown("shared-id")
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(original)
            saveIndependentRecord()
        }
        val owner = TokenStore.storageScope()
        val before = runBlocking(Dispatchers.IO) { app.database.freeFlightArchiveDao().get("shared-id", owner) }
        showRecords()
        await { list().adapter?.itemCount == 2 }
        assertEquals("阅读", sceneText(0))
        assertEquals("", sceneText(1))
        val total = host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_subtitle).text.toString()
        val first = scenePicker(0)
        capturePicker(first, "record-scene-picker")
        sceneChoice(first, "工作").performClick()
        await { shownScene(0) == "工作" && !first.isShowing }
        assertEquals("", sceneText(1))
        val second = scenePicker(1)
        sceneChoice(second, "学习").performClick()
        await { shownScene(1) == "学习" && !second.isShowing }
        assertEquals("工作", sceneText(0))
        scenario!!.recreate()
        scenario!!.onActivity { activity = it }
        idle()
        val nav = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        host = nav.childFragmentManager.primaryNavigationFragment as RecordListHostFragment
        layout()
        await { host.childFragmentManager.fragments.any { it is RecordPageFragment && it.view != null } }
        await { list().adapter?.itemCount == 2 && shownScene(0) == "工作" && shownScene(1) == "学习" }
        assertEquals(total, host.requireView().findViewById<TextView>(RecordR.id.pf_record_page_text_subtitle).text.toString())
        assertEquals(before, runBlocking(Dispatchers.IO) { app.database.freeFlightArchiveDao().get("shared-id", owner) })
        assertEquals(original, runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("shared-id") })
        capture("records-scene-edited")
    }

    @Test fun aSceneWriteFailureKeepsTheCardAndAllowsTheSameActionToRetry() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(countdown("scene-retry"))
            app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_scene_ui BEFORE INSERT ON flight_record_scene BEGIN SELECT RAISE(ABORT, 'fixture scene write rejected'); END")
        }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        val failed = scenePicker(0)
        sceneChoice(failed, "工作").performClick()
        await { ShadowToast.getTextOfLatestToast()?.toString() == "场景保存失败，请重试" }
        assertEquals("", sceneText(0))
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightRecordSceneDao().find("COUNTDOWN", "scene-retry") })
        runBlocking(Dispatchers.IO) { app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scene_ui") }
        val retry = scenePicker(0)
        sceneChoice(retry, "学习").performClick()
        await { shownScene(0) == "学习" && !retry.isShowing }
        assertNotNull(runBlocking(Dispatchers.IO) { app.database.flightRecordSceneDao().find("COUNTDOWN", "scene-retry") })
    }

    @Test fun aScenePickerAndItsRetainedCallbacksCannotEditAfterBackgroundAccountChange() {
        runBlocking(Dispatchers.IO) {
            saveIndependentRecord()
            val other = TokenStore.storageScope(TokenStore.Snapshot("record-browser-account-two", 0))
            app.database.flightHistoryDao().insert(countdown("other-account", owner = other))
        }
        showRecords()
        await { list().adapter?.itemCount == 1 }
        val old = scenePicker(0)
        val retained = sceneChoice(old, "工作")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertFalse(old.isShowing)
        assertEquals(0, list().adapter?.itemCount)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("record-browser-account-two") }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await { list().adapter?.itemCount == 1 && card(0).contentDescription.toString().contains("倒计时") }
        retained.performClick()
        idle()
        assertEquals("", sceneText(0))
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightRecordSceneDao().find("COUNT_UP", "shared-id") })
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightRecordSceneDao().find("COUNTDOWN", "other-account") })
        assertEquals("阅读", runBlocking(Dispatchers.IO) {
            FreeFlightArchiveCodec.decode(app.database.freeFlightArchiveDao()
                .get("shared-id", requireNotNull(payload).ownerScope)!!.payloadJson).scene.title
        })
    }

    private fun capturePicker(dialog: Dialog, name: String) {
        val root = dialog.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(310, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 270, 310)
        val bitmap = Bitmap.createBitmap(270, 310, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val target = File("build/reports/records/$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun cachedRecordsSurviveRepeatedRecreationWithoutAFilterOrAccountChange() {
        runBlocking(Dispatchers.IO) {
            saveIndependentRecord()
            (1..9).forEach { app.database.flightHistoryDao().insert(countdown("cached-$it",at=wall-it*1_000)) }
        }
        showRecords()
        await { list().adapter?.itemCount==10 }
        repeat(4) {
            scenario!!.recreate();scenario!!.onActivity { activity=it };idle()
            host=(activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
                .childFragmentManager.primaryNavigationFragment as RecordListHostFragment
            await { list().adapter?.itemCount==10 }
            assertEquals(View.GONE,field(RecordR.id.pf_record_page_empty_records_hint).visibility)
        }
    }

    @Test fun confirmedRemovalRefreshesTheRealPagerTotalsAndSharePreviewWithoutRemovingTheOtherMode() {
        runBlocking(Dispatchers.IO) {
            saveIndependentRecord()
            app.database.flightHistoryDao().insert(countdown("shared-id", at = wall - 100_000))
            (1..9).forEach { app.database.flightHistoryDao().insert(countdown("kept-$it", at = wall - it * 1_000)) }
        }
        showRecords()
        await { list().adapter?.itemCount == 10 }
        val originalUp = runBlocking(Dispatchers.IO) { app.database.freeFlightArchiveDao().get("shared-id", TokenStore.storageScope()) }
        val model = androidx.lifecycle.ViewModelProvider(host)[com.projectfocus.feature.record.list.RecordPageViewModel::class.java]
        val distance = requireNotNull(model.state.value.completedDistanceKm)
        page().requireView().findViewById<View>(RecordR.id.pf_record_page_btn_next_page).performClick()
        await { list().adapter?.itemCount == 1 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 2 页" }
        assertTrue(card(0).contentDescription.toString().contains("倒计时"))
        card(0).performClick()
        await { activity.findViewById<View>(RecordR.id.pf_record_page_share_preview) != null }
        val removalHost = com.projectfocus.feature.record.ui.RecordListFragment()
        host.childFragmentManager.beginTransaction().add(removalHost, "record-removal-owner").commitNow()
        val removal = androidx.lifecycle.ViewModelProvider(removalHost)[com.projectfocus.feature.record.list.RecordListViewModel::class.java]
        val target = runBlocking(Dispatchers.IO) { requireNotNull(removal.prepareDelete("shared-id")) }
        runBlocking(Dispatchers.IO) {
            app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete_ui BEFORE DELETE ON flight_history BEGIN SELECT RAISE(ABORT, 'temporary delete failure'); END")
        }
        var removalResult: Boolean? = null
        removal.delete(target) { removalResult = it }
        await { removalResult != null }
        assertEquals(false, removalResult)
        assertEquals(1, list().adapter?.itemCount)
        assertNotNull(activity.findViewById<View>(RecordR.id.pf_record_page_share_preview))
        assertEquals(distance, model.state.value.completedDistanceKm)
        runBlocking(Dispatchers.IO) {
            app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_delete_ui")
        }
        removalResult = null
        removal.delete(target) { removalResult = it }
        await { list().adapter?.itemCount == 10 && field(RecordR.id.pf_record_page_text_page_indicator).text.toString() == "第 1 页" &&
            activity.findViewById<View>(RecordR.id.pf_record_page_share_preview) == null && removalResult == true }
        assertEquals(distance - 99.25, requireNotNull(model.state.value.completedDistanceKm), 0.00001)
        assertEquals(originalUp, runBlocking(Dispatchers.IO) { app.database.freeFlightArchiveDao().get("shared-id", TokenStore.storageScope()) })
        capture("records-after-confirmed-removal")
        scenario!!.recreate()
        scenario!!.onActivity { activity = it }
        idle()
        host = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
            .childFragmentManager.fragments.first() as RecordListHostFragment
        await { list().adapter?.itemCount == 10 }
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById("shared-id") })
    }

    private fun capture(name: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val target = File("build/reports/records/$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
