package com.projectfocus.service.focus.freeflight.runtime

import android.app.Notification
import android.content.Context

/** One source keepalive notification, retained across the preparation/flight phase handoff. */
object FreeFlightRuntimeNotification {
    const val ID = 2002
    const val CHANNEL_ID = FreeFlightKeepAliveNotification.CHANNEL_ID
    fun ensureChannel(context: Context) {
        FreeFlightKeepAliveNotification.ensureChannel(context)
    }
    fun build(context: Context, target: FreeFlightRuntimeTarget): Notification {
        require(target.archive.sessionId == target.key.sessionId)
        return FreeFlightKeepAliveNotification.build(context)
    }
}
