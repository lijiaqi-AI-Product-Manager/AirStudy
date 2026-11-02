package com.projectfocus.app.freeflight

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import com.projectfocus.app.R
import com.projectfocus.core.model.flight.freeflight.CompletedFreeFlight
import com.projectfocus.feature.completion.arrival.FlightCompletionBinder
import com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FreeFlightCompletionController(
    private val parent:ViewGroup,
    private val coordinator:FreeFlightCompletionCoordinator,
    private val owners:SharedFlightRuntimeOwnership,
    private val scope:CoroutineScope,
    private val visibilityChanged:(Boolean)->Unit,
    private val onShare:(CompletedFreeFlight)->Unit,
    private val onAirport:(CompletedFreeFlight,Boolean)->Unit,
):Closeable {
    private var current:FreeFlightCompletionNotice?=null
    private var overlay:View?=null
    private var binder:FlightCompletionBinder?=null
    private var finishing:Job?=null
    private var closed=false
    private val show=Runnable {
        val expected=current
        if (!closed && expected!=null) scope.launch {
            paint(expected) {
                overlay?.let { actual ->
                    actual.visibility=View.VISIBLE
                    actual.alpha=0f
                    actual.animate().alpha(1f).setDuration(600L).setStartDelay(0L)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()
                }
            }
        }
    }
    private val job=scope.launch {
        coordinator.notice.collectLatest { notice ->
            if (closed) return@collectLatest
            if (notice==null) { current=null;clear();return@collectLatest }
            current=notice
            paint(notice) {
                clear()
                current=notice
                val view=LayoutInflater.from(parent.context).inflate(com.projectfocus.feature.completion.R.layout.fragment_flight_completion,parent,false)
                view.isClickable=true;view.isFocusable=true
                parent.addView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                overlay=view
                binder=FlightCompletionBinder(view).apply {
                    bindFreeFlight(notice.result)
                    onComplete={finishCurrent()}
                    onShare={ dispatch { this@FreeFlightCompletionController.onShare(it.result) } }
                    onAirport={ dispatch { this@FreeFlightCompletionController.onAirport(it.result,false) } }
                    onReport={ dispatch { this@FreeFlightCompletionController.onAirport(it.result,true) } }
                }
                visibilityChanged(true)
                parent.postDelayed(show,350L)
            }
        }
    }

    fun onBack():Boolean {
        if (current==null) return false
        finishCurrent()
        return true
    }

    private fun dispatch(action:(FreeFlightCompletionNotice)->Unit) {
        val expected=current ?: return
        scope.launch { paint(expected) { action(expected) } }
    }

    private fun finishCurrent() {
        val expected=current ?: return
        if (finishing?.isActive==true) return
        finishing=scope.launch {
            try {
                if (!expected.originSaved) {
                    coordinator.retryOrigin()
                    paint(expected) { Toast.makeText(parent.context,R.string.pf_ff_completion_pending,Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                paint(expected) { binder?.setCompleteEnabled(false) }
                if (!coordinator.finish(expected)) paint(expected) {
                    binder?.setCompleteEnabled(true)
                    Toast.makeText(parent.context,R.string.pf_ff_completion_pending,Toast.LENGTH_SHORT).show()
                }
            } catch (error:CancellationException) { throw error }
            catch (_:Exception) { paint(expected) {
                binder?.setCompleteEnabled(true)
                Toast.makeText(parent.context,R.string.pf_ff_completion_pending,Toast.LENGTH_SHORT).show()
            } }
        }
    }

    private suspend fun paint(expected:FreeFlightCompletionNotice,action:()->Unit):Boolean {
        while (!closed && current===expected) {
            when (owners.tryWithUiOwner(expected.result.sessionId,expected.owner,true) {
                if (!closed && current===expected) action()
                true
            }) {
                is FreeFlightPaintAttempt.Applied -> return true
                FreeFlightPaintAttempt.Obsolete -> { if (current===expected) { current=null;clear() };return false }
                FreeFlightPaintAttempt.Busy -> delay(16L)
            }
        }
        return false
    }

    private fun clear() {
        parent.removeCallbacks(show)
        overlay?.let { it.animate().cancel();parent.removeView(it) }
        overlay=null;binder=null
        visibilityChanged(false)
    }

    override fun close() {
        if (closed) return
        closed=true;current=null;job.cancel();finishing?.cancel();clear()
    }
}
