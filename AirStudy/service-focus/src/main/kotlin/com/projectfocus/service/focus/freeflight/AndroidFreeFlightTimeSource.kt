package com.projectfocus.service.focus.freeflight

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.projectfocus.core.model.flight.freeflight.FreeFlightClockReading
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

fun interface FreeFlightTimeSource { fun read(): FreeFlightClockReading }

/** Uses both real clocks. A process epoch fallback never grants offline focus. */
class AndroidFreeFlightTimeSource @Inject constructor(
    @ApplicationContext context: Context,
) : FreeFlightTimeSource {
    private val epoch = try {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        "boot:" + boot
    } catch (_: Exception) {
        "process:" + UUID.randomUUID().toString()
    }
    override fun read(): FreeFlightClockReading {
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        // These reads are adjacent; uptime can tick between reads on a freshly booted device.
        return FreeFlightClockReading(maxOf(elapsed, uptime), uptime, System.currentTimeMillis(), epoch)
    }
}
