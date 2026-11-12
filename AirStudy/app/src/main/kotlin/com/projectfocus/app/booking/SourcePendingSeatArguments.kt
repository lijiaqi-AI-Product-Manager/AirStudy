package com.projectfocus.app.booking

import android.os.Bundle

/** Source E0 arguments with own draft identity; never imports inherited/current seat. */
internal object SourcePendingSeatArguments {
    fun from(request: PendingPlanSeatSelection, draftKey: String): Bundle? {
        val wire = request.candidate.snapshot
        val input = SourcePendingFlightInputs.from(wire) ?: return null
        return Bundle().apply {
            putString("draftKey", draftKey)
            putString("recordId", "")
            putString("from", input.departureDisplay)
            putString("to", input.arrivalDisplay)
            putString("flightNo", input.flightNumber())
            val sceneTitle = wire.focusSceneText.trim { it <= '\u0020' }
            if (sceneTitle.isNotEmpty()) {
                putString("planSceneTitle", sceneTitle)
                putInt("planSceneType", wire.focusIconCode)
            }
        }
    }
}
