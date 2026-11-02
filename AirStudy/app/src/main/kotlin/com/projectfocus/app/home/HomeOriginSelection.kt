package com.projectfocus.app.home

import android.content.SharedPreferences
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.UUID

/**
 * Own shared Home writer. The namespace is still global; this does not implement
 * original per-account migration. The monitor orders writes with TokenStore
 * publication, and a write marker excludes a newer manual selection on rollback.
 */
internal object HomeOriginSelection {
    const val CODE = "origin_code"
    const val CITY = "origin_city"
    const val MARKER = "origin_write_marker_v1"
    private const val STORAGE = "Could not persist arrival origin"

    fun selectManually(preferences: SharedPreferences, code: String) = synchronized(TokenStore) {
        preferences.edit().putString(CODE, code).putString(MARKER, UUID.randomUUID().toString()).apply()
    }

    fun advance(preferences: SharedPreferences, destination: PlannedAirport,
                expectedIdentity: TokenStore.Snapshot,
                identity: () -> TokenStore.Snapshot = TokenStore::snapshot) = synchronized(TokenStore) {
        check(identity() == expectedIdentity) { "Arrival account changed" }
        val beforeCode = preferences.getString(CODE, null)
        val beforeCity = preferences.getString(CITY, null)
        val beforeMarker = preferences.getString(MARKER, null)
        val marker = UUID.randomUUID().toString()
        val city = destination.displayCity
        try {
            if (!preferences.edit().putString(CODE, destination.code).putString(CITY, city)
                    .putString(MARKER, marker).commit()) throw IOException(STORAGE)
            // Same-thread test/listener callbacks can re-enter the synchronized
            // TokenStore monitor. Do not treat that generation change as success.
            check(identity() == expectedIdentity) { "Arrival account changed" }
        } catch (error: Exception) {
            try {
                if (preferences.getString(MARKER, null) == marker &&
                    preferences.getString(CODE, null) == destination.code &&
                    preferences.getString(CITY, null) == city) {
                    val rollback = preferences.edit()
                    if (beforeCode == null) rollback.remove(CODE) else rollback.putString(CODE, beforeCode)
                    if (beforeCity == null) rollback.remove(CITY) else rollback.putString(CITY, beforeCity)
                    if (beforeMarker == null) rollback.remove(MARKER) else rollback.putString(MARKER, beforeMarker)
                    if (!rollback.commit()) error.addSuppressed(IOException(STORAGE))
                }
            } catch (rollbackError: Exception) {
                if (rollbackError !== error) error.addSuppressed(rollbackError)
            }
            throw error
        }
    }
}
