package com.projectfocus.app.admission.freeflight

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candidate only; exercises the actual provider with a controlled Android settings surface. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidFreeFlightDeviceIdentityTest {
    @Test fun sameActualDeviceKeepsItsCredentialAndAChangedDeviceCannotEarnAnotherSilentGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val before = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ANDROID_ID, "fixture-device-a")
            val prefs = context.getSharedPreferences("identity-test-" + UUID.randomUUID(), Context.MODE_PRIVATE)
            val first = AndroidFreeFlightDeviceIdentity(context, prefs).capture()
            assertThat(first.hwid).hasLength(64)
            assertThat(first.credential).hasLength(43)
            assertThat(AndroidFreeFlightDeviceIdentity(context, prefs).capture()).isEqualTo(first)
            Settings.Secure.putString(resolver, Settings.Secure.ANDROID_ID, "fixture-device-b")
            var failure: Throwable? = null
            try { AndroidFreeFlightDeviceIdentity(context, prefs).capture() } catch (error: IOException) { failure = error }
            assertThat(failure).isNotNull()
            assertThat(prefs.getString("identity", "")).contains(first.hwid)
        } finally { Settings.Secure.putString(resolver, Settings.Secure.ANDROID_ID, before) }
    }

    @Test fun missingActualAndroidIdCannotBeReplacedByAFixedOrRandomPretendDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val prefs = context.getSharedPreferences("identity-missing-" + UUID.randomUUID(), Context.MODE_PRIVATE)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, null)
            var failure: Throwable? = null
            try { AndroidFreeFlightDeviceIdentity(context, prefs).capture() } catch (error: IOException) { failure = error }
            assertThat(failure).isNotNull()
            assertThat(prefs.contains("identity")).isFalse()
        } finally { Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, before) }
    }
}
