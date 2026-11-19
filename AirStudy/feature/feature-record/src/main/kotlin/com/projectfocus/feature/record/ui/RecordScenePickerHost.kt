package com.projectfocus.feature.record.ui

import android.view.View
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.feature.record.list.RecordItem

/** The screen host supplies the same scene catalog and editor used by flight preparation. */
interface RecordScenePickerHost {
    fun showRecordScenePicker(anchor: View, item: RecordItem, canShow: () -> Boolean,
        onSelected: (PlannedScene) -> Unit): AutoCloseable?
}
