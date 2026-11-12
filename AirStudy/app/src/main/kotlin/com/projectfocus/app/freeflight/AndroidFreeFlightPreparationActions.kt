package com.projectfocus.app.freeflight

import android.widget.Toast
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.core.model.flight.freeflight.FreeFlightNavigation
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.feature.home.map.FreeFlightMapPose
import com.projectfocus.feature.home.map.FreeFlightMapVisual
import com.projectfocus.feature.home.map.MapAirport
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightPreparationDispatch
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
import javax.inject.Inject
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Creates Activity-owned paint/prompt bindings; the retainer owns preparation and formal starts. */
class AndroidFreeFlightPreparationActionsFactory @Inject constructor(
    private val retained: RetainedFreeFlightPreparations,
    private val maps: FreeFlightMapRuntime,
    private val feedback: AndroidFreeFlightRulerFeedbackFactory,
    private val runtime: FreeFlightRuntimeRegistry,
) {
    fun create(activity: MainActivity, restoringPreparationId: String?): FreeFlightPreparationActions? {
        val handle = if (restoringPreparationId == null) retained.begin() else retained.restore(restoringPreparationId, null) ?: return null
        return AndroidFreeFlightPreparationActions(activity, retained, handle, maps, feedback, runtime)
    }
}

internal class AndroidFreeFlightPreparationActions(
    private val activity: MainActivity,
    private val retained: RetainedFreeFlightPreparations,
    private val handle: FreeFlightPreparationHandle,
    private val maps: FreeFlightMapRuntime,
    private val feedbackFactory: AndroidFreeFlightRulerFeedbackFactory,
    runtime: FreeFlightRuntimeRegistry,
) : FreeFlightPreparationActions, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prompts = AndroidFreeFlightPreparationPrompts(activity)
    private var feedback: AndroidFreeFlightRulerFeedback? = null
    private var plane: FreeFlightMapVisual? = null
    private var prepared: FreeFlightPrepared? = null
    private var headingRevision = 0L
    private var hidden = false
    private var closed = false
    override val preparationId get() = handle.preparationId
    override val unavailableReason: String? get() = if (closed || activity.isFinishing || activity.isDestroyed) activity.getString(R.string.pf_ff_preparation_expired) else null
    override val rulerFeedback: FreeFlightRulerFeedback get() = checkNotNull(feedback) { "Actual feedback must load before the heading surface" }
    override val startState get() = retained.startState
    override val runtimeState = runtime.observation
    override val ownerInvalidations get() = retained.ownerInvalidations

    override suspend fun prepare(): FreeFlightPreparationDecision = bind(retained.prepare(handle))
    override suspend fun restore(preparationId: String, sessionId: String?): FreeFlightPreparationDecision {
        if (preparationId != handle.preparationId || retained.restore(preparationId, sessionId) !== handle) return unavailable()
        return bind(retained.prepare(handle))
    }

    private suspend fun bind(decision: FreeFlightPreparationDecision): FreeFlightPreparationDecision {
        if (decision !is FreeFlightPreparationDecision.Prepared) return decision
        val actual = decision.value
        if (closed || !retained.isCurrent(handle, actual)) return unavailable()
        prepared = actual
        feedback?.close(); feedback = feedbackFactory.bind(actual.owner.ownerScope, scope)
        if (retained.isSubmitted(handle)) return decision
        if (!maps.preparationMapReady(actual.sessionId, actual.owner)) return abandonUnavailable(R.string.pf_ff_map_not_ready)
        // Source once-per-device confirmation precedes Main.a0's plane and departure camera.
        if (!prompts.firstHint { isCurrent(actual) }) return abandonUnavailable(R.string.pf_ff_preparation_expired)
        val visual = maps.openPreparationVisual(actual.sessionId, actual.owner, pose(actual, 0))
            ?: return abandonUnavailable(R.string.pf_ff_map_not_ready)
        plane?.close(); plane = visual
        if (!maps.focusPreparationDeparture(actual.sessionId, actual.owner, visual,
                MapAirport(actual.departure.code, actual.departure.latitude, actual.departure.longitude)))
            return abandonUnavailable(R.string.pf_ff_map_not_ready)
        if (!isCurrent(actual) || !visual.isCurrent) return abandonUnavailable(R.string.pf_ff_preparation_expired)
        return decision
    }

    override suspend fun isCurrent(prepared: FreeFlightPrepared): Boolean =
        !closed && this.prepared === prepared && retained.isCurrent(handle, prepared)

    override suspend fun ensureKeepAlivePermissions(prepared: FreeFlightPrepared): FreeFlightPermissionDecision {
        val permission = prompts.notification { isCurrent(prepared) }
        if (permission != FreeFlightPermissionDecision.Ready) return permission
        return when (val dispatched = retained.startKeepAlive(handle, prepared)) {
            is FreeFlightPreparationDispatch.Dispatched -> FreeFlightPermissionDecision.Ready
            is FreeFlightPreparationDispatch.Rejected -> FreeFlightPermissionDecision.Unavailable(dispatched.reason)
            is FreeFlightPreparationDispatch.Failed -> FreeFlightPermissionDecision.Unavailable(activity.getString(R.string.pf_ff_runtime_waiting))
        }
    }

    override fun preparationShown(prepared: FreeFlightPrepared) {
        check(this.prepared === prepared && !closed)
        hidden = false
    }
    override fun headingChanged(prepared: FreeFlightPrepared, headingDegrees: Int) {
        if (this.prepared !== prepared || closed || hidden || retained.isSubmitted(handle)) return
        val revision = ++headingRevision
        val visual = plane ?: return
        scope.launch {
            if (revision == headingRevision && isCurrent(prepared))
                maps.paintPreparation(prepared.sessionId, prepared.owner, visual, pose(prepared, headingDegrees))
        }
    }
    override fun preparationHidden(prepared: FreeFlightPrepared?, submitted: Boolean) {
        hidden = true; ++headingRevision; plane?.close(); plane = null
        feedback?.close(); feedback = null
    }
    override suspend fun start(prepared: FreeFlightPrepared, request: FreeFlightStartRequest): FreeFlightCommandResult {
        if (!isCurrent(prepared)) return FreeFlightCommandResult.Rejected(activity.getString(R.string.pf_ff_preparation_expired))
        if (!retained.isSubmitted(handle)) {
            if (plane?.isCurrent != true) return FreeFlightCommandResult.Rejected(activity.getString(R.string.pf_ff_map_not_ready))
            if (com.projectfocus.service.focus.freeflight.runtime.FreeFlightKeepAliveNotification.access(activity).requirement !=
                com.projectfocus.service.focus.freeflight.runtime.FreeFlightNotificationRequirement.READY)
                return FreeFlightCommandResult.Rejected(activity.getString(R.string.pf_ff_need_notification_permission))
        }
        return retained.start(handle, prepared, request)
    }
    override suspend fun retryRuntime(prepared: FreeFlightPrepared) = retained.retryRuntime(handle, prepared)
    override fun dismissed(prepared: FreeFlightPrepared?, submitted: Boolean, reason: FreeFlightPreparationDismissal) {
        retained.cancel(handle) // The actual retained submitted flag prevents releasing a formal request.
    }
    override fun startOutcome(prepared: FreeFlightPrepared, result: FreeFlightCommandResult) {
        if (closed || this.prepared !== prepared) return
        when (result) {
            is FreeFlightCommandResult.Committed -> activity.reflectFreeFlightStart(prepared.sessionId)
            is FreeFlightCommandResult.Rejected -> {
                if (!retained.isSubmitted(handle)) retained.cancel(handle)
                Toast.makeText(activity, result.reason, Toast.LENGTH_LONG).show()
            }
            is FreeFlightCommandResult.Failed -> Toast.makeText(activity, R.string.pf_ff_runtime_waiting, Toast.LENGTH_LONG).show()
        }
    }
    private fun pose(prepared: FreeFlightPrepared, heading: Int): FreeFlightMapPose {
        val value = FreeFlightNavigation.normalize(FreeFlightPoint(prepared.departure.latitude, prepared.departure.longitude), heading.toDouble())
        return FreeFlightMapPose(value.point.latitude, value.point.longitude, value.bearingDegrees.toFloat())
    }
    private fun abandonUnavailable(message: Int): FreeFlightPreparationDecision {
        retained.cancel(handle); plane?.close(); plane = null; feedback?.close(); feedback = null
        return FreeFlightPreparationDecision.Unavailable(activity.getString(message))
    }
    private fun unavailable() = FreeFlightPreparationDecision.Unavailable(activity.getString(R.string.pf_ff_preparation_expired))
    override fun close() {
        if (closed) return
        closed = true; hidden = true; ++headingRevision; prompts.close()
        plane?.close(); plane = null; feedback?.close(); feedback = null; scope.cancel()
    }
}
