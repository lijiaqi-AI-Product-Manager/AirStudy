package com.projectfocus.feature.home.map

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.projectfocus.feature.home.R
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SourceMapHostView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val mutableState = MutableStateFlow(MapHostState())
    val state: StateFlow<MapHostState> = mutableState.asStateFlow()
    internal var surfaceFactory: MapSurfaceFactory = HmsMapSurfaceFactory
    private val container: FrameLayout
    private val statusPanel: android.view.View
    private val message: TextView
    private val styleNotice: android.view.View
    private var owner: LifecycleOwner? = null
    private var configuration = MapConfiguration("")
    private var surface: MapSurface? = null
    private var renderer: MapRenderer? = null
    private var freeFlightVisual: FreeFlightMapVisual? = null
    private var created = false
    private var started = false
    private var resumed = false
    private var mapVisible = true
    private var generation = 0L
    private var pendingSdkState: Bundle? = null
    private var route = MapRouteInput(null, null, 0f)
    private var styleIndex = 0
    private var styleExplicit = false
    private var attemptedStyle: Int? = null
    private var windowMode = false
    private var windowExplicit = false
    private var statusControlsVisible = true
    private val observer = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) = reconcile()
        override fun onStart(owner: LifecycleOwner) = reconcile()
        override fun onResume(owner: LifecycleOwner) = reconcile()
        override fun onPause(owner: LifecycleOwner) = reconcile()
        override fun onStop(owner: LifecycleOwner) = reconcile()
        override fun onDestroy(owner: LifecycleOwner) {
            if (this@SourceMapHostView.owner === owner) {
                disposeSurface()
                this@SourceMapHostView.owner = null
                publish(MapPhase.DESTROYED)
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.pf_map_host, this, true)
        container = findViewById(R.id.pf_map_sdk_container)
        statusPanel = findViewById(R.id.pf_map_status_panel)
        message = findViewById(R.id.pf_map_message)
        styleNotice = findViewById(R.id.pf_map_style_notice)
        findViewById<android.view.View>(R.id.pf_map_retry).setOnClickListener { retry() }
        updateStatus()
    }

    fun bindLifecycle(owner: LifecycleOwner, configuration: MapConfiguration, savedMapBundle: Bundle? = null) {
        if (this.owner === owner && this.configuration == configuration) { reconcile(); return }
        this.owner?.lifecycle?.removeObserver(observer)
        disposeSurface()
        this.owner = owner
        this.configuration = configuration
        publish(MapPhase.WAITING)
        if (savedMapBundle?.getInt(KEY_VERSION) == 1) {
            pendingSdkState = savedMapBundle.getBundle(KEY_SDK)?.deepCopy()
            if (!styleExplicit) styleIndex = savedMapBundle.getInt(KEY_STYLE).coerceIn(0, 7)
            if (!windowExplicit) windowMode = savedMapBundle.getBoolean(KEY_WINDOW)
        }
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            this.owner = null
            publish(MapPhase.DESTROYED)
        } else {
            owner.lifecycle.addObserver(observer)
            reconcile()
        }
    }

    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        visibility = if (visible) VISIBLE else INVISIBLE
        reconcile()
    }

    /** Hide host diagnostics under an active preparation surface; SDK state stays real. */
    fun setStatusControlsVisible(visible: Boolean) {
        if (statusControlsVisible == visible) return
        statusControlsVisible = visible
        updateStatus()
    }

    fun setWindowMode(enabled: Boolean) {
        windowExplicit = true
        if (windowMode == enabled) return
        windowMode = enabled
        safeRender { it.setWindowMode(enabled) }
    }

    fun setAirports(origin: MapAirport?, destination: MapAirport?) {
        val input = route.copy(origin = origin, destination = destination)
        if (input == route) return
        route = input
        safeRender { it.setRoute(route) }
    }

    fun setFlightProgress(progress: Float) {
        val value = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (route.progress == value) return
        route = route.copy(progress = value)
        safeRender { it.setRoute(route) }
    }

    val freeFlightPreparationReady: Boolean get() = state.value.phase in setOf(MapPhase.READY, MapPhase.LOADED) &&
        renderer is FreeFlightPlaneRenderer && renderer is FreeFlightDepartureCameraRenderer

    /** Requires the actual live SDK capability. An absent/unready map never supplies a fake plane. */
    fun openFreeFlightVisual(initial: FreeFlightMapPose): FreeFlightMapVisual? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val actual = renderer as? FreeFlightPlaneRenderer ?: return null
        if (state.value.phase !in setOf(MapPhase.READY, MapPhase.LOADED)) return null
        val rendererToken = generation
        val map = renderer
        freeFlightVisual?.close()
        val lease = object : FreeFlightMapVisual {
            private var closed = false
            override val isCurrent: Boolean get() = !closed && freeFlightVisual === this &&
                generation == rendererToken && renderer === map &&
                state.value.phase in setOf(MapPhase.READY, MapPhase.LOADED)

            override fun render(pose: FreeFlightMapPose): Boolean {
                check(Looper.myLooper() == Looper.getMainLooper())
                if (!isCurrent) return false
                return try {
                    actual.renderFreeFlightPlane(pose)
                    true
                } catch (_: Exception) { fail(); false }
                catch (_: LinkageError) { fail(); false }
            }

            override fun close() {
                check(Looper.myLooper() == Looper.getMainLooper())
                if (closed) return
                closed = true
                if (freeFlightVisual !== this) return
                freeFlightVisual = null
                if (generation != rendererToken || renderer !== map) return
                try {
                    actual.clearFreeFlightPlane()
                    map?.setRoute(route)
                } catch (_: Exception) { fail() }
                catch (_: LinkageError) { fail() }
            }
        }
        freeFlightVisual = lease
        if (!lease.render(initial)) { lease.close(); return null }
        return lease
    }

    /** Actual source departure camera, after a real plane lease was opened. */
    fun focusFreeFlightDeparture(departure: MapAirport): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (freeFlightVisual?.isCurrent != true) return false
        val camera = renderer as? FreeFlightDepartureCameraRenderer ?: return false
        return try { camera.focusFreeFlightDeparture(departure); true }
        catch (_: Exception) { fail(); false }
        catch (_: LinkageError) { fail(); false }
    }

    /** Invoked by the real arrival bridge on Main, only for the current live renderer. */
    fun animateFreeFlightCruiseReturn(pose:FreeFlightMapPose,elapsedMs:Long,zoom:Float,current:()->Boolean,
                                     frameLease:((()->Unit)->Boolean),result:()->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(state.value.phase in setOf(MapPhase.READY,MapPhase.LOADED) && freeFlightVisual?.isCurrent==true) { "Actual return map/plane is not ready" }
        val actual=renderer as? FreeFlightCruiseReturnRenderer ?: error("Actual source cruise-return camera is unavailable")
        val token=generation
        val guardedFrame:((()->Unit)->Boolean)={ effect ->
            try { frameLease(effect) }
            catch (_:Exception) { fail();false }
            catch (_:LinkageError) { fail();false }
        }
        actual.animateCruiseReturn(pose,elapsedMs,zoom,{ generation==token && renderer===actual && current() },guardedFrame,result)
    }

    /** Invoked by the real arrival bridge on Main, only for the current live renderer. */
    fun renderFreeFlightLandingScene(scene: FreeFlightLandingMapScene?, routeColor: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (freeFlightVisual?.isCurrent != true) return false
        val actual = renderer as? FreeFlightLandingRenderer ?: return false
        return try { actual.renderLandingScene(scene, routeColor); true }
        catch (_: Exception) { fail(); false }
        catch (_: LinkageError) { fail(); false }
    }

    /** Invoked by the real arrival bridge on Main, only for the current live renderer. */
    fun animateFreeFlightFinalArrival(destination: MapAirport, cruiseZoom: Float, result: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(state.value.phase in setOf(MapPhase.READY, MapPhase.LOADED)) { "The actual map is not ready" }
        val camera = renderer as? FreeFlightCameraRenderer ?: error("The actual final camera capability is unavailable")
        camera.animateFinalArrival(destination, cruiseZoom, result)
    }

    fun setMapStyle(index: Int) {
        styleExplicit = true
        val value = index.coerceIn(0, 7)
        if (value == styleIndex) return
        styleIndex = value
        attemptedStyle = null
        applyStyle()
    }

    fun retry() {
        if (owner == null) return
        disposeSurface()
        publish(MapPhase.WAITING)
        reconcile()
    }

    fun onLowMemory() {
        try { surface?.onLowMemory() } catch (_: Exception) { fail() }
    }

    fun saveMapState(outBundle: Bundle) {
        val sdkState = Bundle()
        if (created) try { surface?.onSaveInstanceState(sdkState) } catch (_: Exception) { fail() }
        else pendingSdkState?.let(sdkState::putAll)
        removeCredentials(sdkState)
        outBundle.putInt(KEY_VERSION, 1)
        outBundle.putInt(KEY_STYLE, styleIndex)
        outBundle.putBoolean(KEY_WINDOW, windowMode)
        outBundle.putBundle(KEY_SDK, sdkState)
    }

    private fun reconcile() {
        val lifecycle = owner?.lifecycle ?: return
        if (configuration.apiKey.isBlank()) {
            disposeSurface()
            publish(MapPhase.MISSING_CONFIGURATION)
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) { publish(MapPhase.WAITING); return }
        if (surface == null && mapVisible && state.value.phase != MapPhase.FAILED) createSurface()
        val current = surface ?: return
        val wantsStart = mapVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val wantsResume = mapVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        try {
            if (resumed && !wantsResume) { current.onPause(); resumed = false }
            if (started && !wantsStart) { current.onStop(); started = false }
            if (!started && wantsStart) { current.onStart(); started = true }
            if (!resumed && wantsResume) { current.onResume(); resumed = true }
        } catch (_: Exception) { fail() }
        catch (_: LinkageError) { fail() }
    }

    private fun createSurface() {
        val token = ++generation
        try {
            val current = surfaceFactory.create(context, configuration)
            surface = current
            container.addView(current.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            created = true
            current.onCreate(pendingSdkState)
            pendingSdkState = null
            publish(MapPhase.CREATED)
            val weak = WeakReference(this)
            current.getMapAsync { map ->
                weak.get()?.let { host ->
                    if (Looper.myLooper() == Looper.getMainLooper()) host.acceptMap(token, map)
                    else host.post { weak.get()?.acceptMap(token, map) }
                }
            }
        } catch (_: Exception) { fail() }
        catch (_: LinkageError) { fail() }
    }

    private fun acceptMap(token: Long, map: MapRenderer) {
        if (token != generation || surface == null || owner == null || renderer != null) return
        renderer = map
        publish(MapPhase.READY)
        try {
            map.configureSourceControls()
            map.setWindowMode(windowMode)
            map.setRoute(route)
            val weak = WeakReference(this)
            map.setOnLoaded {
                weak.get()?.dispatchLoaded(token, map)
            }
            applyStyle()
        } catch (_: Exception) { fail() }
    }

    private fun applyStyle() {
        val map = renderer
        if (map == null) {
            mutableState.value = state.value.copy(styleStatus = MapStyleStatus.PENDING, requestedStyleIndex = styleIndex)
            updateStatus()
            return
        }
        if (attemptedStyle == styleIndex) return
        attemptedStyle = styleIndex
        val accepted = try { map.setStyle(styleIndex) } catch (_: Exception) { false }
        mutableState.value = state.value.copy(styleStatus = if (accepted) MapStyleStatus.APPLIED else MapStyleStatus.REJECTED,
            requestedStyleIndex = styleIndex)
        updateStatus()
    }

    private fun safeRender(action: (MapRenderer) -> Unit) {
        try { renderer?.let(action) } catch (_: Exception) { fail() }
    }

    private fun disposeSurface() {
        freeFlightVisual = null
        ++generation
        val current = surface
        if (current != null && created) {
            val snapshot = Bundle()
            if (runCatching { current.onSaveInstanceState(snapshot) }.isSuccess) {
                removeCredentials(snapshot)
                pendingSdkState = snapshot
            }
        }
        renderer?.let { runCatching { it.setOnLoaded(null); it.dispose() } }
        renderer = null
        if (current != null) {
            if (resumed) runCatching { current.onPause() }
            if (started) runCatching { current.onStop() }
            if (created) runCatching { current.onDestroy() }
            container.removeView(current.view)
        }
        surface = null
        resumed = false
        started = false
        created = false
        attemptedStyle = null
        mutableState.value = state.value.copy(styleStatus = MapStyleStatus.PENDING)
    }

    private fun dispatchLoaded(token: Long, map: MapRenderer) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { dispatchLoaded(token, map) }
            return
        }
        if (generation == token && renderer === map) publish(MapPhase.LOADED)
    }

    private fun fail() { disposeSurface(); publish(MapPhase.FAILED) }
    private fun publish(phase: MapPhase) {
        mutableState.value = state.value.copy(phase = phase, requestedStyleIndex = styleIndex)
        updateStatus()
    }
    private fun updateStatus() {
        val phase = state.value.phase
        statusPanel.visibility = if (statusControlsVisible && phase != MapPhase.LOADED) VISIBLE else GONE
        message.setText(if (phase in listOf(MapPhase.MISSING_CONFIGURATION, MapPhase.FAILED)) R.string.pf_map_unavailable else R.string.pf_map_loading)
        styleNotice.visibility = if (statusControlsVisible && state.value.styleStatus == MapStyleStatus.REJECTED) VISIBLE else GONE
    }
    private fun removeCredentials(bundle: Bundle) {
        val secrets = listOfNotNull(configuration.apiKey, configuration.apiKey.trim(), configuration.appId,
            configuration.appId?.trim()).filter { it.isNotEmpty() }.toSet()
        for (key in bundle.keySet().toList()) {
            val value = bundle.get(key)
            val secretKey = key.lowercase().replace("_", "").let { "apikey" in it || "appid" in it || "accesstoken" in it }
            if (secretKey || value is String && value in secrets) bundle.remove(key)
            else if (value is Bundle) removeCredentials(value)
        }
    }
    private companion object {
        const val KEY_VERSION = "pf_map_version"
        const val KEY_SDK = "pf_map_sdk_state"
        const val KEY_STYLE = "pf_map_style"
        const val KEY_WINDOW = "pf_map_window"
    }
}
