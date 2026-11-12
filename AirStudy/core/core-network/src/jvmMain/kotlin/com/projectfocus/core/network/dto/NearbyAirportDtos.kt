package com.projectfocus.core.network.dto

import kotlinx.serialization.Serializable


@Serializable
data class AirportLocationRequest(val latitude: Double, val longitude: Double, val radius: Int)

@Serializable
data class NearbyAirportDto(
    val id: Long,
    val code: String,
    val name: String,
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
)
