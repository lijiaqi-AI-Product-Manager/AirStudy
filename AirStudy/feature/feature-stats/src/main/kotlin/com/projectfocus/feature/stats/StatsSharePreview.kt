package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.ui.sky.ArtworkPreviewOverlay
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.projectfocus.core.ui.R as UiR

internal class StatsSharePreview(
    private val context: Context,
    private val content: ViewGroup,
    private val scope: CoroutineScope,
    private val zone: ZoneId,
    private val currentState: () -> StatsUiState?,
    private val isCurrentQuery: (HistoryAccount,Long) -> Boolean,
    private val canShow: () -> Boolean,
    private val visibilityChanged: (Boolean) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Entry(
        val account: HistoryAccount,val identity: TokenStore.Snapshot,val queryRevision: Long,
        val spec: StatsShareSpec,val overlay: ArtworkPreviewOverlay,val bitmap: Bitmap,val background: Bitmap,
        val accessibility: List<Pair<View,Int>>,val focused: View?,
    )
    private var entry: Entry?=null
    private var opening: Job?=null
    private var sharing: Job?=null
    private var revision=0L

    fun open(state: StatsUiState) {
        close()
        val account=state.account ?: return
        val identity=TokenStore.snapshot()
        if(state.isLoading || currentState()!==state || !account.matches(identity) || !isCurrentQuery(account,state.queryRevision) || !canShow())return
        val capturedSpec=StatsShareSpec.from(context,state,zone)
        val attempt=++revision
        opening=scope.launch {
            while(isActive && revision==attempt && canShow()) {
                try {
                    when(TokenStore.tryWithSnapshotLease(identity) {
                        val latest=currentState()
                        if(revision==attempt && latest!=null && !latest.isLoading && latest.account==account &&
                            latest.queryRevision==state.queryRevision && isCurrentQuery(account,state.queryRevision) &&
                            StatsShareSpec.from(context,latest,zone)==capturedSpec && canShow() && content.width>0 && content.height>0)
                            show(latest,account,identity)
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> return@launch
                        TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                } catch(error: CancellationException) { throw error }
                catch(error: Exception) {
                    android.util.Log.w("AirStudyShare","Unable to open statistics artwork preview",error)
                    close()
                    if(account.isCurrent() && canShow())message(R.string.stats_share_artwork_failed)
                    return@launch
                }
            }
        }
    }

    fun keepFor(state: StatsUiState) {
        val active=entry ?: return
        if(state.isLoading || state.account!=active.account || !active.account.isCurrent() ||
            state.queryRevision!=active.queryRevision || !isCurrentQuery(active.account,active.queryRevision) || StatsShareSpec.from(context,state,zone)!=active.spec)close()
    }

    private fun show(state: StatsUiState,account: HistoryAccount,identity: TokenStore.Snapshot) {
        val spec=StatsShareSpec.from(context,state,zone)
        val bitmap=StatsShareArtworkView.render(context,spec)
        val background: Bitmap
        try {
            background=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888)
            try { content.draw(Canvas(background)) }
            catch(error: Throwable) { background.recycle();throw error }
        } catch(error: Throwable) { bitmap.recycle();throw error }
        try {
            val overlay=ArtworkPreviewOverlay(context).apply {
                id=R.id.statsSharePreview;isClickable=true;isFocusableInTouchMode=true;alpha=0f
                contentDescription=context.getString(R.string.stats_share_artwork_preview)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            overlay.addView(ImageView(context).apply {
                scaleType=ImageView.ScaleType.FIT_XY;setImageBitmap(background)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if(Build.VERSION.SDK_INT>=31)setRenderEffect(RenderEffect.createBlurEffect(30f,30f,Shader.TileMode.MIRROR))
                setColorFilter(Color.argb(140,9,16,32),PorterDuff.Mode.SRC_ATOP)
            },ViewGroup.LayoutParams(-1,-1))
            val image=ImageView(context).apply {
                id=R.id.statsSharePreviewImage;scaleType=ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap);contentDescription=spec.summary;setOnClickListener { }
            }
            overlay.addView(image,ViewGroup.LayoutParams(1,1));overlay.artwork=image
            val button=ExtendedFloatingActionButton(context).apply {
                id=R.id.statsSharePreviewAction;setText(R.string.stats_share_artwork_action)
                icon=ContextCompat.getDrawable(context,android.R.drawable.ic_menu_share)
                backgroundTintList=ColorStateList.valueOf(Color.rgb(34,99,212));setTextColor(Color.WHITE)
                iconTint=ColorStateList.valueOf(Color.WHITE);minimumHeight=dp(52f)
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null
                elevation=0f;translationZ=0f;stateListAnimator=null
            }
            overlay.addView(button,ViewGroup.LayoutParams(-2,-2));overlay.action=button
            val dismissButton=ImageButton(context).apply {
                id=R.id.statsSharePreviewClose
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList=ColorStateList.valueOf(context.getColor(UiR.color.sky_ink))
                contentDescription=context.getString(R.string.stats_share_artwork_close)
                setPadding(dp(12f),dp(12f),dp(12f),dp(12f));SkySurfaces.secondary(this,24f)
                setOnClickListener { close() }
            }
            overlay.addView(dismissButton,ViewGroup.LayoutParams(dp(48f),dp(48f)));overlay.closeControl=dismissButton
            val accessibility=(0 until content.childCount).map { content.getChildAt(it).let { child -> child to child.importantForAccessibility } }
            val active=Entry(account,identity,state.queryRevision,spec,overlay,bitmap,background,accessibility,content.findFocus())
            accessibility.forEach { (view,_) -> view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            entry=active
            overlay.setOnClickListener { close() }
            button.setOnClickListener { if(entry===active && sharing?.isActive!=true)share(active,button) }
            content.addView(overlay,ViewGroup.LayoutParams(-1,-1));overlay.requestFocus()
            if(Build.VERSION.SDK_INT<26 || ValueAnimator.areAnimatorsEnabled()) {
                overlay.animate().alpha(1f).setDuration(180).start()
                image.animate().scaleX(overlay.artworkScale).scaleY(overlay.artworkScale).setDuration(160).start()
            } else {
                overlay.alpha=1f;image.scaleX=overlay.artworkScale;image.scaleY=overlay.artworkScale
            }
            visibilityChanged(true)
        } catch(error: Throwable) {
            if(entry?.bitmap===bitmap)close() else { bitmap.recycle();background.recycle() }
            throw error
        }
    }

    private fun valid(active: Entry): Boolean {
        val current=currentState() ?: return false
        return entry===active && canShow() && !current.isLoading && current.account==active.account &&
            current.queryRevision==active.queryRevision && isCurrentQuery(active.account,active.queryRevision) && StatsShareSpec.from(context,current,zone)==active.spec
    }

    private fun share(active: Entry,button: ExtendedFloatingActionButton) {
        sharing=scope.launch {
            var copy: Bitmap?=null
            var file: File?=null
            var launched=false
            try {
                if(!valid(active)) { close();return@launch }
                while(isActive && valid(active)) {
                    when(val captured=TokenStore.tryWithSnapshotLease(active.identity) {
                        if(!valid(active))null else {
                            button.minimumHeight=maxOf(dp(52f),button.height)
                            button.isEnabled=false;button.setText(R.string.stats_share_artwork_preparing)
                            active.bitmap.copy(Bitmap.Config.ARGB_8888,false)
                        }
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> { copy=captured.value;break }
                        TokenStore.ImmediateSnapshot.Obsolete -> { close();return@launch }
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                }
                val captured=copy ?: return@launch
                withContext(io) {
                    val directory=File(context.cacheDir,"stats-share")
                    check(directory.isDirectory || directory.mkdirs())
                    val cutoff=System.currentTimeMillis()-24L*60*60*1000
                    directory.listFiles()?.filter { it.isFile && it.name.startsWith("stats-") && it.extension=="png" && it.lastModified()<cutoff }
                        ?.forEach { if(it.canonicalFile.parentFile==directory.canonicalFile)it.delete() }
                    val target=File(directory,"stats-${UUID.randomUUID()}.png");file=target
                    target.outputStream().use { check(captured.compress(Bitmap.CompressFormat.PNG,100,it)) }
                }
                if(!valid(active)) { close();return@launch }
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.statsfileprovider",requireNotNull(file))
                val send=Intent(Intent.ACTION_SEND).apply {
                    type="image/png";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_TEXT,active.spec.summary)
                    putExtra(Intent.EXTRA_SUBJECT,"AirStudy");putExtra(Intent.EXTRA_TITLE,active.spec.summary)
                    clipData=ClipData.newRawUri(active.spec.summary,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                while(isActive && valid(active)) {
                    when(TokenStore.tryWithSnapshotLease(active.identity) {
                        if(valid(active)) {
                            if(!hasReceiver(send))throw ActivityNotFoundException()
                            context.startActivity(Intent.createChooser(send,context.getString(R.string.stats_share_artwork_action)).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            });launched=true
                        }
                    }) {
                        is TokenStore.ImmediateSnapshot.Value -> break
                        TokenStore.ImmediateSnapshot.Obsolete -> { close();break }
                        TokenStore.ImmediateSnapshot.Busy -> delay(16)
                    }
                }
            } catch(error: CancellationException) { throw error }
            catch(_: ActivityNotFoundException) {
                if(entry===active && active.account.isCurrent())message(R.string.stats_share_artwork_no_target)
            } catch(error: Exception) {
                android.util.Log.w("AirStudyShare","Unable to export statistics artwork",error)
                if(entry===active && active.account.isCurrent())message(R.string.stats_share_artwork_failed)
            } finally {
                withContext(io+NonCancellable) { copy?.recycle();if(!launched)file?.delete() }
                if(entry===active && active.account.isCurrent()) {
                    button.isEnabled=true;button.setText(R.string.stats_share_artwork_action);button.minimumHeight=dp(52f)
                }
            }
        }
    }

    fun close() {
        ++revision;opening?.cancel();opening=null;sharing?.cancel();sharing=null
        val active=entry;entry=null
        if(active!=null) {
            active.overlay.animate().cancel()
            for(index in 0 until active.overlay.childCount)active.overlay.getChildAt(index).let {
                it.animate().cancel();if(it is ImageView)it.setImageDrawable(null)
            }
            content.removeView(active.overlay)
            active.accessibility.forEach { (view,previous) -> view.importantForAccessibility=previous }
            active.bitmap.recycle();active.background.recycle()
            if(active.focused?.isAttachedToWindow==true)active.focused.requestFocus()
        }
        visibilityChanged(false)
    }

    private fun HistoryAccount.matches(identity: TokenStore.Snapshot)=generation==identity.generation && scope==TokenStore.storageScope(identity)
    private fun dp(value: Float)=SkySurfaces.dp(context,value)
    private fun message(id: Int)=Toast.makeText(context,id,Toast.LENGTH_SHORT).show()
    private fun hasReceiver(send: Intent)=runCatching {
        context.packageManager.queryIntentActivities(send,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }.getOrDefault(true)
}
