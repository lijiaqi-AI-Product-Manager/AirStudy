package com.projectfocus.service.focus.freeflight.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real platform channels and production builder; not a notification-permission accepting port. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FreeFlightKeepAliveNotificationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = requireNotNull(app.getSystemService(NotificationManager::class.java))

    @Test fun missingSourceChannelIsCreatedLowWithoutBadgeAndUsesTheActualSourceLabels() {
        assertEquals(FreeFlightNotificationRequirement.READY, FreeFlightKeepAliveNotification.access(app).requirement)
        val notification = FreeFlightKeepAliveNotification.build(app)
        val channel = manager.getNotificationChannel(FreeFlightKeepAliveNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance);assertFalse(channel.canShowBadge())
        assertEquals(app.getString(com.projectfocus.service.focus.R.string.freeflight_source_channel), channel.name.toString())
        assertEquals(app.getString(com.projectfocus.service.focus.R.string.freeflight_source_notification), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.PRIORITY_LOW, notification.priority)
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertTrue(notification.actions.isNullOrEmpty())
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TITLE))
    }

    @Test fun blockedSourceChannelIsReportedAndNeverRecreatedAsEnabled() {
        manager.createNotificationChannel(NotificationChannel(FreeFlightKeepAliveNotification.CHANNEL_ID, "Actual block", NotificationManager.IMPORTANCE_NONE))
        val access = FreeFlightKeepAliveNotification.access(app)
        assertEquals(FreeFlightNotificationRequirement.CHANNEL, access.requirement)
        assertEquals(FreeFlightKeepAliveNotification.CHANNEL_ID, access.channelId)
        FreeFlightKeepAliveNotification.ensureChannel(app)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(requireNotNull(access.channelId)).importance)
    }

    @Test fun actualOldDebugChannelBlockCannotBeBypassedByCreatingTheNewSourceChannel() {
        manager.createNotificationChannel(NotificationChannel("projectfocus_freeflight_runtime", "Old actual block", NotificationManager.IMPORTANCE_NONE))
        val access = FreeFlightKeepAliveNotification.access(app)
        assertEquals(FreeFlightNotificationRequirement.CHANNEL, access.requirement)
        assertEquals("projectfocus_freeflight_runtime", access.channelId)
        assertThrows(IllegalStateException::class.java) { FreeFlightKeepAliveNotification.ensureChannel(app) }
        assertNull(manager.getNotificationChannel(FreeFlightKeepAliveNotification.CHANNEL_ID))
    }
}
