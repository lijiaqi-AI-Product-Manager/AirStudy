package com.projectfocus.app.auth

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.LocaleList
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.projectfocus.app.R
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real production Views + frozen XML resources, not an Auth/Hilt Activity fixture.
 * Static final frames end the source layout transition; they do not verify the
 * original animation, Activity binding, IME/system bars, account or navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceAuthLayoutTest {
    private data class Screen(
        val width: Int, val height: Int, val smallest: Int = 390,
        val orientation: Int = Configuration.ORIENTATION_PORTRAIT,
    )
    private val phone = Screen(390, 844)

    private fun context(night: Boolean = false, screen: Screen = phone, dpi: Int = 160,
                        fontScale: Float = 1f, locale: Locale = Locale.SIMPLIFIED_CHINESE): Context {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(locale))
            screenWidthDp = screen.width
            screenHeightDp = screen.height
            smallestScreenWidthDp = screen.smallest
            orientation = screen.orientation
            densityDpi = dpi
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.pf_auth_source_theme)
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }

    private fun inflate(context: Context, id: Int): ViewGroup =
        LayoutInflater.from(context).inflate(id, null, false) as ViewGroup

    private fun finalLayout(root: View, width: Int, height: Int) {
        // The original 220ms fade/translate is a separate dynamic contract.
        // Unattached layout fixtures have no Choreographer window; end transient
        // animation explicitly instead of claiming an immediately drawn blank frame.
        nodes(root).forEach {
            it.clearAnimation()
            if (it is ViewGroup) it.layoutAnimation = null
        }
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }

    private fun bitmap(root: View, backdrop: Int): Bitmap =
        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(backdrop); root.draw(this) }
        }

    private fun resourcePath(context: Context, id: Int): String = TypedValue().let {
        context.resources.getValue(id, it, true)
        it.string?.toString().orEmpty()
    }

    private fun required(root: View, id: Int): View = checkNotNull(root.findViewById<View>(id))
    private fun edit(control: SourceAuthInputView): EditText = required(control, R.id.pf_auth_et_input) as EditText

    @Test fun phoneNativeFrames() {
        val output = File(System.getProperty("pf.auth.previewDir", "build/reports/r165-auth-ui")).apply {
            check(isDirectory || mkdirs())
        }
        val receipts = JSONArray()
        for (night in listOf(false, true)) {
            val context = context(night)
            assertEquals(1f, context.resources.displayMetrics.density, 0.001f)
            for ((name, layout) in listOf("login" to R.layout.pf_auth_login, "email" to R.layout.pf_auth_email_login)) {
                val root = inflate(context, layout)
                finalLayout(root, 390, 844)
                val background = context.getColor(R.color.pf_auth_background)
                val image = bitmap(root, background)
                val cover = required(root, R.id.pf_auth_promo_image).parent as View
                val coverBounds = Rect(0, 0, cover.width, cover.height)
                root.offsetDescendantRectToMyCoords(cover, coverBounds)
                assertEquals(background, image.getPixel(coverBounds.left, coverBounds.top))
                assertNotEquals(background, image.getPixel(coverBounds.centerX(), coverBounds.top + 20))
                val button = required(root, R.id.pf_auth_btn_login) as MaterialButton
                val bounds = Rect(0, 0, button.width, button.height)
                root.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue("source login button must be inside the baseline viewport: $bounds", bounds.top >= 0 && bounds.bottom <= 844)
                val foreground = button.currentTextColor
                var glyphPixels = 0
                for (y in bounds.centerY() - 12 until bounds.centerY() + 12) {
                    for (x in bounds.centerX() - 70 until bounds.centerX() + 70) {
                        val color = image.getPixel(x, y)
                        if (abs(Color.red(color) - Color.red(foreground)) < 30 &&
                            abs(Color.green(color) - Color.green(foreground)) < 30 &&
                            abs(Color.blue(color) - Color.blue(foreground)) < 30) glyphPixels++
                    }
                }
                assertTrue("native text pixels missing for $name/night=$night", glyphPixels > 10)
                val file = File(output, "$name-${if (night) "night" else "day"}-390.png")
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                receipts.put(JSONObject().put("file", file.name).put("width", image.width).put("height", image.height)
                    .put("sha256", sha(file.readBytes())).put("nativeTextPixels", glyphPixels))
                image.recycle()
            }
        }
        File(output, "render-receipt.json").writeText(JSONObject()
            .put("kind", "real_resource_and_production_view_native_static_frames")
            .put("renderer", "Robolectric NativeGraphics SDK35").put("locale", "zh-CN")
            .put("frames", receipts).put("activityHiltOrNavigationAcceptance", false)
            .put("sourcePixelOrAnimationAcceptance", false).toString(2))
        File(output, "README.txt").writeText(
            "Four real XML/production-View native draws at390x844 mdpi zh-CN.\n" +
                "Plain Application/scoped source theme fixture, not an Auth/Hilt Activity.\n" +
                "Layout transitions ended for a stable static frame. Original XML agreement text is empty; Activity spans/links are outside this fixture.\n" +
                "No reference screenshot/SSIM, IME, system-bar, original runtime, auth or animation acceptance.\n",
        )
    }

    @Test fun phoneSourceGeometry() {
        for (layout in listOf(R.layout.pf_auth_login, R.layout.pf_auth_email_login)) {
            val root = inflate(context(), layout)
            finalLayout(root, 390, 844)
            val hero = required(root, R.id.pf_auth_promo_image).parent as AuthCoverView
            val margin = hero.layoutParams as ViewGroup.MarginLayoutParams
            assertEquals(268, hero.height); assertEquals(358, hero.width)
            assertEquals(16, margin.topMargin); assertEquals(16, margin.leftMargin)
            assertTrue(hero.clipToOutline)
            val back = required(root, R.id.pf_auth_btn_back) as MaterialCardView
            assertEquals(48, back.width); assertEquals(48, back.height)
            assertEquals(root.context.getString(R.string.pf_ff_common_back), back.contentDescription)
            val foreground = (back.getChildAt(0) as android.widget.ImageView).imageTintList!!.defaultColor
            val background = back.cardBackgroundColor.defaultColor
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5)
            val backRect = Rect(0, 0, back.width, back.height)
            hero.offsetDescendantRectToMyCoords(back, backRect)
            val coverCopy = required(root, R.id.authSkyCoverCopy)
            assertTrue(coverCopy.top > backRect.bottom)
            val button = required(root, R.id.pf_auth_btn_login) as MaterialButton
            assertTrue(button.height >= 55); assertEquals(16f, button.textSize, 0.01f)
            assertEquals(20, button.cornerRadius)
            val form = button.parent as LinearLayout
            assertEquals(24, form.paddingLeft); assertEquals(24, form.paddingRight)
            assertEquals(32, form.paddingTop); assertEquals(40, form.paddingBottom)
            val checkbox = required(root, R.id.pf_auth_cb_agreement)
            assertEquals(48, checkbox.width); assertEquals(48, checkbox.height)
            val agreement = required(root, R.id.pf_auth_tv_agreement) as TextView
            assertEquals(13f, agreement.textSize, 0.01f)
            assertEquals("", agreement.text.toString()) // Runtime Activity binds the source span.
        }
        assertNotEquals(context().getColor(R.color.pf_auth_background), context(true).getColor(R.color.pf_auth_background))
    }

    @Test fun inputRawAndDefault() {
        val control = SourceAuthInputView(context())
        assertEquals(InputType.TYPE_NULL, edit(control).inputType)
        assertSame(edit(control).text, control.getText())
        val raw = " \tαBeta\u00a0 "
        control.getText().replace(0, control.getText().length, raw)
        assertEquals(raw, control.getText().toString())
        assertSame(edit(control).text, control.getText())
    }

    @Test fun typedInputsNoEye() {
        val root = inflate(context(), R.layout.pf_auth_email_login)
        finalLayout(root, 390, 844)
        val email = required(root, R.id.pf_auth_et_login_email) as SourceAuthInputView
        val password = required(root, R.id.pf_auth_et_login_password) as SourceAuthInputView
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, edit(email).inputType)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, edit(password).inputType)
        for (control in listOf(email, password)) {
            val raw = "  source fixture\u00a0 "
            edit(control).setText(raw)
            assertEquals(raw, control.getText().toString())
            assertSame(edit(control).text, control.getText())
            assertTrue(edit(control).height >= 56)
            assertEquals(16f, edit(control).textSize, 0.01f)
            assertEquals(16, edit(control).paddingLeft); assertEquals(16, edit(control).paddingRight)
            assertEquals(0, nodes(control).filterIsInstance<ImageView>().size)
            assertEquals(1, nodes(control).filterIsInstance<EditText>().size)
            val label = required(control, R.id.pf_auth_tv_label) as TextView
            assertEquals(14f, label.textSize, 0.01f)
            assertEquals(8, (label.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
        }
    }

    @Test fun sourceFontAndShapeRefs() {
        val context = context()
        val parser = context.resources.getXml(R.layout.pf_auth_view_custom_input)
        var mediumRefs = 0
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name in listOf("TextView", "EditText")) {
                    val id = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "fontFamily", 0)
                    assertTrue(resourcePath(context, id).endsWith("/harmonyos_sans_sc_medium.ttf")); mediumRefs++
                }
                parser.next()
            }
        } finally { parser.close() }
        assertEquals(2, mediumRefs)
        for ((style, file) in listOf(R.style.pf_auth_text_appearance_metric to "harmonyos_sans_sc_black.ttf",
            R.style.pf_auth_text_appearance_caption to "harmonyos_sans_sc_light.ttf")) {
            val attributes = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.fontFamily))
            try { assertTrue(resourcePath(context, attributes.getResourceId(0, 0)).endsWith("/$file")) }
            finally { attributes.recycle() }
        }
        val box = context.getDrawable(R.drawable.pf_auth_bg_input_box) as StateListDrawable
        assertEquals(18f, (box.current as GradientDrawable).cornerRadius, 0.01f)
        val idle = box.current
        box.state = intArrayOf(android.R.attr.state_focused)
        assertNotSame(idle, box.current)
        val cursor = context.getDrawable(R.drawable.pf_auth_text_cursor_black)!!
        assertEquals(2, cursor.intrinsicWidth)
    }

    @Test fun checkboxNativeState() {
        for (dpi in listOf(160, 176, 240, 420)) {
            val context = context(dpi = dpi)
            val checkbox = SourceAuthCheckboxView(context)
            assertFalse(checkbox.isChecked()); assertNull(checkbox.drawable)
            assertEquals(0, checkbox.paddingLeft)
            checkbox.setChecked(true)
            val expected = (context.resources.displayMetrics.density * 14f + .5f).toInt() +
                (context.resources.displayMetrics.density * 3f + .5f).toInt()
            assertTrue(checkbox.isChecked()); assertNotNull(checkbox.drawable)
            assertEquals(ImageView.ScaleType.CENTER_INSIDE, checkbox.scaleType)
            assertEquals(listOf(expected, expected, expected, expected),
                listOf(checkbox.paddingLeft, checkbox.paddingTop, checkbox.paddingRight, checkbox.paddingBottom))
            checkbox.setChecked(false)
            assertNull(checkbox.drawable); assertEquals(listOf(0, 0, 0, 0),
                listOf(checkbox.paddingLeft, checkbox.paddingTop, checkbox.paddingRight, checkbox.paddingBottom))
        }
        val checkbox = SourceAuthCheckboxView(context())
        finalLayout(checkbox, 48, 48)
        val off = bitmap(checkbox, Color.MAGENTA)
        assertEquals(Color.MAGENTA, off.getPixel(24, 18))
        checkbox.setChecked(true)
        val checked = bitmap(checkbox, Color.MAGENTA)
        val color = checkbox.context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
        try { assertEquals(color.getColor(0, Color.TRANSPARENT), checked.getPixel(24, 18)) }
        finally { color.recycle() }
        assertNotEquals(off.getPixel(24, 18), checked.getPixel(24, 18))
        assertEquals(Color.MAGENTA, checked.getPixel(4, 24))
        val node = checkbox.createAccessibilityNodeInfo()
        assertTrue(node.isCheckable && node.isChecked)
        off.recycle(); checked.recycle()
    }

    @Test fun explicitCheckboxToggle() {
        val checkbox = SourceAuthCheckboxView(context())
        checkbox.performClick()
        assertFalse("Standalone adapter does not silently consume an Activity toggle", checkbox.isChecked())
        var clicks = 0
        checkbox.setOnClickListener { clicks++; checkbox.toggle() }
        assertTrue(checkbox.performClick()); assertTrue(checkbox.isChecked())
        assertTrue(checkbox.performClick()); assertFalse(checkbox.isChecked())
        assertEquals(2, clicks)
    }

    @Test fun qualifierSelection() {
        data class Variant(val screen: Screen, val folder: String, val horizontal: Boolean, val hubButton: Int, val emailButton: Int)
        val variants = listOf(
            Variant(phone, "layout", false, 55, 55),
            Variant(Screen(844, 390, orientation = Configuration.ORIENTATION_LANDSCAPE), "layout-land", true, 52, 52),
            Variant(Screen(1024, 768, 600, Configuration.ORIENTATION_LANDSCAPE), "layout-sw600dp", true, 60, 56),
            Variant(Screen(768, 1024, 600), "layout-sw600dp-port", false, 55, 55),
        )
        for (variant in variants) for ((id, height) in listOf(R.layout.pf_auth_login to variant.hubButton, R.layout.pf_auth_email_login to variant.emailButton)) {
            val context = context(screen = variant.screen)
            val compiledPath = resourcePath(context, id)
            // aapt2 adds the implied API floor (e.g. sw600dp-v13). Match the
            // actual qualifiers independently of that generated version suffix.
            val compiledFolder = compiledPath.substringBeforeLast('/').substringAfterLast('/').replace(Regex("-v\\d+$"), "")
            assertEquals(compiledPath, variant.folder, compiledFolder)
            val root = inflate(context, id)
            finalLayout(root, variant.screen.width, variant.screen.height)
            if (variant.horizontal) assertEquals(LinearLayout.HORIZONTAL, (root as LinearLayout).orientation)
            else assertEquals("androidx.coordinatorlayout.widget.CoordinatorLayout", root.javaClass.name)
            assertTrue(required(root, R.id.pf_auth_btn_login).height >= height)
            assertEquals(1, nodes(root).filterIsInstance<SourceAuthCheckboxView>().size)
            assertEquals(if (id == R.layout.pf_auth_email_login) 2 else 0, nodes(root).filterIsInstance<SourceAuthInputView>().size)
        }
    }

    @Test fun narrowAndShortFormsKeepLargeTypeReadableAndActionsReachable() {
        val variants = listOf(Screen(320, 620, 320), Screen(640, 360, 360, Configuration.ORIENTATION_LANDSCAPE))
        val layouts = listOf("entry" to R.layout.pf_auth_login, "email" to R.layout.pf_auth_email_login,
            "register" to R.layout.pf_auth_register, "reset" to R.layout.pf_auth_forgot_password)
        val output = File("build/reports/ui-account").apply { check(isDirectory || mkdirs()) }
        for ((index, screen) in variants.withIndex()) for ((name, layout) in layouts) {
            val context = context(night = index == 1, screen = screen, fontScale = 2f, locale = Locale.forLanguageTag("ru-RU"))
            val root = inflate(context, layout)
            val agreement = root.findViewById<TextView>(R.id.pf_auth_tv_agreement)
            agreement?.text = context.getString(R.string.pf_auth_agreement_full_text_fmt,
                context.getString(R.string.pf_auth_terms_label), context.getString(R.string.pf_auth_privacy_label))
            finalLayout(root, screen.width, screen.height)

            val scroll = nodes(root).filterIsInstance<androidx.core.widget.NestedScrollView>().single()
            val back = required(root, R.id.pf_auth_btn_back)
            assertTrue(back.width >= 48 && back.height >= 48)
            assertEquals(context.getString(R.string.pf_ff_common_back), back.contentDescription)
            val checkbox = root.findViewById<View>(R.id.pf_auth_cb_agreement)
            if (checkbox != null) assertTrue(checkbox.width >= 48 && checkbox.height >= 48)
            val action = root.findViewById<View>(R.id.pf_auth_btn_login) ?: required(root, R.id.pf_auth_btn_register)
            val copy = root.findViewById<View>(R.id.authSkyCoverCopy)
            if (copy != null) {
                val cover = copy.parent as ViewGroup
                val offset = Rect(0, 0, back.width, back.height)
                cover.offsetDescendantRectToMyCoords(back, offset)
                assertTrue(copy.top > offset.bottom)
            }
            val frame = scroll.getChildAt(0) as ViewGroup
            for (label in nodes(frame).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) {
                val measured = checkNotNull(label.layout)
                assertTrue("${label.text} needs more than ${label.height}px", measured.height <=
                    label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                if (label !is EditText) {
                    assertTrue((0 until measured.lineCount).all { measured.getEllipsisCount(it) == 0 })
                    assertEquals(label.text.length, measured.getLineEnd(measured.lineCount - 1))
                }
            }
            for (field in nodes(frame).filterIsInstance<EditText>()) {
                assertTrue(field.height >= 56 && field.height - field.compoundPaddingTop - field.compoundPaddingBottom >= field.lineHeight)
            }
            val actionBounds = Rect(0, 0, action.width, action.height)
            frame.offsetDescendantRectToMyCoords(action, actionBounds)
            scroll.scrollTo(0, actionBounds.bottom - scroll.height + 12)
            assertTrue(actionBounds.bottom - scroll.scrollY <= scroll.height)
            assertTrue(actionBounds.top >= scroll.scrollY)
            val image = bitmap(root, context.getColor(R.color.pf_auth_background))
            try {
                File(output, "account-$name-${screen.width}-large.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
