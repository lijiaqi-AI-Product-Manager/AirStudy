package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.projectfocus.core.ui.sky.ShareArtworkFrame

internal object ShareArtwork {
    const val OUTPUT_WIDTH = ShareArtworkFrame.OUTPUT_WIDTH
    fun dp(context: Context,value: Float)=ShareArtworkFrame.dp(context,value)
    fun logicalSize(context: Context,width: Int,height: Int)=ShareArtworkFrame.logicalSize(context,width,height)
    fun render(context: Context,view: View)=ShareArtworkFrame.render(context,view)
    fun draw(canvas: Canvas,context: Context,view: View,width: Int,height: Int)=ShareArtworkFrame.draw(canvas,context,view,width,height)
    fun drawPlane(canvas: Canvas,context: Context,x: Float,y: Float,color: Int)=ShareArtworkFrame.drawPlane(canvas,context,x,y,color)
}
