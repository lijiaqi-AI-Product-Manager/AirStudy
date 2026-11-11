package com.projectfocus.app.countdown

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.app.freeflight.AndroidFreeFlightPreparationPrompts
import com.projectfocus.app.freeflight.FreeFlightPermissionDecision
import com.projectfocus.app.freeflight.FreeFlightNightPolicy
import com.projectfocus.app.freeflight.FreeFlightReadySurface
import com.projectfocus.app.freeflight.FreeFlightSeatChoice
import com.projectfocus.app.freeflight.FreeFlightSeatSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Activity-only surfaces; the retained coordinator owns the frozen request. */
class OrdinaryFlightPreparationController(
    private val activity: MainActivity,
    private val coordinator: OrdinaryFlightPreparationCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val root = FrameLayout(activity).apply { id = R.id.ordinaryPreparation; isClickable = true; isFocusable = true }
    private var seats: FreeFlightSeatSurface? = null
    private var boarding: OrdinaryBoardingPassSurface? = null
    private var ready: FreeFlightReadySurface? = null
    private var previous: OrdinaryPreparationStep? = null
    private var home: java.io.Closeable? = null
    private var detached = false
    private var lastReminder = Long.MIN_VALUE
    private var lastError: OrdinaryPreparationError? = null
    private var night = FreeFlightNightPolicy.isNight(activity)
    private var notificationPrompts: AndroidFreeFlightPreparationPrompts? = null
    private var preparingBoarding = false
    private var blockingEnabled = false
    private var blockedAppCount = 0
    val isActive: Boolean get() = !detached && coordinator.state.value.active
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (coordinator.state.value.step == OrdinaryPreparationStep.STARTING) return
            val now = SystemClock.uptimeMillis()
            if (lastReminder != Long.MIN_VALUE && now - lastReminder < 2_000L) coordinator.cancel()
            else {
                lastReminder = now
                Toast.makeText(activity, activity.getString(R.string.pf_ff_toast_exit_reminder_title) + "\n" +
                    activity.getString(R.string.pf_ff_toast_exit_reminder_msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        scope.launch { coordinator.state.collect(::render) }
        scope.launch {
            combine(activity.settingsRepository.observe(PrefKey.BlacklistEnabled),
                activity.settingsRepository.observe(PrefKey.SelectedCategoryId),
                activity.blockedApps.observeAll()) { enabled, category, rows ->
                enabled to rows.filter { it.categoryId == category && it.type == "APP" }.map { it.packageName }.distinct().size
            }.collect { (enabled, count) ->
                blockingEnabled = enabled; blockedAppCount = count
                boarding?.updateBlocking(enabled, count)
            }
        }
    }

    private fun render(state: OrdinaryPreparationState) {
        if (detached) return
        val changed = previous != state.step
        previous = state.step
        back.isEnabled = state.active
        if (state.active && home == null) {
            home = activity.beginFreeFlightHomePresentation()
            if (home == null) { coordinator.cancel(); return }
        }
        val request = state.requestId
        when (state.step) {
            OrdinaryPreparationStep.LOADING -> if (changed) {
                clear(); attach()
                root.addView(ProgressBar(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            OrdinaryPreparationStep.SEAT -> if (changed || state.error != null) {
                clear(); attach()
                if (request != null) {
                    seats = FreeFlightSeatSurface(activity, activity, night,
                        state.selection?.let { FreeFlightSeatChoice(it.seat, it.scene) },
                        { coordinator.chooseSeat(request, OrdinarySeatChoice(it.seat, it.scene)) },
                        { coordinator.confirmSeat(request, OrdinarySeatChoice(it.seat, it.scene)) },
                        Bundle().apply { putBoolean("ordinaryFlightPreparation", true) }).also {
                        root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        it.enter()
                    }
                }
            }
            OrdinaryPreparationStep.BOARDING, OrdinaryPreparationStep.INSPECTION, OrdinaryPreparationStep.PREPARE -> {
                if (boarding == null && request != null) {
                    clear(); attach()
                    boarding = OrdinaryBoardingPassSurface(activity, state, night,
                        { coordinator.checkIn(request) }, { coordinator.inspectionCompleted(request) },
                        { prepareBoarding(request) }).also {
                        root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        it.updateBlocking(blockingEnabled, blockedAppCount)
                    }
                }
                if (state.step == OrdinaryPreparationStep.INSPECTION) boarding?.inspect(night)
                if (state.step == OrdinaryPreparationStep.PREPARE) boarding?.showPreparation()
            }
            OrdinaryPreparationStep.READY -> if (changed && request != null) {
                clear(); attach()
                ready = FreeFlightReadySurface(activity, night).also { surface ->
                    surface.id = R.id.ordinaryReady
                    root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    surface.depart.setOnClickListener { coordinator.depart(request) }
                    surface.enter()
                }
            }
            OrdinaryPreparationStep.STARTING -> {
                if (ready == null) {
                    clear(); attach()
                    ready = FreeFlightReadySurface(activity, night).also { surface ->
                        surface.id = R.id.ordinaryReady
                        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                }
                ready?.depart?.isEnabled = false
                if (changed) ready?.let { surface ->
                    surface.animate().alpha(0f).setDuration(500L).withEndAction {
                        if (!detached && ready === surface && coordinator.state.value.step == OrdinaryPreparationStep.STARTED) remove()
                    }.start()
                }
            }
            OrdinaryPreparationStep.STARTED -> if (ready == null || requireNotNull(ready).alpha <= .001f) remove()
            OrdinaryPreparationStep.IDLE, OrdinaryPreparationStep.CLOSED,
            OrdinaryPreparationStep.UNAVAILABLE -> remove()
        }
        val error = state.error
        if (error != null && lastError != error) Toast.makeText(activity, when (error) {
            OrdinaryPreparationError.ROUTE -> R.string.ordinary_route_unavailable
            OrdinaryPreparationError.SELECTION -> R.string.ordinary_scene_unavailable
            OrdinaryPreparationError.CHANGED -> R.string.ordinary_request_changed
            OrdinaryPreparationError.START -> R.string.ordinary_start_failed
        }, Toast.LENGTH_LONG).show()
        lastError = error
    }

    fun refreshNight() {
        val next = FreeFlightNightPolicy.isNight(activity)
        if (next == night || detached) return
        night = next
        when (coordinator.state.value.step) {
            OrdinaryPreparationStep.SEAT -> { previous = null; render(coordinator.state.value) }
            OrdinaryPreparationStep.READY, OrdinaryPreparationStep.STARTING -> ready?.applyNight(night)
            OrdinaryPreparationStep.BOARDING, OrdinaryPreparationStep.INSPECTION, OrdinaryPreparationStep.PREPARE -> {
                clear(); render(coordinator.state.value)
            }
            else -> Unit
        }
    }

    private fun prepareBoarding(request: String) {
        if (preparingBoarding || detached || coordinator.state.value.requestId != request ||
            coordinator.state.value.step != OrdinaryPreparationStep.PREPARE) return
        preparingBoarding = true
        boarding?.prepare?.isEnabled = false
        scope.launch {
            try {
                val prompts = notificationPrompts ?: AndroidFreeFlightPreparationPrompts(activity).also { notificationPrompts = it }
                val decision = prompts.notification {
                    !detached && coordinator.state.value.requestId == request && coordinator.state.value.step == OrdinaryPreparationStep.PREPARE
                }
                when (decision) {
                    FreeFlightPermissionDecision.Ready -> coordinator.prepareBoarding(request)
                    is FreeFlightPermissionDecision.Unavailable -> Toast.makeText(activity, decision.message, Toast.LENGTH_LONG).show()
                    is FreeFlightPermissionDecision.NeedsAction -> Unit
                }
            } finally {
                preparingBoarding = false
                if (!detached && coordinator.state.value.requestId == request && coordinator.state.value.step == OrdinaryPreparationStep.PREPARE)
                    boarding?.prepare?.isEnabled = true
            }
        }
    }

    private fun attach() {
        if (root.parent == null) activity.findViewById<ViewGroup>(android.R.id.content)
            .addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun clear() {
        seats?.release(); boarding?.release(); ready?.animate()?.cancel()
        seats = null; boarding = null; ready = null
        root.removeAllViews()
    }

    private fun remove() {
        notificationPrompts?.close(); notificationPrompts = null
        clear(); (root.parent as? ViewGroup)?.removeView(root)
        home?.close(); home = null
    }

    fun detach() {
        if (detached) return
        detached = true
        back.remove(); remove(); scope.cancel()
    }
}
