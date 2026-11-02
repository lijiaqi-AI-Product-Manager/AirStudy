package com.projectfocus.feature.record.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.ui.RecordCardView
import com.projectfocus.service.focus.history.FlightRecordKey
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordSharePreview(
    private val context: Context,
    private val content: ViewGroup,
    private val scope: CoroutineScope,
    private val canShow: () -> Boolean,
    private val visibilityChanged: (Boolean) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Entry(
        val account: HistoryAccount,
        val identity: TokenStore.Snapshot,
        val item: RecordItem,
        val overlay: FrameLayout,
        val cardBitmap: Bitmap,
        val backgroundBitmap: Bitmap,
        val accessibility: List<Pair<View, Int>>,
    )
    private var entry: Entry? = null
    private var openJob: Job? = null
    private var shareJob: Job? = null
    private var revision = 0L

    fun open(card: RecordCardView, item: RecordItem, account: HistoryAccount) {
        close()
        val attempt = ++revision
        val expected = TokenStore.snapshot()
        if (!account.matches(expected)) return
        openJob = scope.launch {
            while (isActive && attempt == revision && canShow()) {
                try {
                    when (TokenStore.tryWithSnapshotLease(expected) {
                        if (attempt == revision && canShow() && card.isAttachedToWindow && card.width > 0 && card.height > 0 &&
                            content.width > 0 && content.height > 0) show(card, item, account, expected)
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> return@launch
                        TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    close()
                    if (account.isCurrent() && canShow()) Toast.makeText(context, R.string.record_share_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
        }
    }

    fun keepFor(account: HistoryAccount?, keys: Set<FlightRecordKey>) {
        val active = entry ?: return
        if (active.account != account || active.item.key !in keys || !active.account.isCurrent()) close()
    }

    private fun show(card: RecordCardView, item: RecordItem, account: HistoryAccount, expected: TokenStore.Snapshot) {
        val background = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        val bitmap: Bitmap
        try {
            content.draw(Canvas(background))
            bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            try { card.draw(Canvas(bitmap)) } catch (error: Exception) { bitmap.recycle(); throw error }
        } catch (error: Exception) {
            background.recycle()
            throw error
        }
        val overlay = FrameLayout(context).apply {
            id = R.id.pf_record_page_share_preview
            isClickable = true
            isFocusableInTouchMode = true
            contentDescription = context.getString(R.string.pf_record_page_share_preview_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            alpha = 0f
        }
        val backgroundView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.MIRROR))
            } else setColorFilter(Color.argb(120, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        }
        overlay.addView(backgroundView, FrameLayout.LayoutParams(-1, -1))
        val rootLocation = IntArray(2).also(content::getLocationInWindow)
        val cardLocation = IntArray(2).also(card::getLocationInWindow)
        val left = cardLocation[0] - rootLocation[0]
        val top = cardLocation[1] - rootLocation[1]
        val image = ImageView(context).apply {
            id = R.id.pf_record_page_share_card_image
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            contentDescription = item.spokenSummary
            setOnClickListener { }
        }
        overlay.addView(image, FrameLayout.LayoutParams(bitmap.width, bitmap.height).apply {
            leftMargin = left
            topMargin = top
        })
        val share = ExtendedFloatingActionButton(context).apply {
            id = R.id.pf_record_page_share_button
            setText(R.string.pf_record_page_share_card)
            icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_share)
            elevation = 0f
            translationZ = 0f
            stateListAnimator = null
        }
        overlay.addView(share, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = top + bitmap.height + dp(8)
        })
        val accessibility = (0 until content.childCount).map { index ->
            content.getChildAt(index).let { it to it.importantForAccessibility }
        }
        accessibility.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val next = Entry(account, expected, item, overlay, bitmap, background, accessibility)
        entry = next
        overlay.setOnClickListener { close() }
        share.setOnClickListener { if (entry === next && shareJob?.isActive != true) share(next, share) }
        content.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        share.post {
            if (entry !== next) return@post
            val params = share.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = ((content.width - share.width) / 2).coerceAtLeast(0)
            params.topMargin = params.topMargin.coerceAtMost((content.height - share.height - dp(16)).coerceAtLeast(0))
            share.layoutParams = params
        }
        overlay.requestFocus()
        overlay.animate().alpha(1f).setDuration(180).start()
        image.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).start()
        visibilityChanged(true)
    }

    private fun share(active: Entry, button: View) {
        shareJob = scope.launch {
            var copy: Bitmap? = null
            var file: File? = null
            var launched = false
            try {
                while (isActive && entry === active && canShow()) {
                    when (val capture = TokenStore.tryWithSnapshotLease(active.identity) {
                        if (entry !== active || !canShow()) null else {
                            button.isEnabled = false
                            active.cardBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> { copy = capture.value; break }
                        TokenStore.ImmediateSnapshot.Obsolete -> { close(); return@launch }
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                }
                val captured = copy ?: return@launch
                withContext(io) {
                    val directory = File(context.cacheDir, "share")
                    check(directory.isDirectory || directory.mkdirs())
                    val target = File(directory, "record-${UUID.randomUUID()}.png")
                    file = target
                    target.outputStream().use { check(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
                if (entry !== active || !canShow()) return@launch
                val uri = ShareIntentBuilder.uriFor(context, requireNotNull(file))
                val send = ShareIntentBuilder.build(uri, "AirStudy\n${active.item.spokenSummary}",
                    "AirStudy", active.item.spokenSummary)
                while (isActive && entry === active && canShow()) {
                    when (TokenStore.tryWithSnapshotLease(active.identity) {
                        if (entry === active && canShow()) {
                            context.startActivity(ShareIntentBuilder.chooser(send, context.getString(R.string.pf_record_page_share_card)))
                            launched = true
                        }
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> break
                        TokenStore.ImmediateSnapshot.Obsolete -> { close(); break }
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ActivityNotFoundException) {
                if (entry === active && active.account.isCurrent()) Toast.makeText(context, R.string.record_share_no_target, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                if (entry === active && active.account.isCurrent()) Toast.makeText(context, R.string.record_share_failed, Toast.LENGTH_SHORT).show()
            } finally {
                withContext(io + NonCancellable) {
                    copy?.recycle()
                    if (!launched) file?.delete()
                }
                if (entry === active && active.account.isCurrent()) button.isEnabled = true
            }
        }
    }

    fun close() {
        ++revision
        openJob?.cancel()
        openJob = null
        shareJob?.cancel()
        shareJob = null
        val active = entry
        entry = null
        if (active != null) {
            active.overlay.animate().cancel()
            for (index in 0 until active.overlay.childCount) {
                active.overlay.getChildAt(index).let { child ->
                    child.animate().cancel()
                    if (child is ImageView) child.setImageDrawable(null)
                }
            }
            content.removeView(active.overlay)
            active.accessibility.forEach { (view, previous) -> view.importantForAccessibility = previous }
            active.cardBitmap.recycle()
            active.backgroundBitmap.recycle()
        }
        visibilityChanged(false)
    }

    private fun HistoryAccount.matches(value: TokenStore.Snapshot) = generation == value.generation && scope == TokenStore.storageScope(value)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
