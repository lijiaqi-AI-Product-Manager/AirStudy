package com.projectfocus.app.freeflight

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Real Main content overlay. All stage transitions come from the coordinator/required real actions. */
class FreeFlightPreparationController(
    private val activity: MainActivity,
    private val actions: FreeFlightPreparationActions,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val coordinator = FreeFlightPreparationCoordinator(actions, scope)
    private val root = FrameLayout(activity).apply { id = R.id.pf_ff_root;isClickable = true;isFocusable = true }
    private var heading: FreeFlightHeadingSurface? = null
    private var seats: FreeFlightSeatSurface? = null
    private var ready: FreeFlightReadySurface? = null
    private var previous = FreeFlightPreparationStep.IDLE
    private var generation = 0L
    private var night = FreeFlightNightPolicy.isNight(activity)
    private var lastBackReminder = Long.MIN_VALUE
    private var lastMessage: Pair<FreeFlightPreparationStep, String?>? = null
    private var hiddenNotified = false
    private var destroyed = false
    private var homePresentation: java.io.Closeable? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val state = coordinator.state.value
            if (state.step in setOf(FreeFlightPreparationStep.HEADING, FreeFlightPreparationStep.PERMISSION, FreeFlightPreparationStep.CHECKING)) {
                coordinator.dismiss(FreeFlightPreparationDismissal.HEADING_CANCEL)
                return
            }
            val now = SystemClock.uptimeMillis()
            if (lastBackReminder != Long.MIN_VALUE && now - lastBackReminder < 2000L)
                coordinator.dismiss(FreeFlightPreparationDismissal.BACK_EXIT)
            else {
                lastBackReminder = now
                Toast.makeText(activity, activity.getString(R.string.pf_ff_toast_exit_reminder_title) + "\n" +
                    activity.getString(R.string.pf_ff_toast_exit_reminder_msg), Toast.LENGTH_SHORT).show()
            }
        }
    }
    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        scope.launch { coordinator.state.collect(::render) }
    }

    val isActive: Boolean get() = !destroyed && coordinator.state.value.step in setOf(
        FreeFlightPreparationStep.CHECKING, FreeFlightPreparationStep.HEADING, FreeFlightPreparationStep.PERMISSION,
        FreeFlightPreparationStep.SEAT, FreeFlightPreparationStep.READY, FreeFlightPreparationStep.STARTING,
        FreeFlightPreparationStep.PENDING, FreeFlightPreparationStep.UNCERTAIN,
        FreeFlightPreparationStep.RUNTIME_WAITING,
    )

    fun begin() = coordinator.begin()
    fun restore(bundle: Bundle) {
        val id = bundle.getString(KEY_PREPARATION) ?: return
        @Suppress("DEPRECATION") val scene = bundle.getSerializable(KEY_SCENE) as? SeatSceneChoice
        val seat = bundle.getString(KEY_SEAT)
        val step = runCatching { FreeFlightPreparationStep.valueOf(bundle.getString(KEY_STEP).orEmpty()) }.getOrDefault(FreeFlightPreparationStep.HEADING)
        coordinator.restore(FreeFlightPreparationSaved(id, bundle.getString(KEY_SESSION), bundle.getFloat(KEY_HEADING),
            if (scene != null && seat != null) FreeFlightSeatChoice(seat, scene) else null, step, bundle.getBoolean(KEY_SUBMITTED)))
    }

    fun saved(): Bundle? = coordinator.saved()?.let { value ->
        Bundle().apply {
            putString(KEY_PREPARATION, value.preparationId);putString(KEY_SESSION, value.sessionId);putFloat(KEY_HEADING, value.heading)
            putString(KEY_SEAT, value.selection?.seat);putSerializable(KEY_SCENE, value.selection?.scene);putString(KEY_STEP, value.step.name);putBoolean(KEY_SUBMITTED, value.submitted)
        }
    }

    fun refreshNight() {
        val current = FreeFlightNightPolicy.isNight(activity)
        if (current == night || destroyed) return
        night = current
        if (previous == FreeFlightPreparationStep.READY) ready?.applyNight(night)
        else if (previous == FreeFlightPreparationStep.SEAT) { seats?.release();root.removeAllViews();seats = null;showSeat(coordinator.state.value, animate = false) }
    }

    private fun render(state: FreeFlightPreparationUiState) {
        if (destroyed) return
        back.isEnabled = isActive
        val changed = previous != state.step;previous = state.step
        if (state.prepared != null && isActive && homePresentation == null) {
            homePresentation = activity.beginFreeFlightHomePresentation()
            if (homePresentation == null) {
                coordinator.dismiss(FreeFlightPreparationDismissal.HEADING_CANCEL)
                return
            }
        }
        when (state.step) {
            FreeFlightPreparationStep.HEADING, FreeFlightPreparationStep.PERMISSION -> {
                if (heading == null) showHeading(state)
                heading?.next?.isEnabled = state.step == FreeFlightPreparationStep.HEADING
                if (state.message != null) message(state.step, state.message)
            }
            FreeFlightPreparationStep.SEAT -> if (changed && seats == null) {
                val ticket = ++generation
                val show = { if (!destroyed && generation == ticket && coordinator.state.value.step == FreeFlightPreparationStep.SEAT) { clearViews();showSeat(coordinator.state.value, animate = true) } }
                heading?.leave(show) ?: show()
            }
            FreeFlightPreparationStep.READY -> if (changed) {
                val choice = state.selection
                val catalog = activity.seatSceneChoices(Bundle()) as? SeatSceneChoices.Ready
                if (choice == null || catalog?.values?.any { it.optionId == choice.scene.optionId } != true) {
                    coordinator.unavailableRestoredScene();return
                }
                clearViews();attach();ready = FreeFlightReadySurface(activity, night).also { surface ->
                    root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    surface.depart.setOnClickListener { coordinator.submit() };surface.enter()
                }
            }
            FreeFlightPreparationStep.STARTING -> if (changed) {
                ready?.depart?.isEnabled = false
                val surface = ready
                val ticket = ++generation
                if (surface != null) surface.animate().alpha(0f).setDuration(500L).withEndAction {
                    if (!destroyed && generation == ticket && coordinator.state.value.step in setOf(
                            FreeFlightPreparationStep.STARTING, FreeFlightPreparationStep.PENDING, FreeFlightPreparationStep.UNCERTAIN,
                            FreeFlightPreparationStep.RUNTIME_WAITING))
                        removeSurface(notify = false)
                }.start()
            }
            FreeFlightPreparationStep.PENDING, FreeFlightPreparationStep.UNCERTAIN -> {
                message(state.step, activity.getString(R.string.pf_ff_toast_flight_usage_consume_pending_msg))
                if (ready == null) removeSurface(notify = false)
            }
            FreeFlightPreparationStep.RUNTIME_WAITING -> {
                message(state.step, activity.getString(R.string.pf_ff_runtime_waiting))
                if (changed) {
                    ++generation; clearViews(); attach()
                    val density = activity.resources.displayMetrics.density
                    root.addView(com.google.android.material.button.MaterialButton(activity).apply {
                        id = R.id.pf_ff_retry_runtime; setText(R.string.pf_ff_retry_runtime_action)
                        setOnClickListener { coordinator.retryRuntime() }
                    }, FrameLayout.LayoutParams((300 * density).toInt(), (56 * density).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        .apply { bottomMargin = (40 * density).toInt() })
                }
            }
            FreeFlightPreparationStep.CLOSED -> if (changed) {
                ++generation
                heading?.leave { removeSurface(notify = true) } ?: removeSurface(notify = true)
            }
            FreeFlightPreparationStep.STARTED -> removeSurface(notify = true)
            FreeFlightPreparationStep.REJECTED, FreeFlightPreparationStep.UNAVAILABLE -> {
                if (changed) removeSurface(notify = true)
                state.message?.takeIf(String::isNotBlank)?.let { message(state.step, it) }
            }
            FreeFlightPreparationStep.IDLE, FreeFlightPreparationStep.CHECKING -> Unit
        }
    }

    private fun showHeading(state: FreeFlightPreparationUiState) {
        clearViews();attach();heading = FreeFlightHeadingSurface(activity, state.heading, actions.rulerFeedback).also { surface ->
            root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            surface.ruler.onHeadingChanged = coordinator::headingChanged
            surface.cancel.setOnClickListener { coordinator.dismiss(FreeFlightPreparationDismissal.HEADING_CANCEL) }
            surface.next.setOnClickListener { coordinator.continueToSeat() };surface.enter()
        }
    }

    private fun showSeat(state: FreeFlightPreparationUiState, animate: Boolean) {
        attach();seats = FreeFlightSeatSurface(activity, activity, night, state.selection, coordinator::chooseSeat, coordinator::confirmSeat).also {
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (animate) it.enter()
        }
    }

    private fun attach() {
        if (root.parent == null) activity.findViewById<ViewGroup>(android.R.id.content)
            .addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun clearViews() {
        heading?.ruler?.onHeadingChanged = null;heading?.animate()?.cancel();ready?.animate()?.cancel();seats?.release()
        root.removeAllViews();heading = null;seats = null;ready = null
    }

    private fun removeSurface(notify: Boolean) {
        ++generation
        clearViews();(root.parent as? ViewGroup)?.removeView(root)
        if (notify && !hiddenNotified) { hiddenNotified = true;val current = coordinator.state.value;actions.preparationHidden(current.prepared, current.submitted) }
        if (notify) { homePresentation?.close();homePresentation = null }
    }

    private fun message(step: FreeFlightPreparationStep, value: String) {
        if (lastMessage == (step to value)) return
        lastMessage = step to value;Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    fun detach() {
        if (destroyed) return
        destroyed = true;++generation;coordinator.detach();back.remove();removeSurface(notify = false)
        homePresentation?.close();homePresentation = null;scope.cancel()
        (actions as? java.io.Closeable)?.close()
    }

    companion object {
        const val KEY_PREPARATION = "freeFlightPreparationId"
        private const val KEY_SESSION = "sessionId"
        private const val KEY_HEADING = "heading"
        private const val KEY_SEAT = "seat"
        private const val KEY_SCENE = "scene"
        private const val KEY_STEP = "step"
        private const val KEY_SUBMITTED = "submitted"
    }
}
