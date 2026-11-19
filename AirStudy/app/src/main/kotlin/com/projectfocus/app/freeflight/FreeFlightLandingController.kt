package com.projectfocus.app.freeflight

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.projectfocus.app.R
import com.projectfocus.core.model.flight.freeflight.FreeFlightPhase
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Own host of source k2/a.f/e4 landing selection; never starts another flight or clock. */
class FreeFlightLandingController(
    private val context: Context,
    private val root: ViewGroup,
    private val repository: FreeFlightSessionRepository,
    private val owners: SharedFlightRuntimeOwnership,
    private val candidates: FreeFlightLandingCandidates,
    private val feedbackFactory: AndroidFreeFlightRulerFeedbackFactory,
    parentScope: CoroutineScope,
    private val collapseWindow: () -> Unit,
    private val changed: () -> Unit,
) : Closeable {
    private data class Key(val sessionId: String, val owner: FreeFlightOwnerToken, val generation: Long)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val ranges = Channel<Pair<Key, Int>>(Channel.CONFLATED)
    private var frame: FreeFlightHomeFrame? = null
    private var key: Key? = null
    private var surfaceKey: Pair<String, FreeFlightOwnerToken>? = null
    private var surface: FreeFlightLandingSurface? = null
    private var surfaceJob: Job? = null
    private var queryJob: Job? = null
    private var feedback: AndroidFreeFlightRulerFeedback? = null
    private var options: List<FreeFlightLandingOption> = emptyList()
    private var queryFailed = false
    private var queryLoading = false
    private var busy = false
    private var pendingRange: Int? = null
    private var dialog: AlertDialog? = null
    private var closed = false

    init {
        scope.launch {
            for ((captured, minutes) in ranges) {
                while (busy && !closed) delay(16L)
                val actual = repository.state.value
                if (!closed && frame?.owner == captured.owner && actual?.sessionId == captured.sessionId &&
                    actual.phase == FreeFlightPhase.LANDING_SELECTION && owners.retainedOwner(captured.sessionId) == captured.owner) {
                    command(captured.owner) { repository.changeLandingRange(captured.owner, minutes) }
                    if (pendingRange == minutes) pendingRange = null
                    render()
                }
            }
        }
    }

    fun bind(value: FreeFlightHomeFrame?) {
        if (closed) return
        frame = value
        val archive = value?.archive
        if (archive == null || archive.status != FreeFlightStatus.ONGOING || archive.phase != FreeFlightPhase.LANDING_SELECTION) {
            key = null; queryJob?.cancel(); queryJob = null; hideSurface()
            if (archive == null) { dialog?.dismiss(); dialog = null }
            return
        }
        val next = Key(archive.sessionId, value.owner, archive.landingSelectionGeneration)
        val identity = archive.sessionId to value.owner
        if (identity != surfaceKey) {
            hideSurface(); surfaceKey = identity
            surfaceJob = scope.launch {
                val actual = feedbackFactory.bind(value.owner.ownerScope, scope)
                if (closed || surfaceKey != identity || frame?.archive?.phase != FreeFlightPhase.LANDING_SELECTION) { actual.close(); return@launch }
                feedback = actual
                val prefs = context.getSharedPreferences(AndroidFreeFlightRulerFeedbackFactory.FILE, Context.MODE_PRIVATE)
                val widthKey = "airport_card_width_style_" + value.owner.ownerScope
                val width = prefs.getString(widthKey, prefs.getString("airport_card_width_style", "vertical"))
                    .takeIf { it == "horizontal" || it == "vertical" } ?: "vertical"
                val ui = FreeFlightLandingSurface(context, actual, FreeFlightNightPolicy.isNight(context), width, "badge",
                    onRange = { minutes -> key?.let { pendingRange = minutes; ranges.trySend(it to minutes) } },
                    onSelect = ::select, onConfirm = ::confirm, onRetry = ::retry)
                ui.id = R.id.pf_ff_land_surface
                root.addView(ui, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                surface = ui
                if (queryLoading) ui.loading() else if (queryFailed) ui.failed() else ui.showOptions(options)
                render()
            }
        }
        if (next != key) {
            key = next; options = emptyList(); queryFailed = false; queryLoading = true
            surface?.loading(); query(next)
        }
        render()
    }

    fun landOrReturn() {
        val actual = frame ?: return
        if (closed || busy || owners.retainedOwner(actual.archive.sessionId) != actual.owner) return
        when (actual.archive.phase) {
            FreeFlightPhase.LANDING_SELECTION -> scope.launch { command(actual.owner) { repository.returnToCruise(actual.owner) } }
            FreeFlightPhase.CRUISING -> {
                if (actual.archive.clock.pause != null || dialog != null) return
                dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.pf_ff_land_free_flight_landing_confirm_title)
                    .setMessage(R.string.pf_ff_land_free_flight_landing_confirm_message)
                    .setPositiveButton(R.string.pf_ff_land_common_confirm) { _, _ ->
                        scope.launch {
                            if (frame?.archive?.sessionId != actual.archive.sessionId || frame?.owner != actual.owner) return@launch
                            collapseWindow()
                            command(actual.owner) { repository.prepareLanding(actual.owner) }
                        }
                    }.setNegativeButton(R.string.pf_ff_land_common_cancel, null).create().also {
                        it.setOnDismissListener { dialog = null }; it.show()
                    }
            }
            else -> Unit
        }
    }

    private fun query(captured: Key) {
        queryJob?.cancel()
        queryJob = scope.launch {
            delay(300L)
            try {
                if (!current(captured)) return@launch
                val search = repository.landingSearch(captured.owner)
                if (search.sessionId != captured.sessionId || search.generation != captured.generation || !current(captured)) return@launch
                val departure = requireNotNull(repository.state.value).departure.code
                val values = candidates.query(search, departure)
                if (!current(captured)) return@launch
                options = values; queryLoading = false; queryFailed = false
                surface?.showOptions(values); render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(captured)) { options = emptyList(); queryLoading = false; queryFailed = true; surface?.failed(); render() }
            }
        }
    }

    private fun retry() {
        val captured = key ?: return
        if (busy || queryLoading || !current(captured)) return
        options = emptyList(); queryLoading = true; queryFailed = false; surface?.loading(); query(captured)
    }
    private fun select(option: FreeFlightLandingOption) {
        val captured = key ?: return
        if (busy || !current(captured) || options.none { it == option }) return
        scope.launch { command(captured.owner) { repository.selectLanding(captured.owner, captured.sessionId, captured.generation, option.airport) } }
    }
    private fun confirm() {
        val captured = key ?: return
        val airport = repository.state.value?.landingAirport ?: return
        if (busy || !current(captured) || options.none { it.airport == airport }) return
        scope.launch { command(captured.owner) { repository.confirmLanding(captured.owner, captured.generation) } }
    }
    private fun current(captured: Key): Boolean {
        val archive = repository.state.value ?: return false
        return !closed && key == captured && frame?.owner == captured.owner && owners.retainedOwner(captured.sessionId) == captured.owner &&
            archive.ownerScope == captured.owner.ownerScope && archive.sessionId == captured.sessionId && archive.status == FreeFlightStatus.ONGOING &&
            archive.phase == FreeFlightPhase.LANDING_SELECTION && archive.landingSelectionGeneration == captured.generation
    }
    private suspend fun command(owner: FreeFlightOwnerToken, action: suspend () -> FreeFlightCommandResult) {
        if (busy || closed) return
        busy = true; render()
        try {
            when (val result = action()) {
                is FreeFlightCommandResult.Committed -> {
                    if (!closed && frame?.owner == owner) bind(FreeFlightHomeFrame(result.archive, owner))
                    if (!result.runtimeSynchronized) Toast.makeText(context, R.string.pf_ff_runtime_waiting, Toast.LENGTH_LONG).show()
                }
                else -> Toast.makeText(context, R.string.flight_hud_action_failed, Toast.LENGTH_SHORT).show()
            }
        } finally { busy = false; render(); if (!closed) changed() }
    }
    private fun render() {
        val archive = frame?.archive ?: return
        if (archive.phase == FreeFlightPhase.LANDING_SELECTION)
            surface?.render(pendingRange ?: requireNotNull(archive.landingRangeMinutes), archive.landingAirport?.code, busy && pendingRange == null)
    }
    private fun hideSurface() {
        surfaceJob?.cancel(); surfaceJob = null; surfaceKey = null
        surface?.let { (it.parent as? ViewGroup)?.removeView(it) }; surface = null
        feedback?.close(); feedback = null; options = emptyList(); queryFailed = false; queryLoading = false
        pendingRange = null
    }
    override fun close() {
        if (closed) return
        closed = true; dialog?.dismiss(); dialog = null; key = null
        queryJob?.cancel(); hideSurface(); ranges.close(); scope.cancel()
    }
}
