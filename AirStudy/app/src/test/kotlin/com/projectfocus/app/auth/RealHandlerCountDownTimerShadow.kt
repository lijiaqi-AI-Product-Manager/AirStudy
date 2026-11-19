package com.projectfocus.app.auth

import android.os.CountDownTimer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/**
 * Test-local4.14.1 workaround: its bundled timer shadow does not schedule Handler
 * messages. Keep real framework construction/Handler/clock/callbacks, bypassing
 * only that shadow's start/cancel no-ops. No synthetic ticks or UI mutation.
 */
@Implements(value = CountDownTimer::class, callThroughByDefault = true)
class RealHandlerCountDownTimerShadow {
    @RealObject private lateinit var timer: CountDownTimer

    @Implementation
    protected fun start(): CountDownTimer =
        Shadow.directlyOn<CountDownTimer, CountDownTimer>(timer, CountDownTimer::class.java, "start")

    @Implementation
    protected fun cancel() {
        Shadow.directlyOn<Any?, CountDownTimer>(timer, CountDownTimer::class.java, "cancel")
    }
}
