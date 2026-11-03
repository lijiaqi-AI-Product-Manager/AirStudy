package com.projectfocus.feature.stats

import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.service.focus.history.RecordedFlight

data class StatsSceneEntry(
    val key: String,
    val canonicalKey: String,
    val iconKey: String,
    val focusMinutes: Double,
    val completedFlights: Int,
)

object StatsScenes {
    private val aliases = mapOf(
        "Programming" to "programming", "编程" to "programming",
        "Reading" to "reading", "阅读" to "reading",
        "Creative" to "creative", "创作" to "creative",
        "Meditation" to "meditation", "冥想" to "meditation", "Sleep" to "meditation", "睡眠" to "meditation",
        "Study" to "study", "学习" to "study", "Work" to "work", "工作" to "work",
        "Relax" to "relax", "放松" to "relax", "Gaming" to "gaming", "游戏" to "gaming",
        "Exercise" to "exercise", "运动" to "exercise", "Star Chasing" to "starchasing", "追星" to "starchasing",
        "Music" to "music", "音乐" to "music",
    )

    fun canonical(value: String?): String? {
        if(value==null || value.isEmpty())return null
        val trimmed=value.trim { it<=' ' }
        return aliases[trimmed] ?: trimmed
    }

    fun identity(record: RecordedFlight): StatsSceneEntry? {
        val legacy=(record as? RecordedFlight.Countdown)?.record
        val archive=(record as? RecordedFlight.CountUp)?.archive
        val snapshot=record.editedScene?.snapshot ?: legacy?.let { PlannedFlightMetadataCodec.decodeSceneOrNull(it.sceneSnapshotJson) }
        val name=(snapshot?.name ?: archive?.scene?.title).orEmpty().trim { it<=' ' }
        val type=snapshot?.let { SourceSceneCatalog.type(it.iconKey) } ?: archive?.scene?.type ?: 0
        var category=SourceSceneCatalog.builtins.firstOrNull { it.type==type }?.iconKey ?: name
        val key=name.ifEmpty { category }
        if(key.isEmpty())return null
        val normalized=canonical(key).orEmpty().ifEmpty { category }
        if(normalized!=key)category=normalized
        return StatsSceneEntry(key,normalized,category,0.0,0)
    }

    fun matches(record: RecordedFlight, selectedKey: String): Boolean = selectedKey.isEmpty() ||
        identity(record)?.canonicalKey==canonical(selectedKey)

    fun group(records: List<RecordedFlight>, range: StatsDateRange? = null): List<StatsSceneEntry> {
        val rows=linkedMapOf<String,StatsSceneEntry>()
        for(record in records) {
            if(!record.completed || (range!=null && record.recordedAtWallMs !in range))continue
            val scene=identity(record) ?: continue
            val minutes=when(record) {
                is RecordedFlight.Countdown -> record.record.actualFocusMinutes.coerceAtLeast(0).toDouble()
                is RecordedFlight.CountUp -> record.archive.clock.activeElapsedMs / 60_000.0
            }
            val previous=rows[scene.canonicalKey]
            val total=(previous?.focusMinutes ?: 0.0)+minutes
            require(total.isFinite() && total>=0.0)
            rows[scene.canonicalKey]=(previous ?: scene).copy(focusMinutes=total,
                completedFlights=Math.addExact(previous?.completedFlights ?: 0,1))
        }
        return rows.values.sortedByDescending { it.focusMinutes }
    }

    fun percentage(entry: StatsSceneEntry, rows: List<StatsSceneEntry>): Double {
        val total=rows.sumOf { it.focusMinutes }
        return if(total>0.0)entry.focusMinutes / total * 100.0 else 0.0
    }
}
