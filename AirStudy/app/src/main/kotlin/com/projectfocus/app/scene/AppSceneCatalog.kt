package com.projectfocus.app.scene

import android.content.Context
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.feature.scene.source.SourceSceneScope
import timber.log.Timber


object AppSceneCatalog {
    val SCOPE = SourceSceneScope("local")

    fun enabledSeatChoices(context: Context): SeatSceneChoices = try {
        val snapshot = SharedPreferencesSceneCatalogStore.from(context).readEnabled(SCOPE)
        SeatSceneChoices.Ready(snapshot.options.map { option ->
            val scene = SourceSceneCatalog.present(context, option)
            SeatSceneChoice(
                optionId = scene.optionId,
                iconKey = scene.iconKey,
                title = scene.title,
                type = scene.type,
                iconRes = scene.iconRes,
                color = scene.color,
            )
        })
    } catch (error: Exception) {
        Timber.w(error, "场景目录暂不可用")
        SeatSceneChoices.Unavailable
    }
}
