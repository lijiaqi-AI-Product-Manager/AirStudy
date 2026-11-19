package com.projectfocus.app.booking

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.projectfocus.app.R
import com.projectfocus.app.MainActivity
import com.projectfocus.app.auth.AuthLoginActivity
import com.projectfocus.app.auth.AuthCapabilityActivity
import com.projectfocus.app.route.AirportSearchHostFragment
import com.projectfocus.feature.airport.ui.AirportReferencePoint
import com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs
import com.projectfocus.app.route.RouteArgs
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.common.plan.SourceTakeoffClock
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.common.plan.SourceFlightPlanTiming
import com.projectfocus.core.common.plan.SourceFlightArrivalOutcome
import com.projectfocus.feature.booking.plan.TakeoffPlanAirportUi
import com.projectfocus.feature.booking.plan.TakeoffPlanCallbacks
import com.projectfocus.feature.booking.plan.TakeoffPlanPanel
import com.projectfocus.feature.booking.plan.TakeoffPlanEditorStep
import com.projectfocus.feature.booking.plan.TakeoffPlanUiState
import com.projectfocus.feature.booking.plan.TakeoffPlanView
import com.projectfocus.feature.booking.plan.TakeoffPlanSegmentUi
import com.projectfocus.feature.booking.seat.SeatSceneChoicesHost
import com.projectfocus.feature.booking.seat.SeatSceneEditorSession
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.core.ui.scene.ScenePickerOption
import com.projectfocus.app.scene.AppSceneCatalog
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.feature.scene.source.SourceSceneOption
import com.projectfocus.feature.scene.source.SourceSceneRecord
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.UUID
import com.projectfocus.core.common.plan.SourceTakeoffClock.newDateSupported
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.projectfocus.feature.booking.R as BookingR
import com.projectfocus.core.ui.R as CoreUiR
import timber.log.Timber

/** Source three-step editor, temporary compact preview and separate final plan save. */
@AndroidEntryPoint
class FlightPreparationFragment : Fragment(R.layout.fragment_flight_preparation) {
    private val viewModel by lazy {
        ViewModelProvider(this)[FlightPreparationViewModel::class.java]
    }

    private lateinit var seatNamespace: String
    private var pendingSeatKey: String? = null
    private var returningHome = false
    private var sceneDialog: AnchoredScenePickerDialog? = null
    private var sceneEditor: SeatSceneEditorSession? = null
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshPlanEntitlement()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seatNamespace = savedInstanceState?.getString(STATE_SEAT_NAMESPACE) ?: UUID.randomUUID().toString()
        pendingSeatKey = savedInstanceState?.getString(STATE_PENDING_SEAT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.bindPlanHost((requireActivity() as MainActivity).planBindingToken)
        view.findViewById<View>(R.id.btnPreparationStart).setOnClickListener { viewModel.startFlight() }
        view.findViewById<TakeoffPlanView>(R.id.takeoffPlan).callbacks = TakeoffPlanCallbacks(
            onBack = { viewModel.back() },
            onSelectOrigin = { selectAirport(true) },
            onSelectDestination = {
                if (viewModel.state.value.stage == PreparationStage.DESTINATION) selectAirport(false)
                else viewModel.beginEditing()
            },
            onConfirmDestination = { viewModel.nextDestination() },
            onSelectScene = { selectScene() },
            onConfirmScene = { viewModel.nextScene() },
            onDepartureTimeChanged = { hour, minute -> viewModel.selectDepartureTime(hour, minute) },
            onConfirmTime = { viewModel.previewPlan() },
            onConfirmPlan = { viewModel.confirmPlan() },
            onExpand = { viewModel.expand(it) },
            onSeat = { selectSeat() },
            onDateOffset = { viewModel.browseDate(it) },
            onAddSegment = { viewModel.addSegment() },
            onEditSegment = { viewModel.editSegment(it) },
            onRemoveSegment = { viewModel.removeSegment(it) },
            onDeleteCanceledSegments = { viewModel.deleteCanceledSegments(it) },
            onPlanEnabled = { viewModel.togglePlan(it) },
            onAccountAction = { openPlanAccount() },
        )
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // The original Main.D() delegate is not closed. This host deliberately
            // applies the proven floating-arrow semantics to Android system Back.
            if (!viewModel.back()) findNavController().navigateUp()
        }

        parentFragmentManager.setFragmentResultListener(SeatDraftRequests.SELECTION, viewLifecycleOwner) { _, result ->
            val selection = result.getSerializable(SeatDraftRequests.RESULT_DATA) as? SeatSelectionResult
            val expected = seatKey(viewModel.state.value)
            val globalRequest = pendingSeatKey?.takeIf { it.startsWith(GLOBAL_SEAT_PREFIX) }?.removePrefix(GLOBAL_SEAT_PREFIX)
            if (selection != null && selection.recordId.isBlank() && globalRequest != null && selection.draftKey == pendingSeatKey) {
                if (viewModel.acceptGlobalSeat(globalRequest, selection.seat)) pendingSeatKey = null
            } else if (selection != null && selection.recordId.isBlank() &&
                selection.draftKey == pendingSeatKey && selection.draftKey == expected
            ) {
                pendingSeatKey = null
                viewModel.selectSeat(selection.seat)
            }
            parentFragmentManager.clearFragmentResult(SeatDraftRequests.SELECTION)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(view, state)
                    state.pendingGlobalSeat?.let { openGlobalSeat(it) }
                    if (state.started) returnToFlight()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.batchDeletionEvents.collect { count ->
                    Toast.makeText(requireContext(), if (count > 0) getString(BookingR.string.pf_tp_plan_deleted_canceled_legs_fmt, count)
                        else getString(BookingR.string.pf_tp_plan_no_deletable_canceled_legs), Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.planEnableMessages.collect { message ->
                    val text = when (message.kind) {
                        PlanEnableNotice.ONLY_TODAY -> getString(BookingR.string.pf_pg_toast_only_today_flight_plan)
                        PlanEnableNotice.IN_PROGRESS -> getString(BookingR.string.pf_pg_toast_flight_in_progress)
                        PlanEnableNotice.NO_WAITING -> getString(BookingR.string.pf_pg_toast_no_waiting_flight)
                        PlanEnableNotice.FLY_BACK_FIRST -> getString(BookingR.string.pf_pg_toast_fly_back_first_fmt, message.argument.orEmpty())
                        PlanEnableNotice.CANNOT_CLOSE -> getString(BookingR.string.pf_pg_toast_flight_in_progress_cannot_close)
                        PlanEnableNotice.STORAGE_FAILED -> getString(R.string.flight_preparation_error_save)
                        PlanEnableNotice.SYNCING_STATUS -> getString(BookingR.string.pf_pg_toast_syncing_flight_status)
                    }
                    Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { viewModel.refreshClock(); delay(1_000L) }
            }
        }
    }

    private fun selectScene() {
        val current = viewModel.state.value
        if (current.loading || current.busy || current.started || current.stage != PreparationStage.SCENE) return
        val context = requireContext()
        val store = SharedPreferencesSceneCatalogStore.from(context)
        var options = try { store.readEnabled(AppSceneCatalog.SCOPE).options }
            catch (_: Exception) { sceneUnavailable(); return }
        sceneDialog?.dismiss()
        sceneEditor?.close()
        val editor = (activity as? SeatSceneChoicesHost)?.createSeatSceneEditor(Bundle.EMPTY)
        sceneEditor = editor
        fun uiOptions(): List<ScenePickerOption> = options.map { option ->
            val presentation = SourceSceneCatalog.present(context, option)
            ScenePickerOption(option.optionId, presentation.title, presentation.iconRes, presentation.color)
        }
        val dialog = AnchoredScenePickerDialog(
            context = context,
            anchor = requireView().findViewById(BookingR.id.pf_tp_floating_scene_selector),
            seatCaption = null,
            options = uiOptions(),
            selectedId = current.scene?.optionId,
            onSelected = { picked ->
                try {
                    store.readEnabled(AppSceneCatalog.SCOPE).options.firstOrNull { it.optionId == picked.id }
                        ?.let { viewModel.selectScene(it.toPlannedScene()) }
                } catch (_: Exception) { sceneUnavailable() }
            },
            onAdd = editor?.let { session -> { changed: () -> Unit -> session.add(changed) } },
            onRemove = editor?.let { session -> { option: ScenePickerOption, changed: () -> Unit -> session.remove(option.id, changed) } },
            reloadOptions = {
                try { options = store.readEnabled(AppSceneCatalog.SCOPE).options }
                catch (_: Exception) { sceneUnavailable() }
                uiOptions()
            },
        )
        dialog.setOnDismissListener {
            editor?.close()
            if (sceneEditor === editor) sceneEditor = null
            if (sceneDialog === dialog) sceneDialog = null
        }
        sceneDialog = dialog
        dialog.show()
    }

    private fun SourceSceneOption.toPlannedScene(): PlannedScene = record.let {
        PlannedScene(optionId, it.name, it.iconKey, it.color, it.isCustom, it.cloudId, it.clientUuid,
            SourceSceneCatalog.label(requireContext(), it))
    }

    private fun sceneUnavailable() {
        if (isAdded) Toast.makeText(requireContext(), BookingR.string.seat_selection_scenes_unavailable, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        sceneDialog?.dismiss()
        sceneDialog = null
        sceneEditor?.close()
        sceneEditor = null
        view?.findViewById<TakeoffPlanView>(R.id.takeoffPlan)?.callbacks = TakeoffPlanCallbacks()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        consumeAirportSelection()
        viewModel.refreshPlanEntitlement()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SEAT_NAMESPACE, seatNamespace)
        outState.putString(STATE_PENDING_SEAT, pendingSeatKey)
        super.onSaveInstanceState(outState)
    }

    private fun consumeAirportSelection() {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.flightPreparationFragment) return
        val handle = nav.currentBackStackEntry?.savedStateHandle ?: return
        if (handle.get<Boolean>(AirportSearchHostFragment.KEY_PICKED) != true) return
        val code = handle.get<String>(AirportSearchHostFragment.KEY_AIRPORT_CODE)
        val asOrigin = handle.get<Boolean>(AirportSearchHostFragment.KEY_PICKED_ORIGIN) == true
        // Remove the event before dispatch, including when the selected code is no longer valid.
        handle.remove<Boolean>(AirportSearchHostFragment.KEY_PICKED)
        handle.remove<String>(AirportSearchHostFragment.KEY_AIRPORT_CODE)
        handle.remove<Boolean>(AirportSearchHostFragment.KEY_PICKED_ORIGIN)
        if (!code.isNullOrBlank()) viewModel.selectAirport(code, asOrigin)
    }

    private fun selectAirport(asOrigin: Boolean) {
        val state = viewModel.state.value
        if (state.busy || state.loading || state.started) return
        // Product host adapter: show time to the known opposite route endpoint.

        val opposite = if (asOrigin) state.destination else state.origin
        findNavController().navigate(
            R.id.airportSearchFragment,
            Bundle().apply {
                putString(RouteArgs.ARG_MODE, if (asOrigin) RouteArgs.MODE_PICK_ORIGIN else RouteArgs.MODE_PICK_DESTINATION)
                AirportSearchReferenceArgs.writeTo(this, opposite?.let {
                    AirportReferencePoint.from(it.latitude, it.longitude)
                })
            },
        )
    }

    private fun selectSeat() {
        val state = viewModel.state.value
        if (state.busy || state.loading || state.started) return
        val plan = state.plan ?: return
        val draftKey = seatKey(state) ?: return
        pendingSeatKey = draftKey
        val uri = Uri.parse("projectfocus://seat").buildUpon()
            .appendQueryParameter("flightNo", "")
            .appendQueryParameter("from", plan.departure.code)
            .appendQueryParameter("to", plan.destination.code)
            .appendQueryParameter("recordId", "")
            .appendQueryParameter("draftKey", draftKey)
            .appendQueryParameter("seat", plan.seat.orEmpty())
            .build()
        findNavController().navigate(uri)
    }

    private fun seatKey(state: PreparationUiState): String? = state.plan?.let { plan ->
        "preparation:$seatNamespace:${plan.departure.id}:${plan.destination.id}"
    }

    private fun openGlobalSeat(request: PendingPlanSeatSelection) {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.flightPreparationFragment) return
        val key = GLOBAL_SEAT_PREFIX + request.requestId
        val args = SourcePendingSeatArguments.from(request, key) ?: return
        pendingSeatKey = key
        nav.navigate(R.id.seatSelectionFragment, args)
        viewModel.globalSeatNavigationOpened(request.requestId)
    }

    private fun openPlanAccount() {
        if (viewModel.state.value.entitlementGateAuthenticated) {
            startActivity(Intent(requireContext(), AuthCapabilityActivity::class.java).putExtra("capability", "subscription"))
        } else login.launch(Intent(requireContext(), AuthLoginActivity::class.java))
    }

    private fun render(view: View, state: PreparationUiState) {
        val interactive = !state.loading && !state.busy && !state.started && !state.planOperationBusy
        val origin = state.origin
        val destination = state.destination
        val plan = state.plan
        val selected = state.scene
        val sceneText = selected?.let { scene -> SourceSceneCatalog.label(requireContext(), SourceSceneRecord(
            scene.name, scene.iconKey, scene.color, scene.isCustom, scene.cloudId, scene.clientUuid,
        )) }
        val departure = state.departureClock
        val departureText = departure?.let { SourceTakeoffClock.format(it.minuteOfDay) }


        val eta = if (departureText == null || plan == null) null
            else SourceTakeoffClock.eta(departureText, plan.durationMinutes)
        val panel = when (state.stage) {
            PreparationStage.DESTINATION, PreparationStage.SCENE, PreparationStage.DEPARTURE_TIME -> TakeoffPlanPanel.FLOATING_DESTINATION
            PreparationStage.PREVIEW, PreparationStage.COMPACT -> TakeoffPlanPanel.ADD_SEGMENT_COMPACT
            PreparationStage.EXPANDED -> TakeoffPlanPanel.EXPANDED
            PreparationStage.COLLAPSED -> TakeoffPlanPanel.SET_DESTINATION_COMPACT
        }
        val timeAction = when {
            departure == null -> getString(BookingR.string.pf_tp_takeoff_plan_time_select_first)
            !state.timeCanPreview -> getString(BookingR.string.pf_tp_takeoff_plan_time_earliest_fmt, state.earliestDeparture)
            else -> null
        }
        val etaText = if (state.timeCanPreview) getString(
            BookingR.string.pf_tp_takeoff_plan_eta_prev_and_current_fmt, state.previousArrivalText ?: "--:--", eta ?: "--:--",
        ) else getString(BookingR.string.pf_tp_takeoff_plan_eta_invalid_fmt, state.previousArrivalText ?: "--:--", state.earliestDeparture)
        val kmUnit = getString(BookingR.string.pf_tp_common_km)
        val expanded = state.stage == PreparationStage.EXPANDED
        val draftType = selected?.let { SourceSceneCatalog.type(it.iconKey) } ?: 1
        val draftBuiltin = SourceSceneCatalog.builtins.firstOrNull { it.type == draftType }
        val now = java.time.ZonedDateTime.now()
        val displayed = state.dailyPlan?.displayLegs().orEmpty()
        val delays = SourceFlightPlanTiming.delays(displayed.map { leg ->
            SourceFlightPlanTiming.Segment(leg.snapshot.legStatus,
                FlightPlanCardPresentation.instantMillis(leg.snapshot.scheduledDepartureAt), leg.snapshot.distanceKm)
        }, now.toInstant().toEpochMilli())
        val segments = if (expanded) displayed.mapIndexed { index, leg ->
            val wire = leg.snapshot
            val builtin = SourceSceneCatalog.builtins.firstOrNull { it.type == wire.focusIconCode }
            val rawStatus = SourceFlightPlanStatus.parse(wire.legStatus)
            val canceled = FlightPlanCardPresentation.canceled(wire, now.toLocalDate(), now.zone)
            val outcome = if (!canceled && rawStatus == SourceFlightPlanStatus.COMPLETED)
                FlightPlanCardPresentation.outcome(wire, state.completionReceipts[wire.legIndex]) else null
            val status = when {
                canceled -> BookingR.string.pf_tp_plan_status_canceled
                outcome?.kind == SourceFlightArrivalOutcome.Kind.ON_TIME -> BookingR.string.pf_tp_plan_status_completed_on_time
                outcome?.kind == SourceFlightArrivalOutcome.Kind.EARLY -> BookingR.string.pf_tp_plan_status_completed_early_fmt
                outcome?.kind == SourceFlightArrivalOutcome.Kind.DELAYED -> BookingR.string.pf_tp_plan_status_completed_delayed_fmt
                rawStatus == SourceFlightPlanStatus.IN_PROGRESS -> BookingR.string.pf_tp_plan_status_in_progress
                rawStatus == SourceFlightPlanStatus.COMPLETED -> BookingR.string.pf_tp_plan_status_completed
                else -> BookingR.string.pf_tp_takeoff_plan_status_waiting_takeoff
            }
            val color = when {
                canceled -> BookingR.color.pf_tp_plan_status_canceled
                outcome?.kind == SourceFlightArrivalOutcome.Kind.ON_TIME -> BookingR.color.pf_tp_plan_status_completed_on_time
                outcome?.kind == SourceFlightArrivalOutcome.Kind.EARLY -> BookingR.color.pf_tp_plan_status_completed_early
                outcome?.kind == SourceFlightArrivalOutcome.Kind.DELAYED -> BookingR.color.pf_tp_plan_status_completed_delayed
                rawStatus == SourceFlightPlanStatus.IN_PROGRESS -> BookingR.color.pf_tp_plan_status_in_progress
                else -> BookingR.color.pf_tp_plan_status_waiting
            }
            val delay = FlightPlanCardPresentation.delayLabel(wire, delays[index], now.toLocalDate(), now.zone)
            TakeoffPlanSegmentUi(
                id = viewModel.segmentId(index),
                origin = wire.departureAirport?.let { TakeoffPlanAirportUi(it.code, it.city) },
                destination = wire.arrivalAirport?.let { TakeoffPlanAirportUi(it.code, it.city) },
                tagText = getString(BookingR.string.pf_tp_takeoff_plan_segment_label_fmt, maxOf(1, wire.legIndex)),
                departureTimeText = FlightPlanCardPresentation.clock(wire.scheduledDepartureAt),
                arrivalTimeText = FlightPlanCardPresentation.arrival(wire),
                distanceText = FlightPlanCardPresentation.distance(wire.distanceKm, kmUnit),
                sceneText = wire.focusSceneText,
                sceneIconRes = builtin?.iconRes ?: SourceSceneCatalog.icon("study"),
                sceneColor = builtin?.color ?: 0,
                statusText = if (outcome?.kind in setOf(SourceFlightArrivalOutcome.Kind.EARLY, SourceFlightArrivalOutcome.Kind.DELAYED))
                    getString(status, outcome!!.minutes) else getString(status), statusColor = requireContext().getColor(color),
                statusTextStyle = when (outcome?.kind) { SourceFlightArrivalOutcome.Kind.EARLY -> 1; SourceFlightArrivalOutcome.Kind.DELAYED -> 3; else -> 0 },
                departureDelayText = delay, arrivalDelayText = delay,
                terminalAlpha = if (canceled || rawStatus == SourceFlightPlanStatus.COMPLETED) .45f else 1f,
                timeColor = if (canceled) requireContext().getColor(BookingR.color.pf_tp_plan_status_canceled) else null,
                editable = viewModel.canEdit(index), removable = viewModel.canRemove(index),
                batchDeleteCanceled = viewModel.canDeleteCanceled(index),
                contextMenuEnabled = rawStatus != SourceFlightPlanStatus.COMPLETED && (!canceled || viewModel.canDeleteCanceled(index)) &&
                    !wire.archived && !wire.readOnly &&
                    newDateSupported(state.selectedDate, LocalDate.now()),
            )
        } else if (plan == null) emptyList() else listOf(TakeoffPlanSegmentUi(
            id = "preparation-current-leg",
            origin = TakeoffPlanAirportUi(plan.departure.code, plan.departure.displayCity.ifBlank { plan.departure.displayName }),
            destination = TakeoffPlanAirportUi(plan.destination.code, plan.destination.displayCity.ifBlank { plan.destination.displayName }),
            departureTimeText = departureText, arrivalTimeText = eta,
            distanceText = FlightPlanCardPresentation.distance(plan.distanceKm, kmUnit), sceneText = sceneText,
            sceneIconRes = draftBuiltin?.iconRes ?: SourceSceneCatalog.icon("study"), sceneColor = draftBuiltin?.color ?: 0,
            statusText = getString(BookingR.string.pf_tp_takeoff_plan_status_waiting_takeoff),
            statusColor = requireContext().getColor(BookingR.color.pf_tp_plan_status_waiting),
        ))
        view.findViewById<TakeoffPlanView>(R.id.takeoffPlan).apply {
            render(TakeoffPlanUiState(
                panel = panel,
                editorStep = when (state.stage) {
                    PreparationStage.SCENE -> TakeoffPlanEditorStep.SCENE
                    PreparationStage.DEPARTURE_TIME -> TakeoffPlanEditorStep.DEPARTURE_TIME
                    else -> TakeoffPlanEditorStep.DESTINATION
                },
                origin = origin?.let { TakeoffPlanAirportUi(it.code, it.displayCity.ifBlank { it.displayName }) }
                    ?: state.originCode?.let { TakeoffPlanAirportUi(it, "") },
                destination = destination?.let { TakeoffPlanAirportUi(it.code, it.displayCity.ifBlank { it.displayName }) },
                totalDistanceText = if (expanded) String.format(Locale.US, "%.1f", state.dailyPlan?.legs.orEmpty()
                    .filter { SourceFlightPlanStatus.parse(it.snapshot.legStatus) != SourceFlightPlanStatus.CANCELED }.sumOf { it.snapshot.distanceKm }) + " " + kmUnit
                    else plan?.let { FlightPlanCardPresentation.distance(it.distanceKm, kmUnit) },
                seatText = if (state.planEnabled || state.globalSeatText != null) state.globalSeatText else plan?.seat,
                planEnabled = state.planEnabled,
                sceneText = sceneText,
                sceneIconRes = selected?.let { SourceSceneCatalog.icon(it.iconKey) },
                sceneColor = selected?.color,
                departureHour = departure?.hour,
                departureMinute = departure?.minute,
                etaText = etaText,
                timeActionText = timeAction,
                dateText = state.selectedDate,
                confirmMode = state.stage == PreparationStage.PREVIEW,
                canTogglePlan = true,
                entitlementGateVisible = state.entitlementGateVisible,
                entitlementGateAuthenticated = state.entitlementGateAuthenticated,
                canBrowsePreviousDate = expanded,
                canBrowseNextDate = expanded && state.canBrowseNextDate,
                canAddSegment = state.canAddSegment,
                canSave = when (state.stage) {
                    PreparationStage.DESTINATION, PreparationStage.SCENE -> true
                    PreparationStage.DEPARTURE_TIME -> state.timeCanPreview
                    PreparationStage.PREVIEW -> plan != null && !state.confirmed
                    else -> false
                },
                segments = segments,
                loading = state.loading || state.busy,
                enabled = interactive,
            ))
        }
        // This product host is a standalone themed page while the original
        // shared map overlay is unfinished. Its white overlay footer/date copy
        // needs readable theme ink here; keep the imported component XML intact.
        val hostInk = requireContext().getColor(CoreUiR.color.text_primary)
        for (id in listOf(BookingR.id.pf_tp_date_title, BookingR.id.pf_tp_total_distance, BookingR.id.pf_tp_flight_seat)) {
            view.findViewById<TextView>(id).setTextColor(hostInk)
        }
        view.findViewById<View>(BookingR.id.pf_tp_floating_origin).isEnabled = interactive && !state.editing
        view.findViewById<TextView>(R.id.tvPreparationStatus).apply {
            visibility = if (state.error != null || state.loading || state.busy) View.VISIBLE else View.GONE
            text = when {
                state.error != null -> getString(errorText(state.error))
                state.loading -> getString(R.string.flight_preparation_loading)
                state.busy -> getString(R.string.flight_preparation_working)
                state.confirmed -> getString(R.string.flight_preparation_confirmed)
                else -> getString(R.string.flight_preparation_save_hint)
            }
        }
        view.findViewById<TextView>(R.id.btnPreparationStart).apply {
            visibility = if (state.confirmed) View.VISIBLE else View.GONE
            isEnabled = interactive && state.confirmed && plan != null
            setText(R.string.flight_preparation_start)
            setTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun errorText(error: PreparationError): Int = when (error) {
        PreparationError.MISSING_AIRPORT -> R.string.flight_preparation_error_airport
        PreparationError.INVALID_ROUTE -> R.string.flight_preparation_error_route
        PreparationError.LOAD_FAILED -> R.string.flight_preparation_error_load
        PreparationError.SAVE_FAILED -> R.string.flight_preparation_error_save
        PreparationError.START_FAILED -> R.string.flight_preparation_error_start
        PreparationError.ACTIVE_SESSION -> R.string.flight_preparation_error_active
        PreparationError.NOT_CONFIRMED -> R.string.flight_preparation_error_confirmation
        PreparationError.SCENES_UNAVAILABLE -> R.string.flight_preparation_error_scenes
        PreparationError.INVALID_DEPARTURE -> R.string.flight_preparation_error_departure
        PreparationError.NOT_PREVIEWED -> R.string.flight_preparation_error_preview
        PreparationError.DEPARTURE_NOT_DUE -> R.string.flight_preparation_error_not_due
        PreparationError.PLAN_OPERATION_UNAVAILABLE -> R.string.flight_preparation_error_operation
    }


    private fun returnToFlight() {
        val nav = findNavController()
        if (returningHome || nav.currentDestination?.id != R.id.flightPreparationFragment) return
        try {
            if (!nav.popBackStack(R.id.homeFragment, false)) {
                nav.navigate(R.id.homeFragment, null, NavOptions.Builder()
                    .setPopUpTo(R.id.flightPreparationFragment, true)
                    .setLaunchSingleTop(true)
                    .build())
            }
            returningHome = true
        } catch (error: IllegalStateException) {
            Timber.w(error, "飞行已开始，返回主页导航失败")
            view?.findViewById<TextView>(R.id.tvPreparationStatus)?.apply {
                setText(R.string.flight_preparation_navigation_failed)
                visibility = View.VISIBLE
            }
        }
    }

    companion object {
        const val ARG_ORIGIN = FlightPreparationViewModel.ARG_ORIGIN
        const val ARG_DESTINATION = FlightPreparationViewModel.ARG_DESTINATION
        private const val STATE_SEAT_NAMESPACE = "preparation_seat_namespace"
        private const val STATE_PENDING_SEAT = "preparation_pending_seat"
        private const val GLOBAL_SEAT_PREFIX = "preparation-global:"
    }
}
