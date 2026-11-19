package com.projectfocus.app.freeflight

import android.app.Activity
import android.app.Application
import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.projectfocus.core.model.flight.PlannedAirport
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native original resources/real RecyclerView interaction; options are named layout fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeFlightLandingSurfaceTest {
    private val silent=object:FreeFlightRulerFeedback { override fun tickVolume()=0f;override fun hapticIntensity()=0 }
    @Test fun firstCardIsCentredWithoutSelectingAndOnlyTheHostAcknowledgementEnablesConfirm() {
        val activityController=Robolectric.buildActivity(Activity::class.java)
        val activity=activityController.get();activity.setTheme(com.projectfocus.core.ui.R.style.Theme_Pf)
        activityController.setup().visible()
        var selected:FreeFlightLandingOption?=null;var confirmations=0
        try {
            val surface=FreeFlightLandingSurface(activity,silent,false,"vertical","badge",{}, { selected=it }, { confirmations++ },{})
            val root=FrameLayout(activity).apply { setBackgroundColor(Color.rgb(246,245,250)) };root.addView(surface);activity.setContentView(root)
            layout(root)
            surface.loading();assertFalse(surface.confirm.isEnabled)
            val option=FreeFlightLandingOption(PlannedAirport(1,"TSN","Tianjin Binhai International Airport","Tianjin","CN",39.1244,117.346),77.5,6)
            surface.showOptions(listOf(option));surface.render(10,null,false);layout(root)
            assertNull(selected);assertFalse(surface.confirm.isEnabled)
            val card=checkNotNull(surface.cards.findViewHolderForAdapterPosition(0)).itemView
            card.performClick();assertEquals(option,selected);assertFalse(surface.confirm.isEnabled)
            surface.render(10,"TSN",false);layout(root)
            assertTrue(surface.confirm.isEnabled);surface.confirm.performClick();assertEquals(1,confirmations)
            capture(root,"source-landing-selection-day")
            surface.loading();assertFalse(surface.confirm.isEnabled)
            surface.failed();assertFalse(surface.confirm.isEnabled)
        } finally { activityController.pause().stop().destroy() }
    }
    @Test fun actualNightCardsAndRulerRetainSourceFontsAndSelectedContrast() {
        val activityController=Robolectric.buildActivity(Activity::class.java)
        val activity=activityController.get();activity.setTheme(com.projectfocus.core.ui.R.style.Theme_Pf)
        activityController.setup().visible()
        try {
            val surface=FreeFlightLandingSurface(activity,silent,true,"vertical","none",{}, {}, {},{})
            val root=FrameLayout(activity).apply { setBackgroundColor(Color.rgb(20,30,45));addView(surface) };activity.setContentView(root)
            surface.showOptions(listOf(FreeFlightLandingOption(PlannedAirport(1,"TSN","Tianjin Binhai","Tianjin","CN",39.1244,117.346),77.5,6)))
            surface.render(60,"TSN",false);layout(root)
            assertTrue(surface.confirm.isEnabled)
            capture(root,"source-landing-selection-night")
        } finally { activityController.pause().stop().destroy() }
    }
    private fun layout(view:View) {
        repeat(3) {
            view.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY))
            view.layout(0,0,432,960);shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS)
        }
    }
    private fun capture(view:View,name:String) {
        val bitmap=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file=File("build/reports/freeflight-landing-surface/$name.png").apply { parentFile.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
