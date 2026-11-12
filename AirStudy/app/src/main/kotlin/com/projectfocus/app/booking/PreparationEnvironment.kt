package com.projectfocus.app.booking

import android.content.Context
import com.projectfocus.app.scene.AppSceneCatalog
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

fun interface PreparationSceneSource {
    fun enabledScenes(): List<PlannedScene>
    fun studyTitle(): String = "学习"
}

fun interface PreparationClock {
    fun now(): ZonedDateTime

    /** R() uses elapsed time, independently of calendar and timezone changes. */
    fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
}

/** Device calendar is the product adapter until the original date/zone supplier is closed. */
@Singleton
class DevicePreparationClock @Inject constructor() : PreparationClock {
    override fun now(): ZonedDateTime = ZonedDateTime.now()
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

@Singleton
class LocalPreparationSceneSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreparationSceneSource {
    override fun studyTitle(): String = context.getString(com.projectfocus.feature.scene.R.string.pf_source_scene_label_study)
    override fun enabledScenes(): List<PlannedScene> =
        SharedPreferencesSceneCatalogStore.from(context).readEnabled(AppSceneCatalog.SCOPE).options.map { option ->
            val record = option.record
            PlannedScene(option.optionId, record.name, record.iconKey, record.color,
                record.isCustom, record.cloudId, record.clientUuid, SourceSceneCatalog.label(context, record))
        }
}
