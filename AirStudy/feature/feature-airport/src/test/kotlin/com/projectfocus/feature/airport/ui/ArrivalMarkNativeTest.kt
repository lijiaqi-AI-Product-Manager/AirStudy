package com.projectfocus.feature.airport.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.feature.airport.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrivalMarkNativeTest {
    @Test fun arrivalMarkRendersAsAScalableProductAsset() {
        val context=ContextThemeWrapper(ApplicationProvider.getApplicationContext(),UiR.style.Theme_Pf)
        val mark=checkNotNull(ContextCompat.getDrawable(context,R.drawable.pf_airport_arrived_stamp))
        assertTrue(mark is android.graphics.drawable.VectorDrawable)
        val bitmap=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
        try {
            mark.setBounds(0,0,300,300)
            mark.draw(Canvas(bitmap))
            assertEquals(0,bitmap.getPixel(0,0))
            assertTrue((0 until 300).any { bitmap.getPixel(it,150) ushr 24 != 0 })
            val target=File("build/reports/airport-mark/arrival-mark.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
