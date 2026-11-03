package com.projectfocus.app.home

import android.content.SharedPreferences
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Own shared Home writer. The namespace is still global; this does not implement
 * original per-account migration. The monitor orders writes with TokenStore
 * publication, and a write marker excludes a newer manual selection on rollback.
 */
internal object HomeOriginSelection {
    const val CODE = "origin_code"
    const val CITY = "origin_city"
    const val MARKER = "origin_write_marker_v1"
    const val SNAPSHOT = "origin_airport_snapshot_v1"
    const val SNAPSHOT_SCOPE = "origin_airport_snapshot_scope_v1"
    private const val STORAGE = "Could not persist arrival origin"
    private val json = Json { ignoreUnknownKeys = true }

    fun selectManually(preferences: SharedPreferences, code: String) = synchronized(TokenStore) {
        preferences.edit().putString(CODE, code).putString(MARKER, UUID.randomUUID().toString())
            .remove(SNAPSHOT).remove(SNAPSHOT_SCOPE).apply()
    }

    fun selectedSnapshot(preferences: SharedPreferences, code: String,
                         account: TokenStore.Snapshot = TokenStore.snapshot()): PlannedAirport? = synchronized(TokenStore) {
        if (account != TokenStore.snapshot() || preferences.getString(CODE,null) != code ||
            preferences.getString(SNAPSHOT_SCOPE,null) != TokenStore.storageScope(account)) return@synchronized null
        preferences.getString(SNAPSHOT,null)?.let { raw ->
            runCatching { json.decodeFromString(PlannedAirport.serializer(),raw) }.getOrNull()?.takeIf { it.code == code }
        }
    }

    fun advance(preferences: SharedPreferences, destination: PlannedAirport,
                expectedIdentity: TokenStore.Snapshot,
                identity: () -> TokenStore.Snapshot = TokenStore::snapshot) = synchronized(TokenStore) {
        check(identity() == expectedIdentity) { "Arrival account changed" }
        val beforeCode = preferences.getString(CODE, null)
        val beforeCity = preferences.getString(CITY, null)
        val beforeMarker = preferences.getString(MARKER, null)
        val beforeSnapshot = preferences.getString(SNAPSHOT,null)
        val beforeScope = preferences.getString(SNAPSHOT_SCOPE,null)
        val marker = UUID.randomUUID().toString()
        val city = destination.displayCity
        val snapshot = json.encodeToString(PlannedAirport.serializer(),destination)
        val scope = TokenStore.storageScope(expectedIdentity)
        try {
            if (!preferences.edit().putString(CODE, destination.code).putString(CITY, city)
                    .putString(MARKER, marker).putString(SNAPSHOT,snapshot).putString(SNAPSHOT_SCOPE,scope)
                    .commit()) throw IOException(STORAGE)
            // Same-thread test/listener callbacks can re-enter the synchronized
            // TokenStore monitor. Do not treat that generation change as success.
            check(identity() == expectedIdentity) { "Arrival account changed" }
        } catch (error: Exception) {
            try {
                if (preferences.getString(MARKER, null) == marker &&
                    preferences.getString(CODE, null) == destination.code &&
                    preferences.getString(CITY, null) == city && preferences.getString(SNAPSHOT,null) == snapshot &&
                    preferences.getString(SNAPSHOT_SCOPE,null) == scope) {
                    val rollback = preferences.edit()
                    if (beforeCode == null) rollback.remove(CODE) else rollback.putString(CODE, beforeCode)
                    if (beforeCity == null) rollback.remove(CITY) else rollback.putString(CITY, beforeCity)
                    if (beforeMarker == null) rollback.remove(MARKER) else rollback.putString(MARKER, beforeMarker)
                    if (beforeSnapshot == null) rollback.remove(SNAPSHOT) else rollback.putString(SNAPSHOT,beforeSnapshot)
                    if (beforeScope == null) rollback.remove(SNAPSHOT_SCOPE) else rollback.putString(SNAPSHOT_SCOPE,beforeScope)
                    if (!rollback.commit()) error.addSuppressed(IOException(STORAGE))
                }
            } catch (rollbackError: Exception) {
                if (rollbackError !== error) error.addSuppressed(rollbackError)
            }
            throw error
        }
    }
}
