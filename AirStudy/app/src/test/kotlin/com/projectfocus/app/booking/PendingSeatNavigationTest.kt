package com.projectfocus.app.booking

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.feature.booking.draft.BookingDraftStore
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.feature.booking.seat.SeatSelectionStore
import com.projectfocus.service.focus.session.SessionState
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import com.projectfocus.feature.booking.R as BookingR

/** Actual Main/Seat Fragment and typed result, using source snapshot metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
class PendingSeatNavigationTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        TokenStore.clear()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        onActivity { activity ->
            SeatSelectionStore(activity).clearAll()
            BookingDraftStore(activity).clearAll()
            host(activity).navController.navigate(R.id.action_permission_to_home)
        }
        idle()
    }

    @After fun cleanup() { scenario?.close(); scenario = null; TokenStore.clear() }

    @Test fun sourceCityNumberAndNameLabelsReachSeatDraftAndNbspPresetReturnsWithoutStartingSession() {
        val request = request(" \t\u00a0\r")
        val key = "r166-pending-city-and-nbsp"
        val args = checkNotNull(SourcePendingSeatArguments.from(request, key))
        assertThat(request.candidate.route).isNull()
        assertThat(args.getString("flightNo")).isEqualTo("SC8728")
        assertThat(args.getString("from")).isEqualTo("Capital Airport")
        assertThat(args.getString("to")).isEqualTo("Pudong Airport")
        assertThat(args.getString("planSceneTitle")).isEqualTo("\u00a0")
        assertThat(args.containsKey("seat")).isFalse()
        var result: SeatSelectionResult? = null
        onActivity { activity ->
            host(activity).childFragmentManager.setFragmentResultListener(SeatDraftRequests.SELECTION, activity) { _, bundle ->
                @Suppress("DEPRECATION")
                result = bundle.getSerializable(SeatDraftRequests.RESULT_DATA) as? SeatSelectionResult
            }
            host(activity).navController.navigate(R.id.seatSelectionFragment, args)
        }
        await("seat page attached") { value { seatRoot(it) != null } }
        onActivity { activity ->
            val root = checkNotNull(seatRoot(activity))
            assertThat(root.findViewById<SeatSelectionMapView>(BookingR.id.seatMap).selectedSeat).isNull()
            assertThat(root.findViewById<View>(BookingR.id.btnConfirmSeat).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewWithTag<TextView>("01A").performClick()).isTrue()
        }
        idle()
        assertThat((ShadowDialog.getLatestDialog() as? AnchoredScenePickerDialog)?.isShowing ?: false).isFalse()
        onActivity { activity ->
            val root = checkNotNull(seatRoot(activity))
            assertThat(root.findViewById<SeatSelectionMapView>(BookingR.id.seatMap).selectedSeat).isEqualTo("01A")
            val confirm = root.findViewById<View>(BookingR.id.btnConfirmSeat)
            assertThat(confirm.visibility).isEqualTo(View.VISIBLE)
            assertThat(confirm.performClick()).isTrue()
        }
        await("actual typed result returned") { result != null }
        assertThat(result?.draftKey).isEqualTo(key)
        assertThat(result?.scene?.title).isEqualTo("\u00a0")
        onActivity { activity ->
            val draft = checkNotNull(BookingDraftStore(activity).load(key))
            assertThat(draft.flightNo).isEqualTo("SC8728")
            assertThat(draft.fromCode).isEqualTo("Capital Airport")
            assertThat(draft.toCode).isEqualTo("Pudong Airport")
            assertThat(draft.recordId).isEmpty()
            assertThat(activity.sessionRepository.state.value).isInstanceOf(SessionState.Idle::class.java)
        }
        assertThat(request.candidate.snapshot.flightNo).isEqualTo("old-wire-number")
        assertThat(request.candidate.snapshot.seatNo).isEqualTo("30F")
    }

    @Test fun javaEmptySceneIsNotInstalledAsPresetAndTheChairOpensTheRealPicker() {
        val args = checkNotNull(SourcePendingSeatArguments.from(request(" \t\r"), "r166-no-preset"))
        assertThat(args.containsKey("planSceneTitle")).isFalse()
        assertThat(args.containsKey("planSceneType")).isFalse()
        onActivity { host(it).navController.navigate(R.id.seatSelectionFragment, args) }
        await("seat page attached") { value { seatRoot(it) != null } }
        onActivity { checkNotNull(seatRoot(it)).findViewWithTag<TextView>("01A").performClick() }
        await("source anchored picker is shown") { (ShadowDialog.getLatestDialog() as? AnchoredScenePickerDialog)?.isShowing == true }
        onActivity { activity ->
            assertThat(checkNotNull(seatRoot(activity)).findViewById<View>(BookingR.id.btnConfirmSeat).visibility).isEqualTo(View.GONE)
            assertThat(activity.sessionRepository.state.value).isInstanceOf(SessionState.Idle::class.java)
            (ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog).dismiss()
        }
    }

    private fun request(title: String): PendingPlanSeatSelection {
        val from = FlightPlanAirportDto(1, "PEK", " Capital Airport ", " Beijing ", "invalid-lat", "116", "CN", countryRegionId = 156)
        val to = FlightPlanAirportDto(2, "PVG", "Pudong Airport", "Shanghai", "31", "121", "CN", countryRegionId = 156)
        val wire = FlightPlanLegSnapshot(1, from, to, "2026-10-31T01:00:00Z", "waiting_takeoff", 100.0,
            "old-wire-number", "30F", 2, title)
        return PendingPlanSeatSelection("r166-input-identity", "2026-10-31", "Asia/Shanghai", DailyFlightLeg(wire, null))
    }
    private fun host(activity: MainActivity) = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
    private fun seatRoot(activity: MainActivity): View? = host(activity).childFragmentManager.primaryNavigationFragment?.view
        ?.takeIf { it.findViewById<View>(BookingR.id.seatMap) != null }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun onActivity(block: (MainActivity) -> Unit) { checkNotNull(scenario).onActivity(block) }
    @Suppress("UNCHECKED_CAST") private fun <T> value(block: (MainActivity) -> T): T {
        var result: Any? = null
        onActivity { result = block(it) }
        return result as T
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do { idle(); if (condition()) return; Thread.sleep(10) } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out: $label")
    }
}
