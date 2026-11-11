package com.projectfocus.app.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkyBackdropView
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.feature.completion.pass.BoardingPassBinder
import com.projectfocus.feature.completion.pass.BoardingPassData
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.completion.R as PassR

/** The paper and its printed values survive inspection and the separate boarding confirmation. */
internal class OrdinaryBoardingPassSurface(
    context: Context,
    state: OrdinaryPreparationState,
    private val night: Boolean,
    private val onCheckIn: () -> Unit,
    private val onInspected: () -> Unit,
    onPrepare: () -> Unit,
) : FrameLayout(context) {
    private val paperContext = android.view.ContextThemeWrapper(context, R.style.Theme_ProjectFocus).apply {
        applyOverrideConfiguration(Configuration(context.resources.configuration).apply { fontScale = 1f })
    }
    val paper: FrameLayout = FrameLayout(paperContext).apply {
        val content = LayoutInflater.from(paperContext).inflate(PassR.layout.boarding_pass_layout, this, false) as ViewGroup
        id = content.id
        while (content.childCount > 0) {
            val child = content.getChildAt(0)
            val params = child.layoutParams
            content.removeViewAt(0)
            addView(child, params)
        }
    }
    val artwork = BoardingSequenceView(context, night).apply { id = R.id.ordinaryBoardingArtwork }
    val checkIn = button(R.string.ordinary_check_in).apply { id = R.id.ordinaryCheckIn; isEnabled = false }
    val prepare = button(R.string.ordinary_prepare_boarding).apply { id = R.id.ordinaryPrepareBoarding }
    private val instruction = label().apply {
        setText(PassR.string.boarding_pass_instruction_swipe)
        textSize = 18f
        visibility = GONE
    }
    private val blockingStatus = label().apply { id = R.id.ordinaryBlockingStatus; textSize = 14f }
    private val modePanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40f)
            addView(label().apply { setText(R.string.ordinary_focus_mode); textSize = 16f },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(blockingStatus, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(prepare, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56f)).apply { topMargin = dp(20f) })
    }
    private val ambience = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
    private val backdrop = SkyBackdropView(ambience)
    val feed = BoardingFeedOverlay(context, night).apply { id = R.id.ordinaryBoardingFeed }
    private val printStatus = label().apply {
        id = R.id.ordinaryPrintStatus
        setText(R.string.sky_boarding_preparing)
        textSize = 14f
        setTextColor(ambience.getColor(UiR.color.sky_ink_secondary))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var safeInsets = Insets.NONE
    private var inspection = false
    private var preparing = false
    private var released = false
    private var completed = false
    private var downX = 0f
    private var dragging = false
    private var progress = 0f
    private var tearAnimation: ValueAnimator? = null
    private val finished = Runnable { if (!released && completed && !preparing) onInspected() }
    private val printing = BoardingPrintController(this, paper, feed, checkIn) { phase ->
        if (!released && !inspection && !preparing) {
            printStatus.setText(when (phase) {
                BoardingPrintController.Phase.WAITING -> R.string.sky_boarding_preparing
                BoardingPrintController.Phase.PRINTING -> R.string.sky_boarding_printing
                BoardingPrintController.Phase.COMPLETE -> R.string.sky_boarding_ready
                BoardingPrintController.Phase.RELEASED -> R.string.sky_boarding_preparing
            })
        }
    }

    init {
        id = R.id.ordinaryBoarding
        isClickable = true
        isFocusable = true
        clipChildren = false
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applySafeInsets(insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            insets
        }
        val route = requireNotNull(state.route)
        val time = requireNotNull(state.boardingAt)
        val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val data = BoardingPassData(route.departure.code, OrdinaryFlightNumber.city(route.departure), clock.format(time),
            route.destination.code, OrdinaryFlightNumber.city(route.destination), clock.format(time.plusMinutes(route.durationMinutes.toLong())),
            requireNotNull(route.flightNumber), requireNotNull(route.seat), time.toLocalDate().toString(),
            String.format(Locale.ROOT, "%.1f km", route.distanceKm), "", route.durationMinutes, 0, false)
        BoardingPassBinder(paper).bind(data)
        paper.contentDescription = context.getString(R.string.sky_boarding_accessible_summary,
            data.departureCity, data.departureCode, data.arrivalCity, data.arrivalCode,
            data.flightNo, data.seat, data.date, data.boardingTime, data.arrivalTime)
        ViewCompat.setScreenReaderFocusable(paper, true)
        paper.findViewById<TextView>(PassR.id.tv_departure_city).text = data.departureCity
        paper.findViewById<TextView>(PassR.id.tv_destination_city).text = data.arrivalCity
        val primary = if (night) Color.WHITE else Color.BLACK
        val secondary = if (night) Color.argb(204, 255, 255, 255) else 0xFF444444.toInt()
        for (id in listOf(PassR.id.tv_departure, PassR.id.tv_destination, PassR.id.tv_flight_code,
                PassR.id.tv_boarding_time, PassR.id.tv_date, PassR.id.tv_passenger_name, PassR.id.tv_seat))
            paper.findViewById<TextView>(id).setTextColor(primary)
        for (id in listOf(PassR.id.tv_departure_city, PassR.id.tv_destination_city))
            paper.findViewById<TextView>(id).setTextColor(secondary)
        paper.addView(artwork, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        artwork.onFirstFrame = { if (!released && !inspection && !preparing) printing.begin() }
        addView(paper, LayoutParams(dp(300f), dp(400f)))
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(printStatus, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(checkIn, LayoutParams(dp(240f), dp(56f)))
        addView(instruction, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(modePanel, LayoutParams(dp(300f), LayoutParams.WRAP_CONTENT))
        checkIn.setOnClickListener { if (!released && !inspection && printing.isReady && checkIn.isEnabled) onCheckIn() }
        prepare.setOnClickListener { if (!released && preparing && prepare.isEnabled) onPrepare() }
        installInspection()
    }

    fun inspect(night: Boolean) {
        if (released || inspection || preparing) return
        inspection = true
        printing.showStatic(showAction = false)
        backdrop.visibility = GONE
        printStatus.visibility = GONE
        setBackgroundColor(if (night) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255))
        checkIn.visibility = GONE
        instruction.visibility = VISIBLE
        paper.findViewById<View>(PassR.id.tear_touch_band).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.ordinary_complete_inspection)
        }
        positionViews()
    }

    fun showPreparation() {
        if (released || preparing) return
        inspect(night)
        preparing = true
        completed = true
        dragging = false
        instruction.visibility = GONE
        artwork.showProgress(1f)
        modePanel.visibility = VISIBLE
        paper.findViewById<View>(PassR.id.tear_touch_band).apply {
            isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        positionViews()
    }

    fun updateBlocking(enabled: Boolean, apps: Int) {
        blockingStatus.text = if (enabled) context.getString(R.string.ordinary_focus_apps, apps)
            else context.getString(R.string.ordinary_focus_disabled)
        positionViews()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installInspection() {
        val band = paper.findViewById<View>(PassR.id.tear_touch_band)
        band.setOnClickListener { finishTear() }
        band.isClickable = false
        band.isFocusable = false
        band.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val listener = OnTouchListener { _, event ->
            if (!inspection || completed || preparing || released || artwork.renderedFrame < 0) false else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tearAnimation?.cancel()
                    downX = event.rawX
                    dragging = true
                    progress = 0f
                    artwork.showProgress(0f)
                    instruction.visibility = INVISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val distance = ((event.rawX - downX) / 300f).coerceIn(0f, 1f)
                        progress = distance * .5f
                        artwork.showProgress(progress)
                        if (distance >= 1f) finishTear()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    resetTear()
                    true
                }
                else -> true
            }
        }
        band.setOnTouchListener(listener)
        setOnTouchListener(listener)
    }

    private fun resetTear() {
        if (completed || released) return
        animateTear(progress, 0f, 300L) { if (!released && !completed) instruction.visibility = VISIBLE }
    }

    private fun finishTear() {
        if (!inspection || completed || preparing || released || artwork.renderedFrame < 0) return
        completed = true
        dragging = false
        instruction.visibility = GONE
        val from = progress.coerceAtLeast(.5f)
        animateTear(from, 1f, if (from >= .86f) 280L else ((1f - from) / .5f * 450L).toLong().coerceAtLeast(200L)) {
            artwork.afterFinalFrame { if (!released) postDelayed(finished, 50L) }
        }
    }

    private fun animateTear(from: Float, to: Float, duration: Long, after: () -> Unit) {
        tearAnimation?.cancel()
        tearAnimation = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            var cancelled = false
            addUpdateListener { if (!released) { progress = it.animatedValue as Float; artwork.showProgress(progress) } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled && !released) after() }
            })
            start()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        printing.onHostVisibilityChanged(visibility == VISIBLE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let {
            applySafeInsets(it.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun applySafeInsets(value: Insets) {
        safeInsets = value
        positionViews()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionViews()
    }

    private fun positionViews() {
        if (width <= 0 || height <= 0 || released) return
        val availableWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(dp(96f)).toFloat()
        val availableHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(dp(96f)).toFloat()
        val left = safeInsets.left.toFloat()
        val top = safeInsets.top.toFloat()
        val margin = dp(16f).toFloat()
        val gap = dp(24f).toFloat()
        val compact = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && availableHeight < dp(550f)
        val labelWidth = min(dp(300f).toFloat(), availableWidth - 2 * margin).coerceAtLeast(dp(48f).toFloat()).toInt()
        val sideWidth = min(dp(if (preparing) 280f else 160f).toFloat(),
            (availableWidth * .43f).coerceAtLeast(dp(112f).toFloat())).toInt()
        val column = if (compact) sideWidth else labelWidth
        measureLabel(printStatus, column)
        measureLabel(instruction, column)
        setWidth(modePanel, if (compact) sideWidth else labelWidth)
        modePanel.measure(MeasureSpec.makeMeasureSpec(modePanel.layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setWidth(checkIn, if (compact) sideWidth else min(dp(240f), labelWidth))
        val actionHeight = dp(56f).toFloat()
        val paperSpaceWidth = if (compact) availableWidth - 2 * margin - sideWidth - gap else availableWidth - 2 * margin
        val paperSpaceHeight = if (compact) availableHeight - 2 * margin else when {
            preparing -> availableHeight - 2 * margin - modePanel.measuredHeight - gap
            inspection -> availableHeight - 2 * margin - instruction.measuredHeight - gap
            else -> availableHeight - margin - gap - actionHeight - dp(32f) - printStatus.measuredHeight - dp(20f)
        }
        val scale = min(1f, min(paperSpaceWidth / dp(300f), paperSpaceHeight / dp(400f))).coerceAtLeast(.1f)
        paper.pivotX = 0f; paper.pivotY = 0f
        paper.scaleX = scale; paper.scaleY = scale
        val paperWidth = dp(300f) * scale
        val paperHeight = dp(400f) * scale
        if (compact) {
            paper.x = left + ((availableWidth - paperWidth - gap - sideWidth) / 2f).coerceAtLeast(margin)
            paper.y = top + (availableHeight - paperHeight) / 2f
            val sideX = paper.x + paperWidth + gap
            checkIn.x = sideX; checkIn.y = top + availableHeight / 2f + dp(12f)
            printStatus.x = sideX; printStatus.y = checkIn.y - printStatus.measuredHeight - dp(20f)
            instruction.x = sideX; instruction.y = top + (availableHeight - instruction.measuredHeight) / 2f
            modePanel.x = sideX; modePanel.y = top + (availableHeight - modePanel.measuredHeight) / 2f
        } else {
            paper.x = left + (availableWidth - paperWidth) / 2f
            checkIn.x = left + (availableWidth - checkIn.layoutParams.width) / 2f
            checkIn.y = top + availableHeight - gap - actionHeight
            printStatus.x = left + (availableWidth - printStatus.measuredWidth) / 2f
            instruction.x = left + (availableWidth - instruction.measuredWidth) / 2f
            modePanel.x = left + (availableWidth - modePanel.measuredWidth) / 2f
            paper.y = when {
                preparing -> top + (availableHeight - paperHeight - gap - modePanel.measuredHeight) / 2f
                inspection -> top + (availableHeight - paperHeight - gap - instruction.measuredHeight) / 2f
                else -> {
                    val regionHeight = checkIn.y - dp(32f) - (top + margin)
                    val groupHeight = printStatus.measuredHeight + dp(20f) + paperHeight
                    top + margin + ((regionHeight - groupHeight) / 2f).coerceAtLeast(0f) + printStatus.measuredHeight + dp(20f)
                }
            }
            printStatus.y = paper.y - printStatus.measuredHeight - dp(20f)
            instruction.y = paper.y + paperHeight + gap
            modePanel.y = paper.y + paperHeight + gap
        }
        printing.updatePlacement()
    }

    private fun setWidth(view: View, width: Int) {
        if (view.layoutParams.width != width) view.layoutParams = (view.layoutParams as LayoutParams).apply { this.width = width }
    }

    private fun measureLabel(view: TextView, width: Int) {
        setWidth(view, width)
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    fun release() {
        released = true
        removeCallbacks(finished)
        tearAnimation?.cancel()
        printing.release()
        artwork.release()
        paper.animate().cancel()
        checkIn.setOnClickListener(null)
        prepare.setOnClickListener(null)
        setOnTouchListener(null)
        paper.findViewById<View>(PassR.id.tear_touch_band).apply { setOnTouchListener(null); setOnClickListener(null) }
    }

    private fun button(text: Int) = MaterialButton(context).apply {
        setText(text); isAllCaps = false; cornerRadius = dp(28f)
        backgroundTintList = null
        setTextColor(Color.WHITE)
        textSize = 17f
        SkySurfaces.action(this)
        SkyPressFeedback.install(this)
        typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    }
    private fun label() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(if (night) Color.WHITE else Color.BLACK)
        typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    }
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
