package com.projectfocus.feature.record.ui

import android.app.Application
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.*
import com.projectfocus.feature.record.share.RecordPreviewHostActivity
import com.projectfocus.service.focus.history.*
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h936dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class RecordRemovalPromptTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val completed = CompletableDeferred<RecordDeletionResult>()
    private lateinit var scenario: ActivityScenario<RecordPreviewHostActivity>
    private lateinit var activity: RecordPreviewHostActivity
    private lateinit var model: RecordRemovalViewModel
    private lateinit var prompt: RecordRemovalPrompt
    private lateinit var page: RecordPageState
    private lateinit var target: RecordDeletionTarget

    @Before fun open() {
        Dispatchers.setMain(dispatcher); TokenStore.set("removal-prompt-owner"); ShadowToast.reset()
        scenario = ActivityScenario.launch(RecordPreviewHostActivity::class.java)
        scenario.onActivity { activity = it }
        val account = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
        val row = FlightHistoryEntity("confirmed-record", null, "SOLO", null,
            "PEK", "Airport", "Beijing", "CN", 40.0, 116.0, "TSN", "Airport", "Tianjin", "CN", 39.0, 117.0,
            1_000, 301_000, 25, 5, 99.25, "CANCELLED", null, "01A", false, false, false, null, false, 1_000, 1_000,
            ownerScope = account.scope)
        val record = RecordedFlight.Countdown(row); target = RecordDeletionTarget(account, record)
        page = RecordPageProjection.build(HistoryReadState.Loaded(account, listOf(record), emptyMap(), emptyMap()),
            RecordPageQuery(period = RecordPeriod.ALL), 301_000, ZoneOffset.UTC)
        val operations = object : RecordDeletionOperations {
            override suspend fun captureCountdown(id: String): RecordDeletionTarget? = null
            override suspend fun delete(target: RecordDeletionTarget) = completed.await()
        }
        model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordRemovalViewModel(operations) as T
        })[RecordRemovalViewModel::class.java]
        prompt = RecordRemovalPrompt(activity, scope, model, { page }, { true })
        prompt.open(page.items.single()); dispatcher.scheduler.runCurrent()
        assertTrue(ReflectionHelpers.getField<RecordDeleteDialog>(prompt, "dialog").isShowing)
        model.delete(target); dispatcher.scheduler.runCurrent()
        completed.complete(RecordDeletionResult.DELETED); dispatcher.scheduler.runCurrent()
        assertEquals(RecordRemovalNotice.DELETED, model.state.value.notice)
    }
    @After fun close() {
        prompt.close(); scope.cancel(); store.clear(); dispatcher.scheduler.runCurrent()
        scenario.close(); TokenStore.clear(); Dispatchers.resetMain()
    }
    @Test fun listRefreshCanDismissThePromptWithoutCancellingItsQueuedSuccessNotice() {
        prompt.showNotice(model.state.value)
        page = page.copy(items = emptyList(), deletionTargets = emptyMap())
        prompt.keepFor(page)
        dispatcher.scheduler.runCurrent()
        assertEquals(activity.getString(R.string.pf_record_delete_success), ShadowToast.getTextOfLatestToast()?.toString())
        assertNull(model.state.value.notice)
    }
    @Test fun anAccountChangeStillDiscardsTheQueuedNotice() {
        prompt.showNotice(model.state.value)
        TokenStore.set("another-removal-owner")
        page = page.copy(items = emptyList(), deletionTargets = emptyMap())
        prompt.keepFor(page); dispatcher.scheduler.runCurrent()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun closingTheScreenStillCancelsTheQueuedNotice() {
        prompt.showNotice(model.state.value); prompt.close(); dispatcher.scheduler.runCurrent()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
