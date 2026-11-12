package com.projectfocus.widget.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.projectfocus.widget.R
import com.projectfocus.widget.WidgetEntryActivity
import com.projectfocus.widget.snapshot.TodayStats
import timber.log.Timber






































object TodayWidgetKeys {
    val SESSION_COUNT = intPreferencesKey("today_session_count")
    val FOCUS_MINUTES = intPreferencesKey("today_focus_minutes")
    val DISTANCE_KM = intPreferencesKey("today_distance_km")
    val STREAK_DAYS = intPreferencesKey("today_streak_days")
    val FLEW_TODAY = booleanPreferencesKey("today_flew_today")








    val SNAPSHOT_DAY = longPreferencesKey("today_snapshot_day")
}

/** 写入今日快照（供 Service / Repository 调用） */
object TodayWidgetUpdater {

    /**
     * 推送今日统计给所有 4x2 小组件实例。
     *
     * @param epochDay 这份快照所属的本地日期（`LocalDate.toEpochDay()`）
     */
    suspend fun push(context: Context, stats: TodayStats, epochDay: Long) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(TodayWidget::class.java)
            if (ids.isEmpty()) return   // 用户没放这个小组件，省掉一次全量写
            val widget = TodayWidget()
            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    fromStats(prefs, stats, epochDay)
                }
                widget.update(context, id)
            }
        }.onFailure { Timber.w(it, "更新今日小组件失败") }
    }

    /** 纯函数：把 stats 写进 Preferences。可单测（不需要 Glance 运行时） */
    fun fromStats(prefs: MutablePreferences, stats: TodayStats, epochDay: Long) {
        prefs[TodayWidgetKeys.SESSION_COUNT] = stats.sessionCount
        prefs[TodayWidgetKeys.FOCUS_MINUTES] = stats.focusMinutes
        prefs[TodayWidgetKeys.DISTANCE_KM] = stats.distanceKm
        prefs[TodayWidgetKeys.STREAK_DAYS] = stats.streakDays
        prefs[TodayWidgetKeys.FLEW_TODAY] = stats.flewToday
        prefs[TodayWidgetKeys.SNAPSHOT_DAY] = epochDay
    }












    fun toStats(prefs: Preferences, todayEpochDay: Long): TodayStats {
        val snapshotDay = prefs[TodayWidgetKeys.SNAPSHOT_DAY] ?: 0L
        if (snapshotDay == 0L || snapshotDay != todayEpochDay) return TodayStats.EMPTY
        return TodayStats(
            sessionCount = prefs[TodayWidgetKeys.SESSION_COUNT] ?: 0,
            focusMinutes = prefs[TodayWidgetKeys.FOCUS_MINUTES] ?: 0,
            distanceKm = prefs[TodayWidgetKeys.DISTANCE_KM] ?: 0,
            streakDays = prefs[TodayWidgetKeys.STREAK_DAYS] ?: 0,
            flewToday = prefs[TodayWidgetKeys.FLEW_TODAY] ?: false,
        )
    }
}

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()

                //   跨时区飞行（用户落地后时区变了）会出现"快照日期 != 今天"→
                //   显示空态。这是可接受的：他确实换了日界，数据归属本身就变了。
                val today = java.time.LocalDate.now().toEpochDay()
                val stats = TodayWidgetUpdater.toStats(prefs, today)
                Content(stats)
            }
        }
    }

    @Composable
    private fun Content(stats: TodayStats) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.BgBase)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<WidgetEntryActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // ── 第一行：标题 + 连续天数 ─────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = str(R.string.widget_today_title),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = ColorProvider(WidgetPalette.TextSecondary), fontSize = 12.sp),
                    maxLines = 1,
                )
                Text(
                    text = stats.title,
                    style = TextStyle(
                        color = ColorProvider(
                            if (stats.streakDays > 0) WidgetPalette.AccentPrimary else WidgetPalette.TextTertiary,
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // ── 第二行：大字分钟数 + 单位 ───────────────────────
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    text = stats.bigNumber,
                    style = TextStyle(
                        color = ColorProvider(WidgetPalette.TextPrimary),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = stats.bigNumberUnit,

                    //   齐平时小字看起来像掉下去了。Glance 没有 baseline 对齐，
                    //   用 padding 近似，视觉上够用。
                    modifier = GlanceModifier.padding(bottom = 6.dp),
                    style = TextStyle(color = ColorProvider(WidgetPalette.TextTertiary), fontSize = 12.sp),
                    maxLines = 1,
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // ── 第三行：副标题（明细 / 提醒）─────────────────────
            Text(
                text = stats.subtitle,
                style = TextStyle(color = ColorProvider(WidgetPalette.TextTertiary), fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
