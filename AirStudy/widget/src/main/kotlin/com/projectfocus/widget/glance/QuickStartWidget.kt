package com.projectfocus.widget.glance

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.projectfocus.widget.R
import com.projectfocus.widget.WidgetEntryActivity
import com.projectfocus.widget.snapshot.WidgetText


private const val QUICK_START_MINUTES = 25

class QuickStartWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {

        //   看起来"偷懒"，但静态恰恰意味着零刷新成本、零状态不同步风险。
        //   代价是它无法显示"上次飞了多久" —— 见类注释，那是刻意的取舍。
        provideContent {
            GlanceTheme {
                Content()
            }
        }
    }

    @Composable
    private fun Content() {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.BgBase)
                .cornerRadius(24.dp)
                .padding(16.dp)

                //   中转页负责把 action 转交给主界面（见 WidgetEntryActivity 的
                //   route() 与 EXTRA_ACTION）。
                .clickable(

                    //   不是单个 Pair。写成 `parameters = key to value` 会编译失败：
                    //   `Argument type mismatch: actual type is 'ActionParameters.Pair<String>'`。
                    //   → 用 `actionParametersOf(key to value)`。
                    //   （这是 Glance 0.1 → 1.x 之间改过的 API 形状，网上教程两种都有。）
                    actionStartActivity<WidgetEntryActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(WidgetEntryActivity.EXTRA_ACTION) to
                                WidgetEntryActivity.ACTION_QUICK_START,
                        ),
                    ),
                ),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.Start,
        ) {
            Text(
                text = str(R.string.widget_quick_start_title),
                style = TextStyle(color = ColorProvider(WidgetPalette.TextSecondary), fontSize = 12.sp),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = "$QUICK_START_MINUTES",
                style = TextStyle(
                    color = ColorProvider(WidgetPalette.TextPrimary),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = str(R.string.widget_quick_start_unit),
                style = TextStyle(color = ColorProvider(WidgetPalette.TextTertiary), fontSize = 11.sp),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(10.dp))
            StartPill()
        }
    }












    @Composable
    private fun StartPill() {
        Text(
            text = str(R.string.widget_quick_start_action),
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(WidgetPalette.AccentPrimary)
                .cornerRadius(16.dp)
                .padding(vertical = 8.dp),
            style = TextStyle(
                color = ColorProvider(WidgetPalette.BgBase),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

class QuickStartWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickStartWidget()
}
