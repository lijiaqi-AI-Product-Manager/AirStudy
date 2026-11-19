package com.projectfocus.app.freeflight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.core.ui.scene.ScenePickerOption
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.booking.seat.SeatSceneChoicesHost
import com.projectfocus.feature.booking.seat.SeatSceneEditorSession
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.core.ui.R as CoreUiR
import com.projectfocus.feature.booking.R as BookingR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun Context.ffDp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

/** Existing source-shaped airframe + independent real catalog/editor. No BookingDraft or history writes. */
internal class FreeFlightSeatSurface(
    private val activity: MainActivity,
    private val scenes: SeatSceneChoicesHost,
    night: Boolean,
    initial: FreeFlightSeatChoice?,
    private val onChosen: (FreeFlightSeatChoice) -> Unit,
    private val onConfirmed: (FreeFlightSeatChoice) -> Unit,
    sceneArguments: Bundle = Bundle().apply { putBoolean("freeFlightPreparation", true) },
) : FrameLayout(activity) {
    val map = SeatSelectionMapView(activity).apply { id = R.id.pf_ff_seat_map;setLightCabin(!night) }
    val confirm = MaterialButton(activity).apply {
        id = R.id.pf_ff_seat_confirm;setText(BookingR.string.pf_seat_confirm_selection);setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = ResourcesCompat.getFont(activity, CoreUiR.font.harmonyos_sans_sc_medium);isAllCaps = false
        insetTop = 0;insetBottom = 0;cornerRadius = (28f * resources.displayMetrics.density).toInt();strokeWidth = 0;elevation = 15f * resources.displayMetrics.density
        backgroundTintList = null;SkySurfaces.action(this);setTextColor(Color.WHITE);SkyPressFeedback.install(this)
    }
    private val arguments = Bundle(sceneArguments)
    private var selected: FreeFlightSeatChoice? = initial
    private var dialog: Dialog? = null
    private var editor: SeatSceneEditorSession? = null
    private var confirming = false
    private var lastTap = 0L
    private var exit: ValueAnimator? = null
    private var entry = true
    private var safeInsets = Insets.NONE
    init {
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val density = resources.displayMetrics.density
        addView(confirm, LayoutParams((300f * density).toInt(), (56f * density).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (40f * density).toInt();leftMargin = (16f * density).toInt();rightMargin = (16f * density).toInt()
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applySafeInsets(insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            insets
        }
        selected?.let { map.select(it.seat, it.scene) };updateConfirm()
        map.onSeatTapped = { seat, anchor ->
            val now = System.currentTimeMillis()
            if (!confirming && now - lastTap >= 500L) {
                lastTap = now
                anchor.startAnimation(ScaleAnimation(1f, .9f, 1f, .9f, 1, .5f, 1, .5f).apply { duration = 100L })
                anchor.postDelayed({ anchor.startAnimation(ScaleAnimation(.9f, 1f, .9f, 1f, 1, .5f, 1, .5f).apply { duration = 100L;startOffset = 100L }) }, 100L)
                showScene(seat.trimStart('0'), anchor)
            }
        }
        confirm.setOnClickListener {
            val value = selected
            if (value != null && !confirming && currentChoices()?.any { it.optionId == value.scene.optionId } == true) {
                confirming = true;confirm.isEnabled = false;confirm.isClickable = false;map.interactionEnabled = false
                dialog?.dismiss();confirm.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(180L).setInterpolator(DecelerateInterpolator()).withEndAction {
                    if (confirming) { confirm.visibility = GONE;exitShip(value) }
                }.start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let {
            applySafeInsets(it.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
        }
        ViewCompat.requestApplyInsets(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySafeInsets(safeInsets)
    }

    private fun applySafeInsets(safe: Insets) {
        safeInsets = safe;map.applySafeInsets(safe)
        val available = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - safe.left - safe.right - activity.ffDp(32f)
        val params = confirm.layoutParams as? LayoutParams ?: return
        val targetWidth = min(activity.ffDp(300f), available.coerceAtLeast(0))
        val bottom = activity.ffDp(40f) + safe.bottom
        val left = activity.ffDp(16f) + safe.left;val right = activity.ffDp(16f) + safe.right
        if (params.width != targetWidth || params.bottomMargin != bottom || params.leftMargin != left || params.rightMargin != right) {
            params.width = targetWidth;params.bottomMargin = bottom;params.leftMargin = left;params.rightMargin = right
            confirm.layoutParams = params
        }
    }

    fun enter() {
        map.alpha = 0f;map.animate().alpha(1f).setDuration(250L).setInterpolator(DecelerateInterpolator()).start()
        val ship = map.findViewById<View>(BookingR.id.pf_seat_spaceshipContainer)
        ship.translationY = resources.displayMetrics.heightPixels.toFloat()
        ship.animate().translationY(0f).setDuration(600L).setInterpolator(DecelerateInterpolator()).withEndAction { entry = false }.start()
    }

    private fun currentChoices(): List<SeatSceneChoice>? = (scenes.seatSceneChoices(arguments) as? SeatSceneChoices.Ready)?.values
        ?.filter { it.optionId.isNotBlank() }

    private fun showScene(seat: String, anchor: View) {
        val choices = currentChoices()
        if (choices == null) { Toast.makeText(activity, BookingR.string.seat_selection_scenes_unavailable, Toast.LENGTH_SHORT).show();return }
        dialog?.dismiss();editor?.close();val session = scenes.createSeatSceneEditor(arguments);editor = session
        fun options(): List<ScenePickerOption> = currentChoices().orEmpty().map { ScenePickerOption(it.optionId, it.title, it.iconRes, it.color) }
        val created = AnchoredScenePickerDialog(
            context = activity, anchor = anchor, seatCaption = activity.getString(BookingR.string.pf_seat_scene_seat_format, seat),
            options = options(),
            onSelected = { option ->
                if (!confirming) currentChoices()?.firstOrNull { it.optionId == option.id }?.let {
                    selected = FreeFlightSeatChoice(seat, it);map.select(seat, it);updateConfirm();onChosen(checkNotNull(selected))
                }
            },
            onAdd = session?.let { owned -> { changed: () -> Unit -> owned.add(changed) } },
            onRemove = session?.let { owned -> { option: ScenePickerOption, changed: () -> Unit -> owned.remove(option.id, changed) } },
            reloadOptions = if (session == null) null else ::options,
        )
        created.setOnDismissListener { session?.close();if (editor === session) editor = null;if (dialog === created) dialog = null }
        dialog = created;created.show()
    }

    private fun updateConfirm() { confirm.visibility = if (selected == null) GONE else VISIBLE;confirm.isEnabled = !confirming }

    /** Reused x4.p travel law: scroll to the tail, then fly the ship beyond the full window. */
    private fun exitShip(value: FreeFlightSeatChoice) {
        val ship = map.findViewById<View>(BookingR.id.pf_seat_spaceshipContainer)
        val scroll = ship.parent as? ScrollView
        val rootHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val initial = ship.translationY;val target = -(ship.height + rootHeight).toFloat()
        val distance = abs(target - initial);val direction = if (target >= initial) 1f else -1f
        val scrollY = scroll?.scrollY ?: 0;val remaining = max(0, (ship.height - (scroll?.height ?: ship.height)) - scrollY)
        val total = remaining + distance
        val duration = if (total <= 2.85f * 650f) max(1L, Math.round(sqrt(2f * total * 650f / 5.7f)).toLong())
            else 650L + max(1L, Math.round((total - 2.85f * 650f) / 5.7f).toLong())
        val fadeDelay = Math.round(.6f * duration).toLong();val fadeDuration = max(1L, Math.round(.4f * duration).toLong())
        postDelayed({ if (confirming) map.animate().alpha(0f).setDuration(fadeDuration).start() }, fadeDelay)
        exit = ValueAnimator.ofFloat(0f, duration.toFloat()).apply {
            this.duration = duration;interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedValue as Float
                val travel = min(total, if (time <= 650f) (2.85f / 650f) * time * time else (time - 650f) * 5.7f + 2.85f * 650f)
                scroll?.scrollTo(0, scrollY + Math.round(min(remaining.toFloat(), travel)))
                ship.translationY = initial + direction * min(distance, max(0f, travel - remaining))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled && confirming) onConfirmed(value) }
            });start()
        }
    }

    fun release() {
        confirming = false;exit?.cancel();dialog?.dismiss();editor?.close();dialog = null;editor = null
        map.onSeatTapped = null;map.onSeatSelected = null;map.animate().cancel();confirm.animate().cancel()
        map.findViewById<View>(BookingR.id.pf_seat_spaceshipContainer).animate().cancel()
    }
}
