package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.AirportLocationRequest
import com.projectfocus.core.network.dto.NearbyAirportDto
import java.io.Reader
import java.security.MessageDigest
import kotlin.math.*

/** Complete owned CC0 catalogue. No origin, premium, popularity or 9k-only filtering. */
class AirportLocationCatalog private constructor(private val airports: List<NearbyAirportDto>) {
    val size: Int get() = airports.size

    fun search(request: AirportLocationRequest, limit: Int, offset: Int): List<NearbyAirportDto> {
        require(request.latitude.isFinite() && request.latitude in -90.0..90.0)
        require(request.longitude.isFinite() && request.longitude in -180.0..180.0)
        require(request.radius in 1..3200 && limit in 1..50 && offset >= 0)

        // distance then stable source ID; the client retains it and applies the source HMS filter.
        return airports.asSequence().map { it to distanceKm(request.latitude, request.longitude, it.latitude, it.longitude) }
            .filter { it.second <= request.radius }.sortedWith(compareBy<Pair<NearbyAirportDto, Double>> { it.second }.thenBy { it.first.id })
            .drop(offset).take(limit).map { it.first }.toList()
    }

    companion object {
        private val bundledCatalogue: AirportLocationCatalog by lazy { loadBundled() }
        fun bundled(): AirportLocationCatalog = bundledCatalogue
        private fun loadBundled(): AirportLocationCatalog {
            val raw = requireNotNull(AirportLocationCatalog::class.java.getResourceAsStream("/airports/location-catalog-release.csv")) {
                "Complete airport catalogue is missing"
            }.use { it.readBytes() }
            check(raw.isNotEmpty())
            check(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) } ==
                "d78ba7773b2d07da114415b4dc76366e95c305f97a419ad3b73407e240adb99d") { "Airport catalogue differs from the reviewed full snapshot" }
            return parse(raw.toString(Charsets.UTF_8).reader()).also { check(it.size == 83798) }
        }

        internal fun parse(reader: Reader): AirportLocationCatalog {
            val rows = csv(reader)
            check(rows.first() == listOf("id", "code", "name", "city", "country", "latitude", "longitude", "type"))
            val values = rows.drop(1).map { fields ->
                check(fields.size == 8)
                NearbyAirportDto(fields[0].toLong(), fields[1], fields[2], fields[3], fields[4], fields[5].toDouble(), fields[6].toDouble()).also {
                    check(it.code.isNotBlank() && it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0)
                }
            }
            check(values.map { it.id }.toSet().size == values.size)
            return AirportLocationCatalog(values)
        }

        private fun csv(reader: Reader): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val fields = mutableListOf<String>(); val field = StringBuilder()
            val input = reader.readText(); var quoted = false; var i = 0
            while (i < input.length) {
                val c = input[i++]
                when {
                    c == '"' && quoted && i < input.length && input[i] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields += field.toString(); field.setLength(0) }
                    c == '\n' && !quoted -> { fields += field.toString(); field.setLength(0); rows += fields.toList(); fields.clear() }
                    c == '\r' && !quoted -> Unit
                    else -> field.append(c)
                }
            }
            check(!quoted)
            if (field.isNotEmpty() || fields.isNotEmpty()) { fields += field.toString(); rows += fields.toList() }
            return rows
        }

        private fun distanceKm(a: Double, b: Double, c: Double, d: Double): Double {
            val p1 = Math.toRadians(a); val p2 = Math.toRadians(c)
            val sinLat = sin((p2 - p1) / 2); val sinLon = sin(Math.toRadians(d - b) / 2)
            val h = (sinLat * sinLat + cos(p1) * cos(p2) * sinLon * sinLon).coerceIn(0.0, 1.0)
            return 6371.009 * 2 * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
