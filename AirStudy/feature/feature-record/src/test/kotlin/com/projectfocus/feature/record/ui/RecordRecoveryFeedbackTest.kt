package com.projectfocus.feature.record.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.share.RecordPreviewHostActivity
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordRecoveryStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h936dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class RecordRecoveryFeedbackTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var scenario: ActivityScenario<RecordPreviewHostActivity>
    private lateinit var activity: RecordPreviewHostActivity
    private lateinit var root: View
    private lateinit var feedback: RecordRecoveryFeedback
    private lateinit var pending: RecordRecoveryStatus
    private var retries = 0
    private var logins = 0
    private var visible = true

    @Before fun open() {
        Dispatchers.setMain(dispatcher); TokenStore.set("recovery-feedback-owner")
        scenario = ActivityScenario.launch(RecordPreviewHostActivity::class.java)
        scenario.onActivity { activity = it }
        root = activity.layoutInflater.inflate(R.layout.pf_record_page, null)
        activity.setContentView(root)
        pending = RecordRecoveryStatus(HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation), missingArchives = 1)
        feedback = RecordRecoveryFeedback(activity, root, scope, { visible }, { retries++ }, { logins++ })
    }
    @After fun close() {
        feedback.close(); scope.cancel(); dispatcher.scheduler.runCurrent()
        scenario.close(); TokenStore.clear(); Dispatchers.resetMain()
    }
    private fun dialog(): AlertDialog {
        root.findViewById<View>(R.id.pf_record_recovery_details).performClick()
        dispatcher.scheduler.runCurrent()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    @Test fun onlyRecordsNeedingRecoveryExposeTheExplanationAndRetry() {
        feedback.render(RecordRecoveryStatus(pending.account))
        assertEquals(View.GONE, root.findViewById<View>(R.id.pf_record_recovery_notice).visibility)
        feedback.render(pending)
        assertEquals("1 条记录等待恢复", root.findViewById<TextView>(R.id.pf_record_recovery_summary).text.toString())
        val shown = dialog()
        val message = shown.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains("缺少完整资料")); assertTrue(message.contains("重试恢复"))
        val retry = shown.getButton(AlertDialog.BUTTON_POSITIVE)
        retry.performClick(); retry.performClick(); dispatcher.scheduler.runCurrent()
        assertEquals(1, retries); assertEquals(0, logins)
        feedback.render(RecordRecoveryStatus(pending.account))
        assertEquals(View.GONE, root.findViewById<View>(R.id.pf_record_recovery_notice).visibility)
    }

    @Test fun leavingThePageCancelsAQueuedRetryAndDisablesRetainedButtons() {
        feedback.render(pending)
        val shown = dialog(); val retry = shown.getButton(AlertDialog.BUTTON_POSITIVE)
        retry.performClick(); feedback.close(); dispatcher.scheduler.runCurrent()
        assertEquals(0, retries)
        retry.performClick(); dispatcher.scheduler.runCurrent()
        assertEquals(0, retries)
    }

    @Test fun accountChangesCannotApplyThePreviousAccountsRecoveryActions() {
        feedback.render(pending)
        val shown = dialog(); val retry = shown.getButton(AlertDialog.BUTTON_POSITIVE)
        TokenStore.set("other-recovery-owner")
        retry.performClick(); dispatcher.scheduler.runCurrent()
        assertEquals(0, retries)
        feedback.render(RecordRecoveryStatus())
        assertFalse(shown.isShowing)
        assertEquals(View.GONE, root.findViewById<View>(R.id.pf_record_recovery_notice).visibility)
    }

    @Test fun anActiveRecoveryDisablesAnotherRetryAndRetainsItsDetails() {
        feedback.render(pending.copy(syncing = true))
        val shown = dialog()
        assertFalse(shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals("正在恢复…", root.findViewById<TextView>(R.id.pf_record_recovery_details).text.toString())
        feedback.render(pending)
        assertFalse(shown.isShowing)
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals(0, retries)
    }

    @Test fun expiredAuthenticationOffersTheNormalSignInEntry() {
        feedback.render(pending.copy(authenticationRequired = true))
        val shown = dialog()
        assertEquals("重新登录", shown.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); dispatcher.scheduler.runCurrent()
        assertEquals(1, logins); assertEquals(0, retries)
    }
}
