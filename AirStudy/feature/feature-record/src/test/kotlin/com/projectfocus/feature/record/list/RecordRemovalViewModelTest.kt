package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordDeletionOperations
import com.projectfocus.service.focus.history.RecordDeletionResult
import com.projectfocus.service.focus.history.RecordDeletionTarget
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordRemovalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val operations = Operations()
    private lateinit var model: RecordRemovalViewModel
    private lateinit var target: RecordDeletionTarget

    @Before fun open() {
        Dispatchers.setMain(dispatcher)
        TokenStore.set("removal-account")
        val account = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
        target = RecordDeletionTarget(account, RecordedFlight.Countdown(FlightHistoryEntity(
            "confirmed-record",null,"SOLO",null,"PEK","Beijing Airport","Beijing","CN",40.0,116.0,
            "TSN","Tianjin Airport","Tianjin","CN",39.0,117.0,1_000L,301_000L,25,5,99.25,
            "CANCELLED",null,"01A",false,false,false,null,false,1_000L,1_000L,ownerScope=account.scope)))
        model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordRemovalViewModel(operations) as T
        })[RecordRemovalViewModel::class.java]
    }

    @After fun close() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        TokenStore.clear()
        Dispatchers.resetMain()
    }

    @Test fun confirmationWaitsForTheActualResultAndDuplicateClicksShareOneAttempt() {
        model.delete(target)
        model.delete(target)
        assertTrue(model.state.value.saving)
        assertNull(model.state.value.notice)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(target), operations.calls)
        operations.result.complete(RecordDeletionResult.DELETED)
        dispatcher.scheduler.runCurrent()
        assertFalse(model.state.value.saving)
        assertEquals(RecordRemovalNotice.DELETED, model.state.value.notice)
        model.acknowledge(model.state.value.attempt + 1)
        assertNotNull(model.state.value.notice)
        model.acknowledge(model.state.value.attempt)
        assertNull(model.state.value.notice)
    }

    @Test fun missingChangedAndProtectedResultsKeepTheirDistinctMeanings() {
        listOf(RecordDeletionResult.MISSING to RecordRemovalNotice.DELETED,
            RecordDeletionResult.CHANGED to RecordRemovalNotice.CHANGED,
            RecordDeletionResult.PROTECTED to RecordRemovalNotice.PROTECTED).forEachIndexed { index, (result, notice) ->
            operations.result = CompletableDeferred()
            model.delete(target)
            dispatcher.scheduler.runCurrent()
            assertEquals(index + 1L, model.state.value.attempt)
            operations.result.complete(result)
            dispatcher.scheduler.runCurrent()
            assertEquals(notice, model.state.value.notice)
        }
        assertEquals(3, operations.calls.size)
    }

    @Test fun aStorageFailureCanBeRetriedWithoutAnOptimisticSuccess() {
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        operations.result.completeExceptionally(IOException("write failed"))
        dispatcher.scheduler.runCurrent()
        assertEquals(RecordRemovalNotice.FAILED, model.state.value.notice)
        assertFalse(model.state.value.saving)
        operations.result = CompletableDeferred()
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        operations.result.complete(RecordDeletionResult.DELETED)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, operations.calls.size)
        assertEquals(RecordRemovalNotice.DELETED, model.state.value.notice)
    }

    @Test fun aLateCompletionCannotExposeThePreviousAccountsRecordOrNotice() {
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        TokenStore.set("another-removal-account")
        operations.result.complete(RecordDeletionResult.DELETED)
        dispatcher.scheduler.runCurrent()
        assertFalse(model.state.value.saving)
        assertNull(model.state.value.target)
        assertNull(model.state.value.notice)
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, operations.calls.size)
    }

    @Test fun completedNoticesAreDiscardedWhenTheirAccountBecomesObsolete() {
        operations.result.complete(RecordDeletionResult.DELETED)
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        assertNotNull(model.state.value.target)
        TokenStore.set("another-removal-account")
        model.discardObsoleteResult()
        assertNull(model.state.value.target)
        assertNull(model.state.value.notice)
    }

    @Test fun closingTheOwnerCancelsItsPendingOperationWithoutReportingSuccess() {
        model.delete(target)
        dispatcher.scheduler.runCurrent()
        store.clear()
        dispatcher.scheduler.runCurrent()
        assertTrue(operations.finished)
        assertFalse(model.state.value.saving)
        assertNull(model.state.value.notice)
    }

    private class Operations : RecordDeletionOperations {
        var result = CompletableDeferred<RecordDeletionResult>()
        val calls = mutableListOf<RecordDeletionTarget>()
        var finished = false
        override suspend fun captureCountdown(id: String): RecordDeletionTarget? = error("Unexpected capture")
        override suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult {
            calls += target
            try { return result.await() } finally { finished = true }
        }
    }
}
