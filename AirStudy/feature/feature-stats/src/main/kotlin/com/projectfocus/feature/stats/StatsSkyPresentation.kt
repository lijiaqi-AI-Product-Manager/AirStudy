package com.projectfocus.feature.stats

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces

internal object StatsSkyPresentation {
    fun page(root: View) {
        val context=root.context
        val title=root.findViewById<ViewGroup>(R.id.statsTitleBar)
        for(index in 0 until title.childCount) (title.getChildAt(index) as? TextView)
            ?.setTextColor(context.getColor(UiR.color.sky_ink))
        for(id in listOf(R.id.statsBack,R.id.statsShare)) {
            val control=root.findViewById<View>(id)
            SkySurfaces.secondary(control,24f)
            SkyPressFeedback.install(control)
            if(control is ImageView) control.imageTintList=ColorStateList.valueOf(context.getColor(UiR.color.sky_ink))
        }
        for(id in listOf(R.id.statsDateControls,R.id.statsHeadline,R.id.statsTrendPanel,
            R.id.statsScenePanel,R.id.statsHourPanel,R.id.statsInsightsPanel,R.id.statsHighlightsPanel,
            R.id.statsHeatmapPanel,R.id.statsCareerPanel)) {
            root.findViewById<View>(id).apply {
                elevation=SkySurfaces.dp(context,4f).toFloat()
                if(android.os.Build.VERSION.SDK_INT>=28) {
                    outlineAmbientShadowColor=0xFF384F8C.toInt()
                    outlineSpotShadowColor=0xFF384F8C.toInt()
                }
            }
        }
        root.findViewById<TextView>(R.id.statsHistoryStatus).apply {
            setTextColor(context.getColor(UiR.color.sky_ink_secondary))
            SkySurfaces.card(this,20f,1f,true)
        }
        root.findViewById<TextView>(R.id.statsSourceNote)
            .setTextColor(context.getColor(UiR.color.sky_ink_secondary))
    }
}
