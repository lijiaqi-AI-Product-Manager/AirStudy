package com.projectfocus.core.ui.sky

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.ceil

open class ArtworkPreviewOverlay(context: Context): FrameLayout(context) {
    var artwork: ImageView?=null
    var action: View?=null
    var closeControl: View?=null
    val artworkScale=1.02f
    private fun dp(value: Float)=ShareArtworkFrame.dp(context,value).toInt()

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val height=MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(resolveSize(width,widthMeasureSpec),resolveSize(height,heightMeasureSpec))
        val image=artwork;val button=action
        for(index in 0 until childCount) {
            val child=getChildAt(index)
            if(child!==image && child!==button && child!==closeControl) child.measure(MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY))
        }
        closeControl?.measure(MeasureSpec.makeMeasureSpec(dp(48f),MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(48f),MeasureSpec.EXACTLY))
        if(image==null || button==null)return
        button.measure(MeasureSpec.makeMeasureSpec((measuredWidth-dp(32f)).coerceAtLeast(1),MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((measuredHeight-dp(32f)).coerceAtLeast(1),MeasureSpec.AT_MOST))
        val bitmap=(image.drawable as? BitmapDrawable)?.bitmap ?: return
        val ratio=bitmap.width.toFloat()/bitmap.height
        val headerExtra=if(closeControl==null)0 else dp(48f)
        val maxHeight=((measuredHeight-button.measuredHeight-dp(64f)-headerExtra)/artworkScale).toInt().coerceAtLeast(1)
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
            if(child!==image && child!==button && child!==closeControl)child.layout(0,0,width,height)
        }
        closeControl?.let { close ->
            val x=(width-close.measuredWidth-dp(12f)).coerceAtLeast(0)
            close.layout(x,dp(8f),x+close.measuredWidth,dp(8f)+close.measuredHeight)
        }
        if(image==null || button==null)return
        val extra=image.measuredHeight*(artworkScale-1f)/2f
        val headerExtra=if(closeControl==null)0 else dp(48f)
        val imageTop=ceil((height+headerExtra-image.measuredHeight*artworkScale-button.measuredHeight-dp(16f))/2f+extra)
            .toInt().coerceAtLeast(dp(16f)+headerExtra)
        val imageLeft=((width-image.measuredWidth)/2).coerceAtLeast(0)
        image.layout(imageLeft,imageTop,imageLeft+image.measuredWidth,imageTop+image.measuredHeight)
        val buttonTop=ceil(image.bottom+extra+dp(16f)).toInt()
            .coerceAtMost((height-button.measuredHeight-dp(16f)).coerceAtLeast(0))
        val buttonLeft=((width-button.measuredWidth)/2).coerceAtLeast(0)
        button.layout(buttonLeft,buttonTop,buttonLeft+button.measuredWidth,buttonTop+button.measuredHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }
}
