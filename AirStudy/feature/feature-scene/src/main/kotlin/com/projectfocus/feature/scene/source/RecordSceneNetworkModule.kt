package com.projectfocus.feature.scene.source

import android.content.Context
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.dto.FlightRecordScenePatch
import com.projectfocus.core.network.record.RecordSceneWireProjection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordSceneNetworkModule {
    @Provides
    @Singleton
    fun sceneProjection(@ApplicationContext context: Context): RecordSceneWireProjection = object : RecordSceneWireProjection {
        override fun project(scene: PlannedScene): FlightRecordScenePatch {
            val builtin = SourceSceneCatalog.builtins.firstOrNull { it.iconKey == scene.iconKey }
            val title = scene.displayName?.takeIf(String::isNotBlank) ?: scene.name.takeIf(String::isNotBlank)
                ?: builtin?.let { context.getString(it.labelRes) } ?: scene.optionId
            return FlightRecordScenePatch(SourceSceneCatalog.type(scene.iconKey).takeIf { it > 0 } ?: 1, title)
        }

        override fun restore(patch: FlightRecordScenePatch): PlannedScene? {
            val value = patch.normalized()
            val builtin = SourceSceneCatalog.builtins.firstOrNull { it.type == value.focusIconType } ?: return null
            return PlannedScene("history-cloud-${builtin.type}", value.focusScenario, builtin.iconKey,
                builtin.color, true, displayName = value.focusScenario)
        }
    }
}
