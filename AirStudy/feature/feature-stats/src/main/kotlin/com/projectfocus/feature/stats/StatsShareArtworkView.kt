package com.projectfocus.feature.stats

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.sky.ShareArtworkFrame
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.R as UiR

/** A measured paper composition; its content grows with complete labels. */
internal class StatsShareArtworkView(context: Context,val spec: StatsShareSpec): LinearLayout(context) {
    private fun dp(value: Float)=SkySurfaces.dp(context,value)
    private val ink=context.getColor(UiR.color.sky_ink)
    private val secondary=context.getColor(UiR.color.sky_ink_secondary)
    private val strong=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_black)
    private val medium=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_medium)

    init {
        orientation=VERTICAL
        setPadding(dp(22f),dp(24f),dp(22f),dp(24f))
        SkySurfaces.card(this,24f,0f)
        contentDescription=spec.summary
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        val heading=LinearLayout(context).apply { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        heading.addView(ClockMark(context),LayoutParams(dp(48f),dp(48f)))
        heading.addView(label(context.getString(R.string.stats_share_artwork_title),19f,true),LayoutParams(0,-2,1f).apply { marginStart=dp(12f) })
        addView(heading,LayoutParams(-1,-2))
        addView(label(spec.period,14f),LayoutParams(-1,-2).apply { topMargin=dp(20f) })
        addView(label(spec.scene,13f,true).apply {
            setPadding(dp(12f),dp(9f),dp(12f),dp(9f));SkySurfaces.card(this,14f,0f)
        },LayoutParams(-1,-2).apply { topMargin=dp(12f) })
        addView(label(spec.focusLabel,13f),LayoutParams(-1,-2).apply { topMargin=dp(28f) })
        addView(label(spec.focusMinutes?.let { StatsFocusText.rich(context,it.toDouble(),secondary) }
            ?:context.getString(R.string.stats_placeholder),46f,true).apply { id=R.id.statsShareArtworkFocus },
            LayoutParams(-1,-2).apply { topMargin=dp(4f) })
        for(pair in spec.metrics.chunked(2)) {
            val row=LinearLayout(context).apply { orientation=HORIZONTAL;isBaselineAligned=false }
            pair.forEachIndexed { index,metric ->
                val cell=LinearLayout(context).apply {
                    orientation=VERTICAL;minimumHeight=dp(104f)
                    setPadding(dp(14f),dp(14f),dp(14f),dp(14f))
                    SkySurfaces.card(this,18f,0f)
                    addView(label(metric.label,11f),LayoutParams(-1,-2))
                    addView(label(metric.value,23f,true),LayoutParams(-1,-2).apply { topMargin=dp(7f) })
                    if(metric.unit.isNotEmpty())addView(label(metric.unit,11f),LayoutParams(-1,-2).apply { topMargin=dp(2f) })
                }
                row.addView(cell,LayoutParams(0,-1,1f).apply { if(index>0)marginStart=dp(12f) })
            }
            addView(row,LayoutParams(-1,-2).apply { topMargin=dp(12f) })
        }
        addView(label(spec.note,11f).apply {
            if(spec.partial) {
                setTextColor(context.getColor(UiR.color.sky_ink))
                setPadding(dp(12f),dp(12f),dp(12f),dp(12f));SkySurfaces.card(this,14f,0f)
            }
        },LayoutParams(-1,-2).apply { topMargin=dp(22f) })
        addView(label(context.getString(R.string.stats_share_artwork_tagline),14f).apply {
            gravity=Gravity.CENTER;typeface=Typeface.create("serif",Typeface.ITALIC)
        },LayoutParams(-1,-2).apply { topMargin=dp(24f) })
    }

    private fun label(value: CharSequence,size: Float,bold: Boolean=false)=TextView(context).apply {
        text=value;textSize=size;typeface=if(bold)strong else medium
        setTextColor(if(bold)ink else secondary)
        includeFontPadding=false
        setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null
        setLineSpacing(dp(3f).toFloat(),1f)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private class ClockMark(context: Context): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val scale=width/48f;val saved=canvas.save();canvas.scale(scale,scale)
            paint.style=Paint.Style.FILL;paint.color=context.getColor(UiR.color.sky_selected)
            canvas.drawCircle(25f,26f,21f,paint)
            paint.color=context.getColor(UiR.color.sky_card_solid);canvas.drawCircle(23f,23f,19f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f;paint.strokeCap=Paint.Cap.ROUND
            paint.color=context.getColor(UiR.color.sky_primary);canvas.drawCircle(23f,23f,16f,paint)
            canvas.drawLine(23f,23f,23f,12f,paint);canvas.drawLine(23f,23f,31f,27f,paint)
            paint.style=Paint.Style.FILL;canvas.drawCircle(23f,23f,2f,paint)
            canvas.restoreToCount(saved)
        }
    }

    companion object {
        fun render(context: Context,spec: StatsShareSpec): Bitmap {
            val stable=ContextThemeWrapper(context,UiR.style.Theme_Pf).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale=1f })
            }
            val view=StatsShareArtworkView(stable,spec)
            val width=SkySurfaces.dp(stable,360f)
            view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            view.layout(0,0,view.measuredWidth,view.measuredHeight)
            return ShareArtworkFrame.render(stable,view,context.getString(R.string.stats_share_artwork_subtitle))
        }
    }
}
