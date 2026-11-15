package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.ceil

internal class RecordShareOverlay(context: Context): FrameLayout(context) {
    var artwork: ImageView?=null
    var action: View?=null
    val artworkScale=1.02f
    private fun dp(value: Float)=ShareArtwork.dp(context,value).toInt()

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val height=MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(resolveSize(width,widthMeasureSpec),resolveSize(height,heightMeasureSpec))
        val image=artwork;val button=action
        for(index in 0 until childCount) {
            val child=getChildAt(index)
            if(child!==image && child!==button) child.measure(MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY))
        }
        if(image==null || button==null)return
        button.measure(MeasureSpec.makeMeasureSpec((measuredWidth-dp(32f)).coerceAtLeast(1),MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((measuredHeight-dp(32f)).coerceAtLeast(1),MeasureSpec.AT_MOST))
        val bitmap=(image.drawable as? BitmapDrawable)?.bitmap ?: return
        val ratio=bitmap.width.toFloat()/bitmap.height
        val maxHeight=((measuredHeight-button.measuredHeight-dp(64f))/artworkScale).toInt().coerceAtLeast(1)
        val maxWidth=((measuredWidth-dp(48f))/artworkScale).toInt().coerceAtLeast(1)
        val imageWidth=minOf(maxWidth,(maxHeight*ratio).toInt().coerceAtLeast(1))
        val imageHeight=(imageWidth/ratio).toInt().coerceAtLeast(1)
        image.measure(MeasureSpec.makeMeasureSpec(imageWidth,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(imageHeight,MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        val image=artwork;val button=action
        for(index in 0 until childCount) {
            val child=getChildAt(index)
            if(child!==image && child!==button)child.layout(0,0,width,height)
        }
        if(image==null || button==null)return
        val extra=image.measuredHeight*(artworkScale-1f)/2f
        val imageTop=ceil((height-image.measuredHeight*artworkScale-button.measuredHeight-dp(16f))/2f+extra)
            .toInt().coerceAtLeast(dp(16f))
        val imageLeft=((width-image.measuredWidth)/2).coerceAtLeast(0)
        image.layout(imageLeft,imageTop,imageLeft+image.measuredWidth,imageTop+image.measuredHeight)
        val buttonTop=ceil(image.bottom+extra+dp(16f)).toInt()
            .coerceAtMost((height-button.measuredHeight-dp(16f)).coerceAtLeast(0))
        val buttonLeft=((width-button.measuredWidth)/2).coerceAtLeast(0)
        button.layout(buttonLeft,buttonTop,buttonLeft+button.measuredWidth,buttonTop+button.measuredHeight)
    }
}
