package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.projectfocus.core.ui.R as UiR
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal object ShareArtwork {
    const val OUTPUT_WIDTH = 1080
    private const val PIXEL_BUDGET = 4_000_000L
    fun dp(context: Context,value: Float)=value*context.resources.displayMetrics.density
    fun logicalSize(context: Context,width: Int,height: Int)=Pair(
        width+dp(context,36f),height+dp(context,104f))

    fun render(context: Context,view: View): Bitmap {
        require(view.width>0 && view.height>0)
        val (width,height)=logicalSize(context,view.width,view.height)
        val ratio=width/height
        val outputWidth=minOf(OUTPUT_WIDTH,floor(sqrt(PIXEL_BUDGET*ratio.toDouble())).toInt()).coerceAtLeast(1)
        val outputHeight=ceil(outputWidth/ratio.toDouble()).toInt().coerceAtLeast(1)
        require(outputWidth.toLong()*outputHeight<=PIXEL_BUDGET+OUTPUT_WIDTH)
        val bitmap=Bitmap.createBitmap(outputWidth,outputHeight,Bitmap.Config.ARGB_8888)
        try { draw(Canvas(bitmap),context,view,outputWidth,outputHeight) }
        catch(error: Throwable) { bitmap.recycle();throw error }
        return bitmap
    }

    fun draw(canvas: Canvas,context: Context,view: View,width: Int,height: Int) {
        val (logicalWidth,logicalHeight)=logicalSize(context,view.width,view.height)
        val saved=canvas.save()
        try {
            canvas.scale(width/logicalWidth,height/logicalHeight)
            drawFrame(canvas,context,logicalWidth,logicalHeight)
            canvas.translate(dp(context,18f),dp(context,72f))
            view.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }

    private fun drawFrame(canvas: Canvas,context: Context,width: Float,height: Float) {
        val primary=context.getColor(UiR.color.sky_primary)
        val ink=context.getColor(UiR.color.sky_ink)
        val secondary=ColorUtils.blendARGB(context.getColor(UiR.color.sky_ink_secondary),ink,.16f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val box=RectF(0f,0f,width,height)
        paint.shader=LinearGradient(0f,0f,width,height,
            intArrayOf(context.getColor(UiR.color.sky_canvas),context.getColor(UiR.color.sky_selected),
                context.getColor(UiR.color.sky_card_solid)),null,Shader.TileMode.CLAMP)
        canvas.drawRect(box,paint)
        paint.shader=RadialGradient(width*.9f,0f,width*.85f,
            intArrayOf(ColorUtils.setAlphaComponent(primary,42),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        canvas.drawRect(box,paint)
        paint.shader=null
        paint.color=ink
        paint.textSize=dp(context,24f)
        paint.typeface=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_black)
        canvas.drawText("AirStudy",dp(context,24f),dp(context,33f),paint)
        paint.color=secondary
        paint.textSize=dp(context,9f)
        paint.typeface=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_medium)
        canvas.drawText("FLIGHT JOURNAL",dp(context,25f),dp(context,51f),paint)
        drawPlane(canvas,context,width-dp(context,40f),dp(context,32f),primary)
        paint.textSize=dp(context,9f)
        paint.textAlign=Paint.Align.CENTER
        paint.color=secondary
        canvas.drawText("AirStudy",width/2f,height-dp(context,12f),paint)
    }

    fun drawPlane(canvas: Canvas,context: Context,x: Float,y: Float,color: Int) {
        val d=context.resources.displayMetrics.density
        val saved=canvas.save()
        canvas.translate(x,y);canvas.scale(d,d)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val shape=Path().apply { moveTo(-15f,-8f);lineTo(16f,-15f);lineTo(6f,14f);lineTo(-1f,3f);close() }
        paint.color=ColorUtils.setAlphaComponent(color,28)
        canvas.translate(1f,3f);canvas.drawPath(shape,paint);canvas.translate(-1f,-3f)
        paint.shader=LinearGradient(-15f,-8f,16f,14f,
            intArrayOf(ColorUtils.blendARGB(color,Color.WHITE,.9f),ColorUtils.blendARGB(color,Color.WHITE,.46f)),
            null,Shader.TileMode.CLAMP)
        canvas.drawPath(shape,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;paint.color=ColorUtils.setAlphaComponent(color,190)
        canvas.drawPath(shape,paint)
        canvas.drawPath(Path().apply { moveTo(16f,-15f);lineTo(-1f,3f);lineTo(6f,14f) },paint)
        canvas.restoreToCount(saved)
    }
}
