package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.SubscriptionStatusSnapshot
import com.projectfocus.core.network.subscription.SubscriptionStatusGateway
import com.projectfocus.core.network.subscription.SubscriptionStatusParseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanEntitlementAccessTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var prefs: SharedPreferences
    private val clock = Clock()
    @Before fun setup() {
        TokenStore.set("fixture-account-a")
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("entitlement_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    @After fun teardown() { TokenStore.clear() }

    @Test fun staleTrueIsReturnedBeforeARealRefreshAndExplicitNullClearsAndPersistsIt() = runTest {
        seed(qualified = true, age = 60_000)
        val response = CompletableDeferred<SubscriptionStatusSnapshot?>()
        val gateway = Gateway { response.await() }
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, gateway, backgroundScope)
        assertThat(access.admit()).isTrue()
        runCurrent()
        assertThat(gateway.calls).isEqualTo(1)
        assertThat(access.state.value.qualified).isTrue()
        response.complete(null); runCurrent()
        assertThat(access.state.value.qualified).isFalse()
        assertThat(access.state.value.checkedAtMillis).isEqualTo(clock.millis())
        assertThat(SharedPreferencesPlanEntitlementAccess(prefs, clock, gateway, backgroundScope).admit()).isFalse()
        assertThat(gateway.calls).isEqualTo(1)
    }

    @Test fun freshBooleanUsesTheExactSixtySecondWallClockBoundaryAndDoesNotAwaitNetwork() = runTest {
        seed(true, 59_999)
        val response = CompletableDeferred<SubscriptionStatusSnapshot?>()
        val gateway = Gateway { response.await() }
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, gateway, backgroundScope)
        assertThat(access.admit()).isTrue(); runCurrent()
        assertThat(gateway.calls).isEqualTo(0)
        clock.now = clock.now.plusNanos(1_000_000)
        assertThat(access.admit()).isTrue(); runCurrent()
        assertThat(gateway.calls).isEqualTo(1)
        response.complete(QUALIFIED); runCurrent()
    }

    @Test fun callbackQualificationUsesAllThreeFieldsAndDoesNotParseExpiresAtLocally() = runTest {
        for (reply in listOf(QUALIFIED.copy(isActive = false), QUALIFIED.copy(isPremium = false),
            QUALIFIED.copy(remainingDays = 0), QUALIFIED.copy(remainingDays = -1), QUALIFIED.copy(expiresAt = "not locally parsed"))) {
            seed(false, 60_000)
            val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, Gateway { reply }, backgroundScope)
            assertThat(access.admit()).isFalse(); runCurrent()
            assertThat(access.state.value.qualified).isEqualTo(reply.isActive && reply.isPremium && reply.remainingDays > 0)
        }
    }

    @Test fun networkFailureKeepsEligibilityExpiryAndLastCheckRatherThanDeliveringFalse() = runTest {
        seed(true, 60_000)
        val before = prefs.all.toMap()
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, Gateway { throw IOException("offline") }, backgroundScope)
        assertThat(access.admit()).isTrue(); runCurrent()
        assertThat(access.state.value.qualified).isTrue()
        assertThat(access.state.value.refreshFailure).isEqualTo(EntitlementRefreshFailure.NETWORK)
        assertThat(prefs.all).isEqualTo(before)
    }

    @Test fun parseFailurePreservesTheOldBooleanAndHasADifferentFailureKind() = runTest {
        seed(true, 60_000)
        val before = prefs.all.toMap()
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock,
            Gateway { throw SubscriptionStatusParseException(IOException("malformed")) }, backgroundScope)
        assertThat(access.admit()).isTrue(); runCurrent()
        assertThat(access.state.value.qualified).isTrue()
        assertThat(access.state.value.refreshFailure).isEqualTo(EntitlementRefreshFailure.PARSE)
        assertThat(prefs.all).isEqualTo(before)
    }

    @Test fun anAnonymousClientCannotUseAnotherAccountsCachedTrueOrTriggerAnAuthenticatedRequest() = runTest {
        seed(true, 0)
        TokenStore.clear()
        val gateway = Gateway { QUALIFIED }
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, gateway, backgroundScope)
        assertThat(access.admit()).isFalse(); runCurrent()
        assertThat(access.state.value.authenticated).isFalse()
        assertThat(gateway.calls).isEqualTo(0)
    }

    @Test fun repeatedAdmissionsCoalesceOnlyTheOwnAdapterRequestAndStillReturnOldFalse() = runTest {
        val response = CompletableDeferred<SubscriptionStatusSnapshot?>()
        val gateway = Gateway { response.await() }
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock, gateway, backgroundScope)
        repeat(3) { assertThat(access.admit()).isFalse() }; runCurrent()
        assertThat(gateway.calls).isEqualTo(1)
        response.complete(QUALIFIED); runCurrent()
        assertThat(access.admit()).isTrue()
    }

    @Test fun lateResponseFromAnOldAccountCannotGrantTheNextAccountOrOverwriteItsCache() = runTest {
        val response = CompletableDeferred<SubscriptionStatusSnapshot?>()
        var requests = 0
        val access = SharedPreferencesPlanEntitlementAccess(prefs, clock,
            Gateway {
                requests++
                if (requests == 1) withContext(NonCancellable) { response.await() } else awaitCancellation()
            }, backgroundScope)
        access.admit(); runCurrent()
        TokenStore.set("fixture-account-b")
        assertThat(access.admit()).isFalse(); runCurrent()
        response.complete(QUALIFIED); runCurrent()
        assertThat(access.state.value.ownerScope).isEqualTo(FlightPlanOwnership.scope())
        assertThat(access.state.value.qualified).isFalse()
        assertThat(prefs.contains("${FlightPlanOwnership.scope()}:qualified")).isFalse()
        val oldOwner = java.security.MessageDigest.getInstance("SHA-256").digest("fixture-account-a".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertThat(prefs.contains("$oldOwner:qualified")).isFalse()
    }

    @Test fun failedDiskCommitRollsBackTheMemoryBackedPreferencesAndDoesNotAdmitANewTrue() = runTest {
        seed(false, 60_000)
        val before = prefs.all.toMap()
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun commit(): Boolean { real.commit(); return false }
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { real.putBoolean(key, value); return this }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { real.putLong(key, value); return this }
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                }
            }
        }
        val access = SharedPreferencesPlanEntitlementAccess(failing, clock, Gateway { QUALIFIED }, backgroundScope)
        assertThat(access.admit()).isFalse(); runCurrent()
        assertThat(access.state.value.qualified).isFalse()
        assertThat(access.state.value.refreshFailure).isEqualTo(EntitlementRefreshFailure.STORAGE)
        assertThat(prefs.all).isEqualTo(before)
    }

    private fun seed(qualified: Boolean, age: Long) {
        val key = FlightPlanOwnership.scope()
        prefs.edit().putBoolean("$key:qualified", qualified).putLong("$key:checked_at", clock.millis()-age)
            .putString("$key:expires_at", "old-expiry").commit()
    }
    private class Gateway(val action: suspend () -> SubscriptionStatusSnapshot?) : SubscriptionStatusGateway {
        var calls = 0
        override suspend fun fetch(): SubscriptionStatusSnapshot? { calls++; return action() }
    }
    private class Clock : PreparationClock {
        var now = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
        override fun now() = now
        fun millis() = now.toInstant().toEpochMilli()
    }
    private companion object {
        val QUALIFIED = SubscriptionStatusSnapshot(isActive = true, isPremium = true, remainingDays = 1, expiresAt = "2026-11-01T04:34:00Z")
    }
}
