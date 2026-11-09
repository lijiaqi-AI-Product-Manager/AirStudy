package com.projectfocus.core.network.record

import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.dto.FlightRecordScenePatch

fun interface RecordSceneWireProjection {
    fun project(scene: PlannedScene): FlightRecordScenePatch

    fun restore(patch: FlightRecordScenePatch): PlannedScene? = null
}
