package com.projectfocus.service.focus.freeflight

enum class FreeFlightMapAvailability { PRESENT, ABSENT, UNKNOWN }

/** Actual shared/native surface state, supplied by Root. Config/SDK distance are not proof of presence. */
fun interface FreeFlightMapPresence {
    fun current(sessionId: String, owner: FreeFlightOwnerToken): FreeFlightMapAvailability
}
