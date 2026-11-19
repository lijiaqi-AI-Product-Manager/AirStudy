package com.projectfocus.core.ui.nav

/** Mode1 owns an independent preparation flow; false means its real dependencies are unavailable. */
interface FreeFlightPreparationHost {
    fun openFreeFlightPreparation(): Boolean
}
