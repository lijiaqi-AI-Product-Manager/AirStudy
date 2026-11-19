package com.projectfocus.widget.glance

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.background
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
import com.projectfocus.widget.snapshot.WidgetSnapshot
import com.projectfocus.widget.snapshot.WidgetText
import kotlinx.coroutines.flow.first
import timber.log.Timber


































object WidgetStateStore {

    // 键名前缀统一 widget_，避免与其他 StateDefinition 冲突
    private val KEY_HAS_SESSION = booleanPreferencesKey("widget_has_session")
    private val KEY_PAUSED = booleanPreferencesKey("widget_paused")
    private val KEY_REMAINING_MS = longPreferencesKey("widget_remaining_ms")
    private val KEY_TOTAL_MS = longPreferencesKey("widget_total_ms")
    private val KEY_ELAPSED_MS = longPreferencesKey("widget_elapsed_ms")
    private val KEY_DESTINATION = stringPreferencesKey("widget_destination")
    private val KEY_SCENARIO = stringPreferencesKey("widget_scenario")
    private val KEY_UPDATED_AT = longPreferencesKey("widget_updated_at")

    /** 从一份 Preferences 解析出快照。纯函数，可单测。 */
    fun fromPreferences(prefs: Preferences): WidgetSnapshot {
        if (!(prefs[KEY_HAS_SESSION] ?: false)) return WidgetSnapshot.Idle
        val total = prefs[KEY_TOTAL_MS] ?: 0L
        return if (prefs[KEY_PAUSED] ?: false) {
            WidgetSnapshot.Paused(
                elapsedMs = prefs[KEY_ELAPSED_MS] ?: 0L,
                totalMs = total,
                scenarioLabel = prefs[KEY_SCENARIO] ?: "",
            )
        } else {
            WidgetSnapshot.Flying(
                remainingMs = prefs[KEY_REMAINING_MS] ?: 0L,
                totalMs = total,
                destinationCode = prefs[KEY_DESTINATION]?.takeIf { it.isNotBlank() },
                scenarioLabel = prefs[KEY_SCENARIO] ?: "",
            )
        }
    }

    /** 把快照写成 Preference 变更。纯函数，可单测 —— 与 fromPreferences 互为逆运算。 */
    fun toPreferences(prefs: MutablePreferences, snapshot: WidgetSnapshot, nowWallMs: Long) {
        prefs[KEY_UPDATED_AT] = nowWallMs
        when (snapshot) {
            WidgetSnapshot.Idle -> {
                prefs[KEY_HAS_SESSION] = false
                prefs.remove(KEY_PAUSED)
                prefs.remove(KEY_REMAINING_MS)
                prefs.remove(KEY_TOTAL_MS)
                prefs.remove(KEY_ELAPSED_MS)
                prefs.remove(KEY_DESTINATION)
                prefs.remove(KEY_SCENARIO)
            }
            is WidgetSnapshot.Flying -> {
                prefs[KEY_HAS_SESSION] = true
                prefs[KEY_PAUSED] = false
                prefs[KEY_REMAINING_MS] = snapshot.remainingMs
                prefs[KEY_TOTAL_MS] = snapshot.totalMs
                prefs[KEY_ELAPSED_MS] = snapshot.elapsedMs
                prefs[KEY_DESTINATION] = snapshot.destinationCode.orEmpty()
                prefs[KEY_SCENARIO] = snapshot.scenarioLabel
            }
            is WidgetSnapshot.Paused -> {
                prefs[KEY_HAS_SESSION] = true
                prefs[KEY_PAUSED] = true
                prefs[KEY_ELAPSED_MS] = snapshot.elapsedMs
                prefs[KEY_TOTAL_MS] = snapshot.totalMs
                prefs[KEY_REMAINING_MS] = snapshot.remainingMs
                prefs[KEY_SCENARIO] = snapshot.scenarioLabel
                prefs.remove(KEY_DESTINATION)
            }
            is WidgetSnapshot.Today -> {

                //   两个 widget 类型共用一份 Preferences 会让状态互相覆盖 ——
                //   用户同时放了飞行与今日小组件时，一个更新把另一个的清掉了。
                //   这里显式 no-op 并在注释里说清楚，比悄悄忽略好。
            }
        }
    }

    /** 读某一份状态（GlanceId 维度） */
    suspend fun read(context: Context, prefs: Preferences): WidgetSnapshot = fromPreferences(prefs)

    /** 读「当前飞行状态」—— 用于非 Glance 上下文（比如 Service 想回读） */
    suspend fun readFlying(context: Context): WidgetSnapshot = WidgetSnapshot.Idle









    suspend fun pushFlying(context: Context, snapshot: WidgetSnapshot) {
        runCatching {
            PushHelper.updateAll(context, FlyingWidget::class.java, snapshot)
        }.onFailure { Timber.w(it, "更新飞行小组件失败") }
    }
}

/** 内部工具：遍历所有实例并更新 */
private object PushHelper {
    suspend fun updateAll(context: Context, clazz: Class<out GlanceAppWidget>, snapshot: WidgetSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(clazz)
        val widget = FlyingWidget()
        ids.forEach { id ->
            androidx.glance.appwidget.state.updateAppWidgetState(context, id) { prefs ->
                WidgetStateStore.toPreferences(prefs, snapshot, System.currentTimeMillis())
            }
            widget.update(context, id)
        }
    }
}

/**
 * 「进行中」小组件 —— 4x2。
 *
 * 显示：剩余时间（大字）+ 进度条 + 航线/场景。
 * 点击：拉起 [WidgetEntryActivity]（透明中转页）→ 主界面。
 */
class FlyingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {

        //   这里只解析。理由见 WidgetStateStore 头注释。
        provideContent {
            GlanceTheme {
                val prefs = androidx.glance.currentState<Preferences>()
                Content(WidgetStateStore.fromPreferences(prefs))
            }
        }
    }

    @Composable
    private fun Content(snapshot: WidgetSnapshot) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.BgBase)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<WidgetEntryActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            when (snapshot) {
                is WidgetSnapshot.Flying -> FlyingBody(
                    snapshot = snapshot,
                    titleColor = WidgetPalette.TextSecondary,
                    accent = WidgetPalette.AccentPrimary,
                    title = titleOf(snapshot),
                )

                is WidgetSnapshot.Paused -> FlyingBody(
                    snapshot = snapshot,
                    titleColor = WidgetPalette.Warning,
                    accent = WidgetPalette.Warning,
                    title = str(R.string.widget_flying_paused_title),
                )

                else -> IdleBody()
            }
        }
    }

    /** 两种状态的公共骨架 —— 只有标题文案与强调色不同 */
    @Composable
    private fun FlyingBody(
        snapshot: WidgetSnapshot,
        titleColor: Color,
        accent: Color,
        title: String,
    ) {
        val remaining = when (snapshot) {
            is WidgetSnapshot.Flying -> snapshot.remainingMs
            is WidgetSnapshot.Paused -> snapshot.remainingMs
            else -> 0L
        }
        val progress = when (snapshot) {
            is WidgetSnapshot.Flying -> snapshot.progress
            is WidgetSnapshot.Paused -> snapshot.progress
            else -> 0f
        }

        Text(
            text = title,
            style = TextStyle(color = ColorProvider(titleColor), fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = WidgetText.countdown(remaining),
            style = TextStyle(
                color = ColorProvider(WidgetPalette.TextPrimary),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            ),

            //   Glance 的 Text 没有 autoSize，所以宁可给足宽度（34sp 在
            //   4x2 的宽度里放得下 7 个字符）。
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(10.dp))
        ProgressBar(progress = progress, color = accent)
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(
            text = WidgetText.progressPercent(progress),
            style = TextStyle(color = ColorProvider(WidgetPalette.TextTertiary), fontSize = 11.sp),
            maxLines = 1,
        )
    }














    @Composable
    private fun ProgressBar(progress: Float, color: Color) {
        val filledSteps = (progress.coerceIn(0f, 1f) * STEPS).toInt()
        Row(modifier = GlanceModifier.fillMaxWidth().height(4.dp)) {
            repeat(STEPS) { index ->
                val isFilled = index < filledSteps
                androidx.glance.layout.Box(
                    modifier = GlanceModifier
                        .height(4.dp)
                        .defaultWeight()
                        .background(if (isFilled) color else WidgetPalette.BgElevated)
                        .padding(horizontal = if (index == 0 || index == STEPS - 1) 0.dp else 0.dp),
                ) {}
            }
        }
    }

    @Composable
    private fun IdleBody() {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = str(R.string.widget_flying_idle_title),
                    style = TextStyle(
                        color = ColorProvider(WidgetPalette.TextPrimary),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = str(R.string.widget_flying_idle_hint),
                    style = TextStyle(color = ColorProvider(WidgetPalette.TextTertiary), fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            Spacer(modifier = GlanceModifier.width(12.dp))
            Text(
                text = str(R.string.widget_flying_idle_action),
                modifier = GlanceModifier
                    .background(WidgetPalette.AccentPrimary)
                    .cornerRadius(18.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = TextStyle(
                    color = ColorProvider(WidgetPalette.BgBase),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }

    private fun titleOf(flying: WidgetSnapshot.Flying): String {
        val route = WidgetText.routeLabel(null, flying.destinationCode)
        return if (route == "专注航线" && flying.scenarioLabel.isNotBlank()) {
            flying.scenarioLabel
        } else {
            route
        }
    }

    private companion object {
        /** 进度条量化档数。见 ProgressBar 的注释 */
        const val STEPS = 20
    }
}

/** 小组件提供者（manifest 里注册的那个） */
class FlyingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlyingWidget()
}
