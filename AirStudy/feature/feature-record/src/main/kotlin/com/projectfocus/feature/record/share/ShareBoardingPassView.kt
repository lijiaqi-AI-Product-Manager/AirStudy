package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.completion.pass.BoardingPassView
import com.projectfocus.core.ui.R as UiR
import kotlin.math.ceil
import kotlin.math.max

internal class ShareBoardingPassView(context: Context): BoardingPassView(context) {
    private fun dp(value: Float)=ShareArtwork.dp(context,value)
    private fun color(id: Int)=context.getColor(id)
    private fun text(size: Float,bold: Boolean=false)=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=dp(size)
        color=color(UiR.color.sky_ink)
        typeface=ResourcesCompat.getFont(context,if(bold)UiR.font.harmonyos_sans_sc_black else UiR.font.harmonyos_sans_sc_medium)
    }
    private val code=text(44f,true)
    private val city=text(12f)
    private val time=text(16f,true)
    private val label=text(9f).apply { color=ColorUtils.blendARGB(color(UiR.color.sky_ink_secondary),color(UiR.color.sky_ink),.16f) }
    private val value=text(15f,true)
    private val small=text(11f,true)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private data class Field(val label: String,val text: StaticLayout,val x: Float,val top: Float)
    private data class Ticket(val fromCity: StaticLayout,val toCity: StaticLayout,val cityTop: Float,
        val timeTop: Float,val fields: List<Field>,val height: Float,val mainRight: Float,val columnWidth: Float,
        val passenger: StaticLayout?,val passengerTop: Float)

    private fun paragraph(text: String,paint: TextPaint,width: Float,alignment: Layout.Alignment=Layout.Alignment.ALIGN_NORMAL)=
        StaticLayout.Builder.obtain(text,0,text.length,paint,width.toInt().coerceAtLeast(1))
            .setIncludePad(false).setAlignment(alignment).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()

    private fun ticket(width: Int,d: BoardingPassData): Ticket {
        val right=width-dp(70f)
        val usable=right-dp(40f)
        val column=(usable-dp(16f))/2f
        val from=paragraph(d.departureCity,city,column)
        val to=paragraph(d.arrivalCity,city,column,Layout.Alignment.ALIGN_OPPOSITE)
        val cityTop=dp(80f)
        val cityHeight=max(if(d.departureCity.isBlank())0 else from.height,if(d.arrivalCity.isBlank())0 else to.height)
        val timeTop=cityTop+cityHeight+dp(8f)
        var top=timeTop+dp(36f)
        val fields=mutableListOf<Field>()
        val values=listOf("FLIGHT" to d.flightNo,"SEAT" to d.seat,
            "DISTANCE" to d.distanceText,"FOCUS" to d.focusDurationText,
            "BOARDING" to d.boardingTime,"DATE" to d.date)
        for(row in values.chunked(2)) {
            val layouts=row.map { paragraph(it.second,value,column) }
            row.forEachIndexed { index,item -> fields+=Field(item.first,layouts[index],dp(20f)+index*(column+dp(16f)),top) }
            top+=dp(17f)+layouts.maxOf { it.height }+dp(16f)
        }
        val passenger=if(d.passengerName.isBlank())null else paragraph(d.passengerName,value,usable)
        val passengerTop=top
        if(passenger!=null)top+=dp(17f)+passenger.height+dp(16f)
        return Ticket(from,to,cityTop,timeTop,fields,top+dp(6f),right,column,passenger,passengerTop)
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=resolveSize(dp(400f).toInt(),widthMeasureSpec)
        val height=data?.let { ceil(ticket(width,it).height.toDouble()).toInt() } ?: dp(320f).toInt()
        setMeasuredDimension(width,resolveSize(height,heightMeasureSpec))
    }

    private fun drawParagraph(canvas: Canvas,text: StaticLayout,x: Float,y: Float) {
        val saved=canvas.save();canvas.translate(x,y);text.draw(canvas);canvas.restoreToCount(saved)
    }

    override fun onDraw(canvas: Canvas) {
        val d=data ?: return
        val layout=ticket(width,d)
        val bounds=RectF(dp(1f),dp(1f),width-dp(1f),height-dp(1f))
        val path=Path().apply { addRoundRect(bounds,dp(22f),dp(22f),Path.Direction.CW) }
        val holes=Path().apply {
            addCircle(layout.mainRight,0f,dp(9f),Path.Direction.CW)
            addCircle(layout.mainRight,height.toFloat(),dp(9f),Path.Direction.CW)
        }
        if(!path.op(holes,Path.Op.DIFFERENCE)) {
            path.reset();path.addRoundRect(bounds,dp(22f),dp(22f),Path.Direction.CW)
        }
        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),
            intArrayOf(color(UiR.color.sky_card_top),color(UiR.color.sky_card_bottom)),null,Shader.TileMode.CLAMP)
        canvas.drawPath(path,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1f);paint.color=color(UiR.color.sky_edge)
        canvas.drawPath(path,paint)
        paint.pathEffect=DashPathEffect(floatArrayOf(dp(3f),dp(4f)),0f)
        paint.color=color(UiR.color.sky_divider)
        canvas.drawLine(layout.mainRight,dp(14f),layout.mainRight,height-dp(14f),paint)
        paint.pathEffect=null
        canvas.drawText("BOARDING PASS",dp(20f),dp(25f),label)
        val left=dp(20f);val right=layout.mainRight-dp(20f)
        code.textSize=dp(44f)
        val widest=max(code.measureText(d.departureCode),code.measureText(d.arrivalCode))
        if(widest>layout.columnWidth)code.textSize*=layout.columnWidth/widest
        canvas.drawText(d.departureCode,left,dp(68f),code)
        canvas.drawText(d.arrivalCode,right-code.measureText(d.arrivalCode),dp(68f),code)
        drawParagraph(canvas,layout.fromCity,left,layout.cityTop)
        drawParagraph(canvas,layout.toCity,left+layout.columnWidth+dp(16f),layout.cityTop)
        canvas.drawText(d.departureTime,left,layout.timeTop-time.fontMetrics.ascent,time)
        canvas.drawText(d.arrivalTime,right-time.measureText(d.arrivalTime),layout.timeTop-time.fontMetrics.ascent,time)
        val middle=(left+right)/2f
        paint.color=ColorUtils.setAlphaComponent(color(UiR.color.sky_primary),90)
        paint.strokeWidth=dp(1f);paint.pathEffect=DashPathEffect(floatArrayOf(dp(2f),dp(3f)),0f)
        canvas.drawLine(middle-dp(22f),dp(54f),middle+dp(22f),dp(54f),paint);paint.pathEffect=null
        ShareArtwork.drawPlane(canvas,context,middle,dp(54f),color(UiR.color.sky_primary))
        for(field in layout.fields) {
            canvas.drawText(field.label,field.x,field.top-label.fontMetrics.ascent,label)
            drawParagraph(canvas,field.text,field.x,field.top+dp(17f))
        }
        layout.passenger?.let {
            canvas.drawText("PASSENGER",left,layout.passengerTop-label.fontMetrics.ascent,label)
            drawParagraph(canvas,it,left,layout.passengerTop+dp(17f))
        }
        val stubX=(layout.mainRight+width)/2f
        ShareArtwork.drawPlane(canvas,context,stubX,dp(31f),color(UiR.color.sky_primary))
        val saved=canvas.save()
        canvas.rotate(90f,stubX,height*.42f)
        small.textSize=dp(14f)
        val available=height*.36f
        if(small.measureText(d.flightNo)>available)small.textSize*=available/small.measureText(d.flightNo)
        canvas.drawText(d.flightNo,stubX-small.measureText(d.flightNo)/2f,height*.42f-small.fontMetrics.ascent/3f,small)
        canvas.restoreToCount(saved)
        val stamp=RectF(layout.mainRight+dp(6f),height-dp(64f),width-dp(6f),height-dp(28f))
        val accent=color(if(d.abandoned)UiR.color.semantic_warning else UiR.color.sky_primary)
        paint.style=Paint.Style.FILL;paint.color=ColorUtils.setAlphaComponent(accent,24)
        canvas.drawRoundRect(stamp,dp(7f),dp(7f),paint)
        paint.style=Paint.Style.STROKE;paint.color=ColorUtils.setAlphaComponent(accent,105)
        canvas.drawRoundRect(stamp,dp(7f),dp(7f),paint)
        small.textSize=dp(10f)
        if(small.measureText(d.stampText)>stamp.width()-dp(8f))small.textSize*=(stamp.width()-dp(8f))/small.measureText(d.stampText)
        canvas.drawText(d.stampText,stamp.centerX()-small.measureText(d.stampText)/2f,
            stamp.centerY()-(small.fontMetrics.ascent+small.fontMetrics.descent)/2f,small)
    }
}
