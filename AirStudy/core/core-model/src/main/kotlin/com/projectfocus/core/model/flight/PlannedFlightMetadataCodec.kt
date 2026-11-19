package com.projectfocus.core.model.flight

import java.time.DateTimeException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * This product's durable flight metadata adapter, independent of category IDs.
 *
 * Entities retain the original nullable JSON bytes. Decoding is only a typed
 * projection for a running route; checkpoint/terminal updates copy the stored
 * strings so unknown future fields and malformed metadata are not rewritten.
 */
object PlannedFlightMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeScene(value: PlannedScene?): String? =
        value?.let { json.encodeToString(PlannedScene.serializer(), it) }

    fun decodeSceneOrNull(value: String?): PlannedScene? =
        decodeOrNull(value, PlannedScene.serializer())

    fun encodeDeparture(value: PlannedDeparture?): String? =
        value?.let { json.encodeToString(PlannedDeparture.serializer(), it) }

    fun decodeDepartureOrNull(value: String?): PlannedDeparture? =
        decodeOrNull(value, PlannedDeparture.serializer())

    private fun <T> decodeOrNull(value: String?, serializer: KSerializer<T>): T? {
        if (value == null) return null
        return try {
            json.decodeFromString(serializer, value)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }
}
