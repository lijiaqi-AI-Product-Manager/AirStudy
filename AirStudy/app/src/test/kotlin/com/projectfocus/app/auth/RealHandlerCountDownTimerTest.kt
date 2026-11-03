package com.projectfocus.app.auth

import android.app.Application
import android.os.CountDownTimer
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Android Handler delivers callbacks; the test never calls onTick/onFinish itself. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [RealHandlerCountDownTimerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RealHandlerCountDownTimerTest {
    @Test fun `real framework start advances with looper clock and cancel removes pending callbacks`() {
        val ticks = mutableListOf<Long>()
        var finished = false
        val timer = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) { ticks += millisUntilFinished }
            override fun onFinish() { finished = true }
        }
        assertThat(timer.start()).isSameInstanceAs(timer)
        assertThat(ticks).isEmpty()
        shadowOf(Looper.getMainLooper()).idleFor(1_500, TimeUnit.MILLISECONDS)
        assertThat(ticks).containsExactly(3_000L, 2_000L).inOrder()
        assertThat(finished).isFalse()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertThat(finished).isTrue()

        val canceledTicks = mutableListOf<Long>()
        var canceledFinished = false
        val canceled = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) { canceledTicks += millisUntilFinished }
            override fun onFinish() { canceledFinished = true }
        }
        canceled.start()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertThat(canceledTicks).containsExactly(3_000L)
        canceled.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertThat(canceledTicks).containsExactly(3_000L)
        assertThat(canceledFinished).isFalse()
    }
}
