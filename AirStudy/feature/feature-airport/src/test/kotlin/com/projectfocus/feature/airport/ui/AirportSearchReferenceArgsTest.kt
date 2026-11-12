package com.projectfocus.feature.airport.ui

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirportSearchReferenceArgsTest {
    @Test
    fun `explicit zero coordinates round trip but absent or partial doubles are no reference`() {
        val origin = checkNotNull(AirportReferencePoint.from(0.0, 0.0))
        val args = Bundle().apply { AirportSearchReferenceArgs.writeTo(this, origin) }
        assertThat(AirportSearchReferenceArgs.readFrom(args)).isEqualTo(origin)
        assertThat(AirportSearchReferenceArgs.readFrom(null)).isNull()
        assertThat(AirportSearchReferenceArgs.readFrom(Bundle())).isNull()
        args.remove(AirportSearchReferenceArgs.LONGITUDE)
        assertThat(AirportSearchReferenceArgs.readFrom(args)).isNull()
        val onlyLongitude = Bundle().apply { putDouble(AirportSearchReferenceArgs.LONGITUDE, 0.0) }
        assertThat(AirportSearchReferenceArgs.readFrom(onlyLongitude)).isNull()
    }

    @Test
    fun `invalid doubles never become a fabricated zero reference`() {
        for ((latitude, longitude) in listOf(
            Double.NaN to 0.0, 0.0 to Double.NaN, Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY, 91.0 to 0.0, 0.0 to 181.0,
        )) {
            val args = Bundle().apply {
                putDouble(AirportSearchReferenceArgs.LATITUDE, latitude)
                putDouble(AirportSearchReferenceArgs.LONGITUDE, longitude)
            }
            assertThat(AirportSearchReferenceArgs.readFrom(args)).isNull()
        }
        val wrongType = Bundle().apply {
            putInt(AirportSearchReferenceArgs.LATITUDE, 0)
            putDouble(AirportSearchReferenceArgs.LONGITUDE, 0.0)
        }
        assertThat(AirportSearchReferenceArgs.readFrom(wrongType)).isNull()
    }

    @Test
    fun `clearing reference removes both prior values`() {
        val args = Bundle().apply {
            AirportSearchReferenceArgs.writeTo(this, AirportReferencePoint.from(1.0, 2.0))
        }
        AirportSearchReferenceArgs.writeTo(args, null)
        assertThat(args.containsKey(AirportSearchReferenceArgs.LATITUDE)).isFalse()
        assertThat(args.containsKey(AirportSearchReferenceArgs.LONGITUDE)).isFalse()
        assertThat(AirportSearchReferenceArgs.readFrom(args)).isNull()
    }
}
