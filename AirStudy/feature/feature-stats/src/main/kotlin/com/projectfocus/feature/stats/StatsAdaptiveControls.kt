package com.projectfocus.feature.stats

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil

internal class StatsPeriodTabsLayout(context: Context) : ViewGroup(context) {
    private fun dp(value: Int) = ceil(value * resources.displayMetrics.density.toDouble()).toInt()
    private fun columns(available: Int): Int {
        val labelWidth=(0 until childCount).map { getChildAt(it) as TextView }
            .maxOfOrNull { ceil(it.paint.measureText(it.text.toString()).toDouble()).toInt()+dp(12) } ?: 0
        val minimum=maxOf(dp(48),labelWidth)
        return (available/minimum).coerceIn(1,childCount.coerceAtLeast(1))
    }
    fun requiredHeight(available: Int): Int {
        val count=columns(available)
        return ((childCount+count-1)/count)*dp(52)
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val count=columns(width)
        for(index in 0 until childCount) {
            val row=index/count
            val cells=minOf(count,childCount-row*count)
            val column=index%count
            val cellWidth=width*(column+1)/cells-width*column/cells
            getChildAt(index).measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(52),MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width,resolveSize(requiredHeight(width),heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        val count=columns(width)
        for(index in 0 until childCount) {
            val row=index/count
            val cells=minOf(count,childCount-row*count)
            val column=index%count
            getChildAt(index).layout(width*column/cells,row*dp(52),width*(column+1)/cells,(row+1)*dp(52))
        }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
}

internal class StatsDateNavigationLayout(context: Context) : ViewGroup(context) {
    private var stacked=false
    private var arrowsShown=true
    private fun target() = ceil(48*resources.displayMetrics.density.toDouble()).toInt()
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val label=getChildAt(1) as TextView
        val size=target()
        arrowsShown=getChildAt(0).visibility==View.VISIBLE || getChildAt(2).visibility==View.VISIBLE
        stacked=arrowsShown && (label.paint.measureText(label.text.toString())>width-2*size ||
            resources.configuration.fontScale>=1.3f && width/resources.displayMetrics.density<420f)
        val labelWidth=if(stacked || !arrowsShown)width else(width-2*size).coerceAtLeast(0)
        label.measure(MeasureSpec.makeMeasureSpec(labelWidth,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        for(index in listOf(0,2))getChildAt(index).measure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY))
        val height=if(stacked)label.measuredHeight+size else maxOf(size,label.measuredHeight)
        setMeasuredDimension(width,resolveSize(height,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        val label=getChildAt(1);val size=target()
        val y=if(stacked)height-size else(height-size)/2
        getChildAt(0).layout(0,y,size,y+size)
        getChildAt(2).layout(width-size,y,width,y+size)
        val x=if(stacked || !arrowsShown)0 else size
        val labelY=if(stacked)0 else(height-label.measuredHeight)/2
        label.layout(x,labelY,x+label.measuredWidth,labelY+label.measuredHeight)
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
}
