package com.projectfocus.app.booking

import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.google.android.material.button.MaterialButton
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.app.scene.AppSceneCatalog
import com.projectfocus.core.network.api.SceneContentApi
import com.projectfocus.core.network.content.SceneContentChecker
import com.projectfocus.core.network.di.KotlinSerializationConverterFactory
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.feature.booking.draft.BookingDraftStore
import com.projectfocus.feature.booking.seat.SeatSelectionFragment
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.feature.booking.seat.SeatSelectionStore
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.feature.scene.source.SourceSceneRecord
import com.projectfocus.feature.scene.ui.SourceSceneAddDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import retrofit2.Retrofit
import com.projectfocus.core.ui.R as CoreUiR
import com.projectfocus.feature.booking.R as BookingR
import com.projectfocus.feature.scene.R as SceneR

/** Real Main/Slot/store/dialog integration; the loopback peer controls only HTTP responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-notnight-mdpi")
class SeatSceneEditorNavigationTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var releaseResponse: CountDownLatch? = null

    @Before
    fun setUp() {
        TokenStore.clear()
        server = MockWebServer().apply { start() }
        client = OkHttpClient()
        val checker = SceneContentChecker(
            Retrofit.Builder().baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(KotlinSerializationConverterFactory.create(
                    Json { ignoreUnknownKeys = true; isLenient = true }, "application/json".toMediaType(),
                ))
                .build().create(SceneContentApi::class.java),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        onActivity { activity ->
            activity.sceneContentChecker = checker
            val store = SharedPreferencesSceneCatalogStore.from(activity)
            store.writeEnabled(AppSceneCatalog.SCOPE, SourceSceneCatalog.initialEnabledRecords())
            store.writeAllCreatedCustom(AppSceneCatalog.SCOPE, emptyList())
            SeatSelectionStore(activity).clearAll()
            BookingDraftStore(activity).clearAll()
            val nav = nav(activity)
            nav.navigate(R.id.action_permission_to_home)
            nav.navigate(R.id.seatSelectionFragment, Bundle().apply {
                putString(SeatSelectionFragment.ARG_FROM, "ZBAA")
                putString(SeatSelectionFragment.ARG_TO, "ZSPD")
                putString(SeatSelectionFragment.ARG_DRAFT_KEY, "r160-scene-editor-integration")
            })
        }
        await("production Slot view is attached") { activityValue { seatRoot(it) != null } }
    }

    @After
    fun tearDown() {
        releaseResponse?.countDown()
        scenario?.close()
        scenario = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        server.close()
        client.dispatcher.executorService.shutdown()
        TokenStore.clear()
    }

    @Test
    fun `builtin add and confirmed delete refresh the same card without selecting a seat or checking a name`() {
        val parent = openPicker()
        val add = openAdd(parent)
        clickOption(add, label("reading"))
        await("builtin save closes only the child") { !add.isShowing && records().size == 3 }
        assertThat(parent.isShowing).isTrue()
        assertThat(tagButtons(parent).map { it.text.toString() }).contains(label("reading"))
        assertThat(server.requestCount).isEqualTo(0)
        assertNoSeatSelected()

        onActivity { parent.findViewById<View>(CoreUiR.id.pf_scene_edit).performClick() }
        clickOption(parent, label("reading"))
        val cancel = latestConfirmation()
        onActivity { cancel.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() }
        idle()
        assertThat(records()).hasSize(3)
        clickOption(parent, label("reading"))
        val confirm = latestConfirmation()
        onActivity { confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        await("confirmed builtin removal refreshes parent") {
            records().size == 2 && tagButtons(parent).none { it.text.toString() == label("reading") }
        }
        assertThat(parent.isShowing).isTrue()
        assertThat(history()).isEmpty()
        assertNoSeatSelected()
    }

    @Test
    fun `real HTTP true saves Java trimmed custom name and selected source icon before actual seat selection`() {
        server.enqueue(MockResponse(body = """{"data":{"allowed":true}}"""))
        val parent = openPicker()
        val add = openAdd(parent)
        enterCustom(add, "  深度阅读  ", "reading")
        onActivity { add.findViewById<View>(SceneR.id.pf_scene_add_custom).performClick() }
        await("HTTP-approved custom scene is saved and parent refreshed") {
            !add.isShowing && tagButtons(parent).any { it.text.toString() == "深度阅读" }
        }
        val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/user/content/check")
        assertThat(Json.parseToJsonElement(request.body!!.utf8()))
            .isEqualTo(Json.parseToJsonElement("""{"text":"深度阅读"}"""))
        val actual = records().single { it.isCustom }
        assertThat(actual).isEqualTo(SourceSceneRecord(
            name = "深度阅读", iconKey = "reading", color = builtin("reading").color, isCustom = true,
        ))
        assertThat(actual.cloudId).isEqualTo(-1L)
        assertThat(actual.clientUuid).isNull()
        assertThat(history()).containsExactly(actual)
        assertNoSeatSelected()
        clickOption(parent, "深度阅读")
        await("the new persisted tag decorates the actual chair") {
            activityValue { seatMap(it).selectedSeat == "01A" && seatConfirm(it).isShown }
        }
        assertThat(parent.isShowing).isFalse()
    }

    @Test
    fun `false string true and HTTP failure never save while the same editor can retry a boolean true`() {
        val parent = openPicker()
        val add = openAdd(parent)
        enterCustom(add, "晨读", "reading")
        val denied = listOf(
            MockResponse(body = """{"data":{"allowed":false}}"""),
            MockResponse(body = """{"data":{"allowed":"true"}}"""),
            MockResponse(code = 503, body = "unavailable"),
        )
        for ((index, response) in denied.withIndex()) {
            server.enqueue(response)
            onActivity { add.findViewById<View>(SceneR.id.pf_scene_add_custom).performClick() }
            await("name-check failure ${index + 1} leaves a retry") {
                server.requestCount == index + 1 && activityValue {
                    add.findViewById<View>(SceneR.id.pf_scene_add_custom).isEnabled
                }
            }
            assertThat(add.isShowing).isTrue()
            assertThat(records()).hasSize(2)
            assertThat(history()).isEmpty()
            assertNoSeatSelected()
        }
        server.enqueue(MockResponse(body = """{"data":{"allowed":true}}"""))
        onActivity { add.findViewById<View>(SceneR.id.pf_scene_add_custom).performClick() }
        await("a later genuine Boolean true saves once") { !add.isShowing && records().size == 3 }
        assertThat(records().count { it.name == "晨读" }).isEqualTo(1)
        assertThat(history()).hasSize(1)
        assertThat(parent.isShowing).isTrue()
    }

    @Test
    fun `custom removal preserves history and reenable uses the real checker while already enabled history cannot resubmit`() {
        val custom = SourceSceneRecord("晨读", "reading", builtin("reading").color, isCustom = true)
        onActivity { activity ->
            SharedPreferencesSceneCatalogStore.from(activity).apply {
                writeEnabled(AppSceneCatalog.SCOPE, SourceSceneCatalog.initialEnabledRecords() + custom)
                writeAllCreatedCustom(AppSceneCatalog.SCOPE, listOf(custom))
            }
        }
        val parent = openPicker()
        val enabledHistory = openAdd(parent)
        val inactive = optionButton(enabledHistory, "晨读")
        assertThat(inactive.alpha).isEqualTo(.5f)
        onActivity { inactive.performClick() }
        idle()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(enabledHistory.isShowing).isTrue()
        onActivity { enabledHistory.cancel() }
        idle()

        onActivity { parent.findViewById<View>(CoreUiR.id.pf_scene_edit).performClick() }
        clickOption(parent, "晨读")
        val confirm = latestConfirmation()
        onActivity { confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        await("custom removed only from enabled") { records().size == 2 }
        assertThat(history()).containsExactly(custom)
        val availableHistory = openAdd(parent)
        assertThat(optionButton(availableHistory, "晨读").alpha).isEqualTo(1f)
        server.enqueue(MockResponse(body = """{"data":{"allowed":true}}"""))
        clickOption(availableHistory, "晨读")
        await("history reenabled and child dismissed") { !availableHistory.isShowing && records().size == 3 }
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath).isEqualTo("/user/content/check")
        assertThat(history()).containsExactly(custom)
        assertThat(parent.isShowing).isTrue()
        assertNoSeatSelected()
    }

    @Test
    fun `Activity recreation closes both real dialogs and cancels a pending HTTP approval before any write`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releaseResponse = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return MockResponse(body = """{"data":{"allowed":true}}""")
            }
        }
        val parent = openPicker()
        val add = openAdd(parent)
        enterCustom(add, "不应在关闭后保存", "study")
        onActivity { add.findViewById<View>(SceneR.id.pf_scene_add_custom).performClick() }
        await("real HTTP request is pending") { entered.count == 0L }
        onActivity { assertThat(add.findViewById<View>(SceneR.id.pf_scene_add_custom).isEnabled).isFalse() }
        scenario!!.recreate()
        idle()
        assertThat(parent.isShowing).isFalse()
        assertThat(add.isShowing).isFalse()
        release.countDown()
        await("cancelled transport is no longer running") { client.dispatcher.runningCallsCount() == 0 }
        idle()
        assertThat(records()).hasSize(2)
        assertThat(history()).isEmpty()
        assertNoSeatSelected()
    }

    @Test
    fun `five actual anonymous scenes keep the source bound and do not fabricate a paid profile`() {
        onActivity { SharedPreferencesSceneCatalogStore.from(it).writeEnabled(
            AppSceneCatalog.SCOPE, SourceSceneCatalog.builtins.take(5).map { item -> item.record() },
        ) }
        val parent = openPicker()
        onActivity { parent.findViewById<View>(CoreUiR.id.pf_scene_add).performClick() }
        await("real free bound is shown") {
            ShadowToast.getTextOfLatestToast()?.toString() == activityValue { it.getString(R.string.scene_editor_limit_reached) }
        }
        assertThat((ShadowDialog.getLatestDialog() as? SourceSceneAddDialog)?.isShowing == true).isFalse()
        assertThat(records()).hasSize(5)
        assertThat(server.requestCount).isEqualTo(0)
        assertNoSeatSelected()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `capture actual day scene editor window trees`() = writeEditorPreviews(night = false)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-night-mdpi")
    fun `capture actual night scene editor window trees`() = writeEditorPreviews(night = true)

    private fun writeEditorPreviews(night: Boolean) {
        val output = File("build/previews/source-scene-editor").apply { mkdirs() }
        val parent = openPicker()
        val add = openAdd(parent)
        writeWindowStack(output, "add-presets", night, listOf(parent, add))
        enterCustom(add, "深度阅读", "reading")
        writeWindowStack(output, "add-custom", night, listOf(parent, add))
        onActivity { add.cancel(); parent.findViewById<View>(CoreUiR.id.pf_scene_edit).performClick() }
        idle()
        writeWindowStack(output, "delete-mode", night, listOf(parent))
        assertThat(server.requestCount).isEqualTo(0)
    }


    private fun writeWindowStack(output: File, name: String, night: Boolean, dialogs: List<android.app.Dialog>) {
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        idle()
        onActivity { activity ->
            measureMain(activity)
            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val windows = JSONArray()
            try {
                val canvas = Canvas(bitmap)
                val main = activity.window.decorView
                val background = checkNotNull(main.background)
                val oldBounds = Rect(background.bounds)
                try { background.setBounds(0, 0, width, height); background.draw(canvas) }
                finally { background.bounds = oldBounds }
                main.draw(canvas)
                dialogs.forEach { dialog ->
                    assertThat(dialog.isShowing).isTrue()
                    val window = checkNotNull(dialog.window)
                    val attrs = window.attributes
                    val decor = window.decorView
                    fun spec(value: Int, bound: Int) = View.MeasureSpec.makeMeasureSpec(
                        if (value > 0) value else bound,
                        if (value == WindowManager.LayoutParams.WRAP_CONTENT) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY,
                    )
                    repeat(2) {
                        decor.measure(spec(attrs.width, width), spec(attrs.height, height))
                        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                        idle()
                    }
                    val horizontal = attrs.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                    val vertical = attrs.gravity and Gravity.VERTICAL_GRAVITY_MASK
                    val x = when (horizontal) {
                        Gravity.LEFT -> attrs.x
                        Gravity.RIGHT -> width - decor.measuredWidth - attrs.x
                        else -> (width - decor.measuredWidth) / 2 + attrs.x
                    }
                    val y = when (vertical) {
                        Gravity.TOP -> attrs.y
                        Gravity.BOTTOM -> height - decor.measuredHeight - attrs.y
                        else -> (height - decor.measuredHeight) / 2 + attrs.y
                    }
                    if (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                        canvas.drawColor(Color.argb((255 * attrs.dimAmount).toInt(), 0, 0, 0))
                    }
                    assertThat(decor.measuredWidth).isGreaterThan(0)
                    assertThat(decor.measuredHeight).isGreaterThan(0)
                    assertThat(x).isAtLeast(0)
                    assertThat(y).isAtLeast(0)
                    assertThat(x + decor.measuredWidth).isAtMost(width)
                    assertThat(y + decor.measuredHeight).isAtMost(height)
                    // Each real window gets its own native canvas. A shared canvas
                    // can retain native display-list state across these View roots.
                    val surface = Bitmap.createBitmap(decor.measuredWidth, decor.measuredHeight, Bitmap.Config.ARGB_8888)
                    try {
                        decor.draw(Canvas(surface))
                        canvas.drawBitmap(surface, x.toFloat(), y.toFloat(), null)
                    } finally { surface.recycle() }
                    val sourceCard = dialog.findViewById<View>(SceneR.id.pf_scene_add_card)
                    val cardBounds = sourceCard?.let {
                        Rect(0, 0, it.width, it.height).apply { (decor as ViewGroup).offsetDescendantRectToMyCoords(it, this) }
                    }
                    windows.put(JSONObject().put("class", dialog.javaClass.simpleName)
                        .put("runtimeWidth", attrs.width).put("runtimeHeight", attrs.height)
                        .put("runtimeGravity", attrs.gravity).put("runtimeX", attrs.x).put("runtimeY", attrs.y)
                        .put("measuredWidth", decor.measuredWidth).put("measuredHeight", decor.measuredHeight)
                        .put("drawX", x).put("drawY", y).put("flags", attrs.flags).put("dimAmount", attrs.dimAmount)
                        .put("decorAlpha", decor.alpha).put("decorTranslationX", decor.translationX).put("decorTranslationY", decor.translationY)
                        .put("sourceCardBoundsInDecor", cardBounds?.toShortString()))
                }
                assertThat(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    .isEqualTo(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                val suffix = if (night) "night" else "day"
                File(output, "$name-$suffix.png").outputStream().use {
                    assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                }
                File(output, "$name-$suffix.json").writeText(JSONObject()
                    .put("kind", "native_actual_main_and_dialog_view_drawing")
                    .put("source", "Real MainActivity/Slot/first two persisted source scenes; actual add/edit taps and production dialogs")
                    .put("limitation", "Composite of native View trees with runtime gravity/size/dim attributes; not PixelCopy, phone, IME or original-APK capture")
                    .put("viewportWidth", width).put("viewportHeight", height).put("windows", windows).toString(2))
            } finally { bitmap.recycle() }
        }
    }

    private fun openPicker(): AnchoredScenePickerDialog {
        onActivity { activity ->
            measureMain(activity)
            checkNotNull(seatRoot(activity)).findViewWithTag<TextView>("01A").performClick()
        }
        await("actual anchored card is open") { (ShadowDialog.getLatestDialog() as? AnchoredScenePickerDialog)?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog
    }

    private fun openAdd(parent: AnchoredScenePickerDialog): SourceSceneAddDialog {
        onActivity { parent.findViewById<View>(CoreUiR.id.pf_scene_add).performClick() }
        await("actual two-page add editor is open") { (ShadowDialog.getLatestDialog() as? SourceSceneAddDialog)?.isShowing == true }
        return ShadowDialog.getLatestDialog() as SourceSceneAddDialog
    }

    private fun enterCustom(dialog: SourceSceneAddDialog, name: String, iconKey: String) {
        onActivity {
            dialog.findViewById<View>(SceneR.id.pf_scene_go_custom).performClick()
            dialog.findViewById<EditText>(SceneR.id.pf_scene_custom_name).setText(name)
            descendants(dialog.findViewById(SceneR.id.pf_scene_icon_selector)).filterIsInstance<ImageView>()
                .single { it.contentDescription?.toString() == label(iconKey) }.performClick()
        }
        idle()
    }

    private fun optionButton(dialog: android.app.Dialog, label: String): MaterialButton = activityValue {
        descendants(dialog.window!!.decorView).filterIsInstance<MaterialButton>()
            .single { it.tag is String && it.text.toString() == label }
    }

    private fun clickOption(dialog: android.app.Dialog, label: String) {
        val button = optionButton(dialog, label)
        onActivity { assertThat(button.performClick()).isTrue() }
        idle()
    }

    private fun tagButtons(dialog: AnchoredScenePickerDialog): List<MaterialButton> = activityValue {
        descendants(dialog.findViewById(CoreUiR.id.pf_scene_card_root)).filterIsInstance<MaterialButton>().filter { it.tag is String }
    }

    private fun latestConfirmation(): AlertDialog = (ShadowDialog.getLatestDialog() as AlertDialog)
        .also { assertThat(it.isShowing).isTrue() }

    private fun assertNoSeatSelected() = onActivity {
        assertThat(seatMap(it).selectedSeat).isNull()
        assertThat(seatConfirm(it).visibility).isEqualTo(View.GONE)
    }

    private fun records(): List<SourceSceneRecord> = activityValue {
        SharedPreferencesSceneCatalogStore.from(it).readEnabled(AppSceneCatalog.SCOPE).records
    }
    private fun history(): List<SourceSceneRecord> = activityValue {
        SharedPreferencesSceneCatalogStore.from(it).readAllCreatedCustom(AppSceneCatalog.SCOPE).records
    }
    private fun builtin(key: String) = SourceSceneCatalog.builtins.single { it.iconKey == key }
    private fun label(key: String): String = activityValue { it.getString(builtin(key).labelRes) }
    private fun nav(activity: MainActivity) =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment).navController
    private fun seatRoot(activity: MainActivity): View? =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
            .childFragmentManager.primaryNavigationFragment?.view?.takeIf { it.findViewById<View>(BookingR.id.seatMap) != null }
    private fun seatMap(activity: MainActivity): SeatSelectionMapView = checkNotNull(seatRoot(activity)).findViewById(BookingR.id.seatMap)
    private fun seatConfirm(activity: MainActivity): MaterialButton = checkNotNull(seatRoot(activity)).findViewById(BookingR.id.btnConfirmSeat)

    private fun measureMain(activity: MainActivity) {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            idle()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun onActivity(block: (MainActivity) -> Unit) { scenario!!.onActivity(block) }
    @Suppress("UNCHECKED_CAST")
    private fun <T> activityValue(block: (MainActivity) -> T): T {
        var result: Any? = null
        onActivity { result = block(it) }
        return result as T
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        do {
            idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out: $description")
    }
}
