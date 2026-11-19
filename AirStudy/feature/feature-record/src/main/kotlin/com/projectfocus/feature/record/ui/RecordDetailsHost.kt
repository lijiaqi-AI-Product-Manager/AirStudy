package com.projectfocus.feature.record.ui

import com.projectfocus.feature.record.detail.RecordReplaySource
import com.projectfocus.feature.record.detail.ReplayIntent

interface RecordDetailsHost {
    fun openRecordDetails(source: RecordReplaySource)
}

interface RecordReplayHost {
    suspend fun prepareRecordReplay(intent: ReplayIntent): Boolean
}
