package com.projectfocus.feature.scene.source

import android.content.Context
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
    fun sceneProjection(@ApplicationContext context: Context): RecordSceneWireProjection = RecordSceneWireProjection { scene ->
        val builtin = SourceSceneCatalog.builtins.firstOrNull { it.iconKey == scene.iconKey }
        val title = scene.displayName?.takeIf(String::isNotBlank) ?: scene.name.takeIf(String::isNotBlank)
            ?: builtin?.let { context.getString(it.labelRes) } ?: scene.optionId
        FlightRecordScenePatch(SourceSceneCatalog.type(scene.iconKey).takeIf { it > 0 } ?: 1, title)
    }
}
