package com.projectfocus.app.home

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.app.BuildConfig
import com.projectfocus.feature.home.map.MapAirport
import com.projectfocus.feature.home.map.MapConfiguration
import com.projectfocus.feature.home.map.MapPhase
import com.projectfocus.feature.home.map.MapStyleStatus
import com.projectfocus.feature.home.map.SourceMapHostView
import com.projectfocus.app.route.AirportSearchHostFragment
import com.projectfocus.app.route.EntryAction
import com.projectfocus.app.route.RouteArgs
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.feature.flight.hud.HudSnapshot
import com.projectfocus.feature.flight.hud.FlightHudView
import com.projectfocus.feature.flight.hud.FlightHudCallbacks
import com.projectfocus.feature.flight.hud.FlightHudUiState
import com.projectfocus.feature.flight.window.FlightWindowView
import com.projectfocus.feature.sound.AmbientPlaybackRegistry
import com.projectfocus.feature.sound.AmbientSound
import com.projectfocus.feature.sound.AmbientSoundPlayer
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Hosts the source map/window layers and the existing flight preparation/session flow. */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var airportDao: AirportDao

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var freeFlightMapRuntime: com.projectfocus.app.freeflight.FreeFlightMapRuntime
    @Inject lateinit var freeFlightSessions: com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
    @Inject lateinit var freeFlightOwners: com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
    @Inject lateinit var freeFlightRuntime: com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
    @Inject lateinit var freeFlightLandingCandidates: com.projectfocus.app.freeflight.FreeFlightLandingCandidatesFactory
    @Inject lateinit var freeFlightFeedback: com.projectfocus.app.freeflight.AndroidFreeFlightRulerFeedbackFactory
    @Inject lateinit var freeFlightCompletionCoordinator: com.projectfocus.app.freeflight.FreeFlightCompletionCoordinator
    private var freeFlightMapBinding: java.io.Closeable? = null
    private var freeFlightFlightPresentation: com.projectfocus.app.freeflight.FreeFlightHomePresentation? = null
    private var freeFlightFrame: com.projectfocus.app.freeflight.FreeFlightHomeFrame? = null
    private var freeFlightLanding: com.projectfocus.app.freeflight.FreeFlightLandingController? = null
    private var freeFlightCompletion: com.projectfocus.app.freeflight.FreeFlightCompletionController? = null
    private var completionVisible = false

    internal fun reflectFreeFlightStart(sessionId: String) {
        if (freeFlightFrame?.archive?.sessionId == sessionId) view?.let { renderSessionState(it, sessionRepository.state.value) }
    }

    /** 未选择时保持空；不把示意地图里的样本机场作为用户的航线。 */
    private var pickedAirport: AirportEntity? = null

    /** 用户选择的出发机场。 */
    private var pickedOrigin: com.projectfocus.core.model.flight.PlannedAirport? = null


    private val prefs by lazy {
        requireContext().getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
    }

    private var selectionReloadJob: Job? = null
    private val selectionChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == PREF_ORIGIN || key == PREF_DESTINATION || key == HomeOriginSelection.MARKER) {
            view?.let(::restorePersistedSelection)
        }
    }

    private var selectedMinutes: Int = 25
    private var selectedMapStyle: Int = HomeMapStyleSelection.DEFAULT_INDEX
    private var mapStylePanelVisible: Boolean = false
    private var hudTickerJob: Job? = null
    private var flightWindowVisible = false
    private var flightWindowPosition = 0L
    private var savedMapState: Bundle? = null
    private var freeFlightPresentation: HomePreparationPresentation? = null
    private var mapPreviewVisible = false
    private var mapPreviewBack: OnBackPressedCallback? = null

    /** View-only lease. It grants no account, quota, reservation or flight ownership. */
    internal fun beginFreeFlightPreparationPresentation(): java.io.Closeable? {
        val currentView = view ?: return null
        if (sessionRepository.state.value !is SessionState.Idle || freeFlightCompletionCoordinator.blocksPreparation()) return null
        freeFlightPresentation?.close()
        val lease = HomePreparationPresentation(currentView,
            isCurrent = { freeFlightPresentation === it && view === currentView },
            restoreHome = {
                if (freeFlightPresentation === it && view === currentView) {
                    freeFlightPresentation = null
                    renderSessionState(currentView, sessionRepository.state.value)
                }
            })
        freeFlightPresentation = lease
        mapPreviewVisible = false
        mapPreviewBack?.isEnabled = false
        currentView.findViewById<View>(R.id.homeMapClose).visibility = View.GONE
        mapStylePanelVisible = false
        currentView.findViewById<View>(R.id.mapStyleSelectorContainer).visibility = View.GONE
        lease.show()
        return lease
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedMapState = savedInstanceState?.getBundle(STATE_MAP)
        flightWindowVisible = savedInstanceState?.getBoolean(STATE_FLIGHT_WINDOW) ?: false
        flightWindowPosition = savedInstanceState?.getLong(STATE_FLIGHT_WINDOW_POSITION) ?: 0L
    }


    private var ambientPlayer: AmbientSoundPlayer? = null
    private var ambientIndex: Int = -1
    private var ambientVolume: Float = 0.5f
    private var whiteNoiseEnabled: Boolean = false
    private var volumeDirty: Boolean = false
    private var volumeWriteJob: Job? = null
    private val mapStylePanelChipIds = intArrayOf(
        R.id.mapStylePanelChip0,
        R.id.mapStylePanelChip1,
        R.id.mapStylePanelChip2,
        R.id.mapStylePanelChip3,
        R.id.mapStylePanelChip4,
        R.id.mapStylePanelChip5,
        R.id.mapStylePanelChip6,
        R.id.mapStylePanelChip7,
    )
    private val mapStyleNameRes = intArrayOf(
        R.string.home_map_style_forest,
        R.string.home_map_style_ocean,
        R.string.home_map_style_night,
        R.string.home_map_style_sunset,
        R.string.home_map_style_vintage,
        R.string.home_map_style_satellite,
        R.string.pf_source_map_style_day,
        R.string.home_map_style_violet,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_source_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSkyHome(view)
        view.findViewById<FlightWindowView>(R.id.flightWindow).restorePosition(flightWindowPosition)

        view.findViewById<SourceMapHostView>(R.id.sourceMap).bindLifecycle(
            viewLifecycleOwner,
            MapConfiguration(BuildConfig.HMS_MAP_API_KEY, BuildConfig.HMS_MAP_APP_ID.takeIf { it.isNotBlank() }),
            savedMapState,
        )
        freeFlightMapBinding?.close()
        freeFlightMapBinding = freeFlightMapRuntime.bind(view.findViewById(R.id.sourceMap))

        setupStartFlight(view)

        // “起飞”菜单 → 飞行模式选择。把当前时长与起降机场带过去，
        // 让“倒计时”CTA 能真正启动同一场会话，而不是只弹 Toast。
        view.findViewById<View>(R.id.menuTakeOff).setOnClickListener {
            if (freeFlightCompletionCoordinator.blocksPreparation()) return@setOnClickListener
            findNavController().navigate(
                R.id.action_home_to_flight_mode,
                bundleOf(
                    "minutes" to selectedMinutes,
                    "origin" to originCode(),
                    "destination" to destinationCode(),
                ),
            )
        }
        view.findViewById<View>(R.id.menuRecord).setOnClickListener {
            findNavController().navigate(R.id.action_home_to_record)
        }
        view.findViewById<View>(R.id.menuMap).setOnClickListener {
            setMapPreview(view, true)
        }
        view.findViewById<View>(R.id.menuSettings).setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
        prefs.registerOnSharedPreferenceChangeListener(selectionChanges)
        setupMapStyleSelector(view)
        setupFlightHud(view)
        freeFlightLanding = com.projectfocus.app.freeflight.FreeFlightLandingController(
            requireContext(), view as ViewGroup, freeFlightSessions, freeFlightOwners, freeFlightLandingCandidates.create(),
            freeFlightFeedback, viewLifecycleOwner.lifecycleScope,
            collapseWindow = { flightWindowVisible = false },
            changed = { if (this.view === view) renderSessionState(view, sessionRepository.state.value) },
        )
        setupFreeFlightCompletion(view)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val presentation = com.projectfocus.app.freeflight.FreeFlightHomePresentation(
                    freeFlightSessions, freeFlightOwners, freeFlightRuntime, freeFlightMapRuntime, this,
                ) { frame ->
                    if (this@HomeFragment.view === view) {
                        freeFlightFrame = frame
                        freeFlightLanding?.bind(frame)
                        renderSessionState(view, sessionRepository.state.value)
                    }
                }
                freeFlightFlightPresentation = presentation
                try { kotlinx.coroutines.awaitCancellation() }
                finally {
                    presentation.close()
                    if (freeFlightFlightPresentation === presentation) { freeFlightFlightPresentation = null; freeFlightFrame = null }
                }
            }
        }



        //     在首页里，"当前"就是首页自己 —— 搜索页是 pop 之后

        //     所以这里读自己的 handle 是对的。
        val nav = runCatching { findNavController() }.getOrNull()
        val handle = nav?.currentBackStackEntry?.savedStateHandle
        val picked = handle?.get<Boolean>(AirportSearchHostFragment.KEY_PICKED) == true
        if (picked) {
            val code = handle?.get<String>(AirportSearchHostFragment.KEY_AIRPORT_CODE)
            val isOrigin = handle?.get<Boolean>(AirportSearchHostFragment.KEY_PICKED_ORIGIN) == true
            handle?.remove<Boolean>(AirportSearchHostFragment.KEY_PICKED)
            handle?.remove<String>(AirportSearchHostFragment.KEY_AIRPORT_CODE)
            handle?.remove<Boolean>(AirportSearchHostFragment.KEY_PICKED_ORIGIN)
            if (code != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val airport = runCatching { airportDao.findByCode(code) }.getOrNull()
                    if (airport != null) {
                        if (isOrigin) {
                            pickedOrigin = airport.plannedSnapshot()
                            renderPickedOrigin(view, airport)
                        } else {
                            pickedAirport = airport
                            renderPickedAirport(view, airport)
                        }
                    } else {
                        timber.log.Timber.w("选中的机场 %s 在本地库中不存在", code)
                    }
                }
            }
        } else {
            restorePersistedSelection(view)
        }
    }

    private fun setupFreeFlightCompletion(view: ViewGroup) {
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { freeFlightCompletion?.onBack() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val completion = com.projectfocus.app.freeflight.FreeFlightCompletionController(
                    view, freeFlightCompletionCoordinator, freeFlightOwners, this,
                    visibilityChanged = { visible ->
                        completionVisible = visible
                        back.isEnabled = visible
                        if (visible) {
                            mapStylePanelVisible = false
                            view.findViewById<View>(R.id.mapStyleSelectorContainer).visibility = View.GONE
                        }
                        if (this@HomeFragment.view === view) renderSessionState(view, sessionRepository.state.value)
                    },
                    onShare = { result ->
                        val text = getString(R.string.pf_ff_completion_share,
                            result.departure.displayCity.ifBlank { result.departure.code },
                            result.destination.displayCity.ifBlank { result.destination.code },
                            result.displayFocusMinutes, String.format(Locale.US, "%.1f", result.distanceKm))
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(send,
                            getString(com.projectfocus.feature.completion.R.string.flight_completion_share_chooser)))
                    },
                    onAirport = { result, report ->
                        findNavController().navigate(Uri.Builder().scheme("projectfocus").authority("airport")
                            .appendPath(result.destination.code)
                            .apply { if (report) appendQueryParameter("report", "true") }.build())
                    },
                )
                freeFlightCompletion = completion
                try { kotlinx.coroutines.awaitCancellation() }
                finally {
                    completion.close()
                    if (freeFlightCompletion === completion) freeFlightCompletion = null
                }
            }
        }
    }

    private fun renderPickedAirport(view: View, airport: AirportEntity) {
        persistSelection(PREF_DESTINATION, airport.iataCode.ifBlank { airport.icaoCode })
        renderAirportSelection(view)
    }

    private fun renderPickedOrigin(view: View, airport: AirportEntity) {
        persistSelection(PREF_ORIGIN, airport.iataCode.ifBlank { airport.icaoCode })
        renderAirportSelection(view)
    }

    private fun AirportEntity.mapAirport(): MapAirport? = runCatching {
        MapAirport(iataCode.ifBlank { icaoCode }, latitude, longitude)
    }.getOrNull()

    private fun AirportEntity.plannedSnapshot() = com.projectfocus.core.model.flight.PlannedAirport(
        id,iataCode.ifBlank { icaoCode },name,city,country,latitude,longitude,
        nameZh?.takeIf(String::isNotBlank) ?: name,cityZh?.takeIf(String::isNotBlank) ?: city)

    private fun com.projectfocus.core.model.flight.PlannedAirport.mapAirport(): MapAirport? = runCatching {
        MapAirport(code,latitude,longitude)
    }.getOrNull()

    private fun renderAirportSelection(view: View) {
        view.findViewById<SourceMapHostView>(R.id.sourceMap).setAirports(
            pickedOrigin?.mapAirport(), pickedAirport?.mapAirport(),
        )
        view.findViewById<TextView>(R.id.btnStartFlight).setText(
            if (pickedOrigin == null) R.string.home_action_search_airport else R.string.home_prepare_takeoff,
        )
        val origin = pickedOrigin
        val destination = pickedAirport
        val originCode = origin?.code ?: getString(R.string.sky_home_airport_unknown)
        val destinationCode = destination?.let { it.iataCode.ifBlank { it.icaoCode } }
            ?: getString(R.string.sky_home_airport_unknown)
        val originCity = origin?.let { it.displayCity.ifBlank { it.city.ifBlank { it.displayName } } }
            ?: getString(R.string.sky_home_pick_origin)
        val destinationCity = destination?.let { it.cityZh?.takeIf(String::isNotBlank) ?: it.city.ifBlank { it.name } }
            ?: getString(R.string.sky_home_pick_destination)
        view.findViewById<TextView>(R.id.homeOriginCode).text = originCode
        view.findViewById<TextView>(R.id.homeOriginCity).text = originCity
        view.findViewById<TextView>(R.id.homeDestinationCode).text = destinationCode
        view.findViewById<TextView>(R.id.homeDestinationCity).text = destinationCity
        view.findViewById<View>(R.id.homeOrigin).contentDescription =
            getString(R.string.sky_home_origin_accessibility, originCode, originCity)
        view.findViewById<View>(R.id.homeDestination).contentDescription =
            getString(R.string.sky_home_destination_accessibility, destinationCode, destinationCity)
    }

    private fun setupSkyHome(root: View) {
        com.projectfocus.core.ui.sky.SkySurfaces.card(root.findViewById(R.id.homeRouteCard), elevation = 7f)
        com.projectfocus.core.ui.sky.SkySurfaces.card(root.findViewById(R.id.right_menu_container), radius = 30f, elevation = 12f)
        com.projectfocus.core.ui.sky.SkySurfaces.action(root.findViewById(R.id.btnStartFlight))
        com.projectfocus.core.ui.sky.SkySurfaces.secondary(root.findViewById(R.id.homeMapClose), radius = 24f)
        root.findViewById<View>(R.id.menuTakeOff).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 22f).toFloat()
                setColor(context.getColor(com.projectfocus.core.ui.R.color.sky_selected))
            }
            isSelected = true
        }
        listOf(R.id.homeOrigin, R.id.homeDestination, R.id.btnStartFlight, R.id.btnFlightPlan,
            R.id.menuTakeOff, R.id.menuRecord, R.id.menuMap, R.id.menuSettings, R.id.homeMapClose).forEach { id ->
            com.projectfocus.core.ui.sky.SkyPressFeedback.install(root.findViewById(id))
        }
        fun choose(origin: Boolean) {
            if (sessionRepository.state.value !is SessionState.Idle || freeFlightPresentation != null ||
                completionVisible || freeFlightCompletionCoordinator.blocksPreparation()) return
            val args = bundleOf(RouteArgs.ARG_MODE to
                if (origin) RouteArgs.MODE_PICK_ORIGIN else RouteArgs.MODE_PICK_DESTINATION)
            if (!origin) com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs.writeTo(args, ordinaryOriginReference())
            safeNavigate(R.id.action_home_to_airport, args)
        }
        root.findViewById<View>(R.id.homeOrigin).setOnClickListener { choose(true) }
        root.findViewById<View>(R.id.homeDestination).setOnClickListener { choose(false) }
        root.findViewById<View>(R.id.homeMapClose).setOnClickListener { setMapPreview(root, false) }
        root.findViewById<View>(R.id.right_menu_container).addOnLayoutChangeListener { dock, _, _, _, _, _, _, _, _ ->
            val scroll = root.findViewById<View>(R.id.homeScroll)
            val bottom = dock.height + com.projectfocus.core.ui.sky.SkySurfaces.dp(requireContext(), 36f)
            if (scroll.paddingBottom != bottom) scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom)
        }
        mapPreviewBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { setMapPreview(root, false) }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        // Keep the page and floating controls comfortable on wider windows.
        if (resources.configuration.screenWidthDp > 600) {
            val width = com.projectfocus.core.ui.sky.SkySurfaces.dp(requireContext(), 560f)
            root.findViewById<View>(R.id.homePageContent).layoutParams.width = width
            root.findViewById<View>(R.id.right_menu_container).layoutParams.width =
                width - com.projectfocus.core.ui.sky.SkySurfaces.dp(requireContext(), 44f)
        }
    }

    private fun setMapPreview(root: View, visible: Boolean) {
        if (visible && (sessionRepository.state.value !is SessionState.Idle || freeFlightFrame != null ||
            freeFlightPresentation != null || completionVisible)) return
        mapPreviewVisible = visible
        mapPreviewBack?.isEnabled = visible
        root.findViewById<View>(R.id.homeMapClose).visibility = if (visible) View.VISIBLE else View.GONE
        mapStylePanelVisible = visible
        renderSessionState(root, sessionRepository.state.value)
        root.findViewById<View>(R.id.mapStyleSelectorContainer).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun persistSelection(key: String, code: String) {
        if (key == PREF_ORIGIN) HomeOriginSelection.selectManually(prefs, code)
        else prefs.edit().putString(key, code).apply()
    }

    /** Refresh the attached Home after a manual selection, arrival write or clear. */
    private fun restorePersistedSelection(view: View) {
        selectionReloadJob?.cancel()
        selectionReloadJob = null
        val originCode = prefs.getString(PREF_ORIGIN, null)
        val originMarker = prefs.getString(HomeOriginSelection.MARKER,null)
        val account = com.projectfocus.core.network.di.TokenStore.snapshot()
        val destinationCode = prefs.getString(PREF_DESTINATION, null)
        fun AirportEntity.code() = iataCode.ifBlank { icaoCode }
        // Drop an obsolete cached airport before the suspendable DAO reads.
        pickedOrigin = pickedOrigin?.takeIf { it.code == originCode }
        pickedAirport = pickedAirport?.takeIf { it.code() == destinationCode }
        renderAirportSelection(view)
        if (originCode == null && destinationCode == null) return
        selectionReloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val origin = originCode?.takeIf(String::isNotBlank)?.let {
                    HomeOriginSelection.selectedSnapshot(prefs,it,account) ?: airportDao.findByCode(it)?.plannedSnapshot()
                }
                val destination = destinationCode?.takeIf(String::isNotBlank)?.let { airportDao.findByCode(it) }
                if (this@HomeFragment.view !== view ||
                    prefs.getString(PREF_ORIGIN, null) != originCode ||
                    prefs.getString(HomeOriginSelection.MARKER,null) != originMarker ||
                    com.projectfocus.core.network.di.TokenStore.snapshot() != account ||
                    prefs.getString(PREF_DESTINATION, null) != destinationCode) return@launch
                pickedOrigin = origin
                pickedAirport = destination
                renderAirportSelection(view)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not reload the current Home airports")
            }
        }
    }

    private fun setupStartFlight(view: View) {
        view.findViewById<View>(R.id.btnFlightPlan).setOnClickListener {
            if (sessionRepository.state.value is SessionState.Idle && !freeFlightCompletionCoordinator.blocksPreparation() && freeFlightPresentation == null) {
                com.projectfocus.core.ui.nav.FlightPreparationNavigator.open(findNavController(), originCode(), destinationCode())
            }
        }
        view.findViewById<View>(R.id.btnStartFlight).setOnClickListener {
            if (originCode().isEmpty()) {
                safeNavigate(R.id.action_home_to_airport,
                    bundleOf(RouteArgs.ARG_MODE to RouteArgs.MODE_PICK_ORIGIN))
            } else startFlight(view)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionRepository.state.collect { state -> renderSessionState(view, state) }
            }
        }
    }

    private fun setupMapStyleSelector(view: View) {
        selectedMapStyle = HomeMapStyleSelection.read(requireContext())
        mapStylePanelChipIds.forEachIndexed { index, id ->
            view.findViewById<View>(id).setOnClickListener {
                selectMapStyle(view, index)
                renderFlightHud(view, sessionRepository.state.value)
            }
        }
        selectMapStyle(view, selectedMapStyle)
    }

    private fun toggleMapStylePanel(view: View) {
        mapStylePanelVisible = !mapStylePanelVisible
        view.findViewById<View>(R.id.mapStyleSelectorContainer).visibility =
            if (mapStylePanelVisible) View.VISIBLE else View.GONE
    }

    private fun selectMapStyle(view: View, index: Int) {
        if (index !in mapStylePanelChipIds.indices) return
        selectedMapStyle = index
        HomeMapStyleSelection.select(requireContext(), index)
        mapStylePanelChipIds.forEachIndexed { i, id -> view.findViewById<View>(id).isSelected = i == index }
        view.findViewById<SourceMapHostView>(R.id.sourceMap).setMapStyle(index)
    }

    /** 未选时不代入虚构机场；准备页会要求用户确认。 */
    private fun destinationCode(): String = prefs.getString(PREF_DESTINATION, null).orEmpty()

    /** Read the current persisted choice even while the visible map is reloading. */
    private fun originCode(): String = prefs.getString(PREF_ORIGIN, null).orEmpty()

    internal fun ordinaryOriginReference(): com.projectfocus.feature.airport.ui.AirportReferencePoint? =
        pickedOrigin?.takeIf { it.code.equals(originCode(), ignoreCase = true) }?.let {
            com.projectfocus.feature.airport.ui.AirportReferencePoint.from(it.latitude, it.longitude)
        }

    private fun startFlight(view: View) {
        if (freeFlightCompletionCoordinator.blocksPreparation()) return
        if (sessionRepository.state.value !is SessionState.Idle) {
            showHomeStatus(view, R.string.home_start_rejected)
            return
        }
        val origin = originCode()
        val destination = destinationCode()
        if (origin.isNotEmpty() && origin == destination) {
            showHomeStatus(view, R.string.home_same_airport)
            return
        }
        if ((activity as? com.projectfocus.core.ui.nav.OrdinaryFlightPreparationHost)?.openOrdinaryFlightPreparation() != true) {
            showHomeStatus(view, R.string.home_start_rejected)
        }
    }

    private fun showHomeStatus(view: View, textRes: Int) {
        view.findViewById<TextView>(R.id.tvHomeStatus).apply {
            setText(textRes); visibility = if (freeFlightPresentation == null) View.VISIBLE else View.GONE
        }
    }

    private fun renderSessionState(view: View, state: SessionState) {
        val idle = state is SessionState.Idle
        view.findViewById<View>(R.id.btnStartFlight).isEnabled = idle && freeFlightPresentation == null &&
            !completionVisible && !freeFlightCompletionCoordinator.blocksPreparation()
        view.findViewById<View>(R.id.btnFlightPlan).isEnabled = idle && freeFlightPresentation == null &&
            !completionVisible && !freeFlightCompletionCoordinator.blocksPreparation()
        for (id in listOf(R.id.homeOrigin, R.id.homeDestination)) {
            view.findViewById<View>(id).isEnabled = idle && freeFlightPresentation == null && !completionVisible
        }
        if (idle && freeFlightFrame == null) {
            view.findViewById<SourceMapHostView>(R.id.sourceMap).setFlightProgress(0f)
            renderAirportSelection(view)
        }
        renderFlightHud(view, state)
    }


    private fun setupFlightHud(view: View) {
        val hud = view.findViewById<FlightHudView>(R.id.flightHudOverlay)
        hud.callbacks = FlightHudCallbacks(
            onPauseResume = { togglePause() }, onAbandon = { confirmAbandon() },
            onToggleWindow = {
                flightWindowVisible = !flightWindowVisible
                renderFlightHud(view, sessionRepository.state.value)
            },
            onMapStyle = { toggleMapStylePanel(view) },
            onLandOrReturn = { freeFlightLanding?.landOrReturn() },
        )
        val ambientButton = hud.ambientButton
        ambientButton.text = getString(R.string.flight_hud_ambient_off)
        val order = listOf(
            AmbientSound.RAIN,
            AmbientSound.BONFIRE,
            AmbientSound.FOREST,
            AmbientSound.OCEAN,
        )
        ambientButton.setOnClickListener {
            if (!whiteNoiseEnabled) {
                Toast.makeText(
                    requireContext(),
                    R.string.flight_hud_ambient_disabled,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            val player = ambientPlayer
                ?: AmbientSoundPlayer(requireContext()).also {
                    it.setVolume(ambientVolume)
                    it.onStopped = {
                        ambientIndex = -1
                        ambientButton.text = getString(R.string.flight_hud_ambient_off)
                    }
                    ambientPlayer = it
                }
            val next = ambientIndex + 1
            if (next >= order.size) {
                AmbientPlaybackRegistry.stop(player)
                ambientIndex = -1
                ambientButton.text = getString(R.string.flight_hud_ambient_off)
                return@setOnClickListener
            }
            val sound = order[next]
            if (AmbientPlaybackRegistry.play(player) { player.play(sound) }) {
                ambientIndex = next
                ambientButton.text = getString(
                    R.string.flight_hud_ambient_on,
                    getString(ambientLabelRes(sound)),
                )
            } else {
                ambientIndex = -1
                ambientButton.text = getString(R.string.flight_hud_ambient_off)
            }
        }

        val volumeLabel = hud.volumeLabel
        val volumeSeek = hud.volumeControl
        volumeSeek.progress = (ambientVolume * 100).roundToInt()
        volumeLabel.text = getString(R.string.flight_hud_volume, volumeSeek.progress)
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = getString(R.string.flight_hud_volume, progress)
                if (!fromUser) return
                val value = progress / 100f
                ambientVolume = value
                volumeDirty = true
                ambientPlayer?.setVolume(value)
                volumeWriteJob?.cancel()
                volumeWriteJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    settingsRepository.writeAll(
                        listOf(PrefKey.WhiteNoiseEnvVolume to value),
                    )
                    volumeDirty = false
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                val value = progress / 100f
                ambientVolume = value
                volumeDirty = true
                ambientPlayer?.setVolume(value)

                // lifecycleScope 跟随 Fragment，协程只捕获 repository 与 Float，不持有 View。
                volumeWriteJob?.cancel()
                volumeWriteJob = lifecycleScope.launch {
                    settingsRepository.writeAll(
                        listOf(PrefKey.WhiteNoiseEnvVolume to value),
                    )
                    volumeDirty = false
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.observe(PrefKey.WhiteNoiseEnvVolume).collect { volume ->
                    ambientVolume = volume
                    ambientPlayer?.setVolume(volume)
                    val progress = (volume * 100).roundToInt()
                    if (volumeSeek.progress != progress) volumeSeek.progress = progress
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.observe(PrefKey.WhiteNoiseEnabled).collect { enabled ->
                    whiteNoiseEnabled = enabled
                    volumeSeek.isEnabled = enabled
                    if (!enabled) {
                        ambientPlayer?.let { AmbientPlaybackRegistry.stop(it) }
                        ambientIndex = -1
                        ambientButton.text = getString(R.string.flight_hud_ambient_off)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                view.findViewById<SourceMapHostView>(R.id.sourceMap).state.collect {
                    val current = sessionRepository.state.value
                    if (current is SessionState.Running || current is SessionState.Paused || freeFlightFrame != null)
                        renderFlightHud(view, current)
                }
            }
        }

        renderFlightHud(view, sessionRepository.state.value)
    }

    private fun ambientLabelRes(sound: AmbientSound): Int = when (sound) {
        AmbientSound.RAIN -> R.string.flight_hud_ambient_rain
        AmbientSound.BONFIRE -> R.string.flight_hud_ambient_bonfire
        AmbientSound.FOREST -> R.string.flight_hud_ambient_forest
        AmbientSound.OCEAN -> R.string.flight_hud_ambient_ocean
    }

    private fun renderFlightHud(view: View, state: SessionState) {
        val overlay = view.findViewById<FlightHudView>(R.id.flightHudOverlay) ?: return
        freeFlightFrame?.let { frame ->
            renderFreeFlightHud(view, overlay, frame.archive)
            return
        }
        val active = state is SessionState.Running || state is SessionState.Paused
        if (active) { mapPreviewVisible = false; mapPreviewBack?.isEnabled = false }
        view.findViewById<View>(R.id.groundHomeContent).visibility =
            if (active || mapPreviewVisible || freeFlightPresentation != null) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.homeMapClose).visibility =
            if (!active && mapPreviewVisible && freeFlightPresentation == null) View.VISIBLE else View.GONE
        view.findViewById<SourceMapHostView>(R.id.sourceMap).setWindowMode(active && flightWindowVisible)
        view.findViewById<SourceMapHostView>(R.id.sourceMap).setStatusControlsVisible(freeFlightPresentation == null && (active || mapPreviewVisible))
        if (!active && !mapPreviewVisible) {
            mapStylePanelVisible = false
            view.findViewById<View>(R.id.mapStyleSelectorContainer).visibility = View.GONE
        }
        val window = view.findViewById<FlightWindowView>(R.id.flightWindow)
        window.visibility = if (active && flightWindowVisible) View.VISIBLE else View.GONE
        window.setSession(active && flightWindowVisible, state is SessionState.Paused)
        if (!active) {
            overlay.render(FlightHudUiState())
            stopHudTicker()
            return
        }

        val route = sessionRepository.currentRoute()
        val origin = route.departureCode.ifBlank { "—" }
        val destination = route.arrivalCode.ifBlank { "—" }
        view.findViewById<SourceMapHostView>(R.id.sourceMap).setAirports(
            route.departure?.let { runCatching { MapAirport(it.code, it.latitude, it.longitude) }.getOrNull() },
            route.arrival?.let { runCatching { MapAirport(it.code, it.latitude, it.longitude) }.getOrNull() },
        )

        val remainingMs: Long
        val progress: Float
        when (state) {
            is SessionState.Running -> {
                val now = sessionRepository.nowMono()
                remainingMs = state.remainingMs(now)
                progress = state.progress(now)
                startHudTicker(view)
            }
            is SessionState.Paused -> {
                remainingMs = state.remainingMs()
                progress = state.progress()
                stopHudTicker()
            }
            else -> return
        }

        view.findViewById<SourceMapHostView>(R.id.sourceMap).apply {
            setFlightProgress(progress)
            contentDescription = getString(R.string.flight_hud_route, origin, destination)
        }
        val plannedMs = when (state) {
            is SessionState.Running -> state.plannedDurationMs
            is SessionState.Paused -> state.plannedDurationMs
            else -> 0L
        }
        val snapshot = if (route.distanceKm.isFinite() && route.distanceKm >= 0.0) {
            HudSnapshot.of(
                (plannedMs - remainingMs).coerceAtLeast(0L),
                (plannedMs / 60_000L).toInt(), route.distanceKm,
            )
        } else null
        overlay.render(FlightHudUiState(
            active = true, paused = state is SessionState.Paused, windowVisible = flightWindowVisible,
            lightIcons = view.findViewById<SourceMapHostView>(R.id.sourceMap).state.value.let { map ->
                // Before real styled tiles load, the actual day fallback is a light surface.
                map.phase == MapPhase.LOADED && map.styleStatus == MapStyleStatus.APPLIED &&
                    map.requestedStyleIndex == selectedMapStyle && selectedMapStyle != 6
            },
            routeText = getString(R.string.flight_hud_route, origin, destination),
            remainingTimeText = formatHudTime(remainingMs), remainingMinutesText = remainingMinutes(remainingMs).toString(),
            remainingKmText = snapshot?.remainingKmText ?: "—", traveledKmText = snapshot?.traveledKmText ?: "—",
            progress = progress,
            counters = if (route.distanceKm.isFinite() && route.distanceKm >= 0.0)
                com.projectfocus.feature.flight.hud.FlightHudCounters.remaining(
                    com.projectfocus.core.common.flight.FlightMath.remainingKm(route.distanceKm, progress),
                    (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L,
                ) else null,
        ))
    }

    private fun renderFreeFlightHud(view: View, overlay: FlightHudView, archive: com.projectfocus.core.model.flight.freeflight.FreeFlightArchive) {
        stopHudTicker()
        view.findViewById<View>(R.id.groundHomeContent).visibility = View.GONE
        val map = view.findViewById<SourceMapHostView>(R.id.sourceMap)
        mapPreviewVisible = false
        mapPreviewBack?.isEnabled = false
        view.findViewById<View>(R.id.homeMapClose).visibility = View.GONE
        map.setStatusControlsVisible(freeFlightPresentation == null)
        map.setWindowMode(flightWindowVisible)
        val selecting = archive.phase == com.projectfocus.core.model.flight.freeflight.FreeFlightPhase.LANDING_SELECTION
        val paused = archive.clock.pause != null && !selecting && archive.clock.pause != com.projectfocus.core.model.flight.freeflight.FreeFlightPause.RETURN_CAMERA
        view.findViewById<FlightWindowView>(R.id.flightWindow).apply {
            visibility = if (flightWindowVisible) View.VISIBLE else View.GONE
            setSession(flightWindowVisible, paused)
        }
        val leg = archive.approach
        val elapsed = archive.clock.activeElapsedMs
        val remaining = leg?.let { (it.durationMs - (elapsed - it.activeElapsedAtStartMs)).coerceAtLeast(0L) }
        val progress = leg?.let { if (it.durationMs == 0L) 1f else ((elapsed - it.activeElapsedAtStartMs).toDouble() / it.durationMs).toFloat().coerceIn(0f, 1f) } ?: 0f
        val counters = if (leg == null) com.projectfocus.feature.flight.hud.FlightHudCounters.freeFlightElapsed(archive)
            else com.projectfocus.feature.flight.hud.FlightHudCounters.freeFlightRemaining(archive)
        overlay.render(FlightHudUiState(
            active = true, paused = paused, windowVisible = flightWindowVisible,
            lightIcons = map.state.value.let { it.phase == MapPhase.LOADED && it.styleStatus == MapStyleStatus.APPLIED && it.requestedStyleIndex == selectedMapStyle && selectedMapStyle != 6 },
            routeText = getString(R.string.flight_hud_route, archive.departure.code, archive.landingAirport?.code ?: getString(R.string.pf_ff_free_flight_mode_name)),
            remainingTimeText = formatHudTime(remaining ?: elapsed), remainingMinutesText = remainingMinutes(remaining ?: elapsed).toString(),
            traveledKmText = String.format(Locale.US, "%.1f", archive.cumulativeDistanceKm),
            remainingKmText = counters?.takeUnless { it.elapsed }?.metrics?.distance?.value?.toString().orEmpty(),
            progress = progress, counters = counters,
            freeFlightLandControl = when {
                selecting -> com.projectfocus.feature.flight.hud.FreeFlightLandControl.RETURN_TO_CRUISE
                archive.phase == com.projectfocus.core.model.flight.freeflight.FreeFlightPhase.CRUISING && !paused -> com.projectfocus.feature.flight.hud.FreeFlightLandControl.LAND
                else -> com.projectfocus.feature.flight.hud.FreeFlightLandControl.HIDDEN
            },
        ))
    }

    private fun startHudTicker(view: View) {
        if (hudTickerJob?.isActive == true) return
        hudTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val state = sessionRepository.state.value
                if (state !is SessionState.Running) break
                renderFlightHud(view, state)
                delay(1_000L)
            }
        }
    }

    private fun stopHudTicker() {
        hudTickerJob?.cancel()
        hudTickerJob = null
    }

    private fun togglePause() {
        freeFlightFrame?.let { frame ->
            viewLifecycleOwner.lifecycleScope.launch {
                val result = if (frame.archive.clock.pause == null) freeFlightSessions.pause(frame.owner) else freeFlightSessions.resume(frame.owner)
                if (result !is com.projectfocus.service.focus.freeflight.FreeFlightCommandResult.Committed) showHudActionFailed()
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = when (sessionRepository.state.value) {
                is SessionState.Running -> sessionRepository.pause()
                is SessionState.Paused -> sessionRepository.resume()
                else -> true
            }
            if (!ok) showHudActionFailed()
        }
    }

    private fun confirmAbandon() {
        val freeFlight = freeFlightFrame
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.flight_hud_abandon_title)
            .setMessage(R.string.flight_hud_abandon_message)
            .setPositiveButton(R.string.flight_hud_confirm_abandon) { _, _ ->
                if (freeFlight != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = freeFlightSessions.cancel(freeFlight.owner)
                        if (result !is com.projectfocus.service.focus.freeflight.FreeFlightCommandResult.Committed) showHudActionFailed()
                    }
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!sessionRepository.abandon()) showHudActionFailed()
                }
            }
            .setNegativeButton(R.string.flight_hud_cancel_abandon, null)
            .show()
    }

    private fun showHudActionFailed() {
        android.widget.Toast.makeText(
            requireContext(),
            R.string.flight_hud_action_failed,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun formatHudTime(ms: Long): String {
        val totalSeconds = ((ms.coerceAtLeast(0L) + 999L) / 1_000L).toInt()
        return String.format(
            Locale.US,
            "%02d:%02d",
            totalSeconds / 60,
            totalSeconds % 60,
        )
    }

    override fun onPause() {
        super.onPause()
        view?.findViewById<FlightWindowView>(R.id.flightWindow)?.setHostResumed(false)

        if (!volumeDirty) return
        volumeDirty = false
        val value = ambientVolume
        lifecycleScope.launch {
            settingsRepository.writeAll(
                listOf(PrefKey.WhiteNoiseEnvVolume to value),
            )
        }
    }

    override fun onDestroyView() {
        mapPreviewBack = null
        mapPreviewVisible = false
        freeFlightCompletion?.close(); freeFlightCompletion = null
        completionVisible = false
        freeFlightLanding?.close(); freeFlightLanding = null
        freeFlightFlightPresentation?.close()
        freeFlightFlightPresentation = null
        freeFlightFrame = null
        freeFlightMapBinding?.close()
        freeFlightMapBinding = null
        freeFlightPresentation?.close()
        freeFlightPresentation = null
        prefs.unregisterOnSharedPreferenceChangeListener(selectionChanges)
        selectionReloadJob?.cancel()
        selectionReloadJob = null
        savedMapState = Bundle().also { state ->
            view?.findViewById<SourceMapHostView>(R.id.sourceMap)?.saveMapState(state)
        }
        view?.findViewById<FlightWindowView>(R.id.flightWindow)?.let {
            flightWindowPosition = it.playbackPositionMs()
            it.setHostResumed(false)
        }
        stopHudTicker()
        volumeWriteJob?.cancel()
        volumeWriteJob = null
        ambientPlayer?.onStopped = null
        ambientPlayer?.let { AmbientPlaybackRegistry.release(it) }
        ambientPlayer = null
        ambientIndex = -1
        super.onDestroyView()
    }

    private fun remainingMinutes(ms: Long): Int = ((ms + 59_999L) / 60_000L).toInt()

















    private fun consumeEntryAction() {
        val nav = runCatching { findNavController() }.getOrNull() ?: return
        val handle = nav.currentBackStackEntry?.savedStateHandle ?: return
        val action = handle.get<EntryAction>(MainActivity.KEY_ENTRY_ACTION) ?: return
        handle.remove<EntryAction>(MainActivity.KEY_ENTRY_ACTION)

        when (action) {
            is EntryAction.QuickStart -> {
                if (action.minutes in 1..180) selectedMinutes = action.minutes
                timber.log.Timber.i("快捷开始：预置 %d 分钟", selectedMinutes)
            }
            is EntryAction.Unknown -> Unit   // Activity 已记过日志，这里不重复
            EntryAction.None -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let(::restorePersistedSelection)
        view?.findViewById<FlightWindowView>(R.id.flightWindow)?.setHostResumed(true)

        //   热启动时 Fragment 已经存在，onViewCreated 不会再跑一次，
        //   而 Activity.onNewIntent 刚写进 savedStateHandle 的动作只有
        //   "重新可见"这一刻才能被读到。放 onViewCreated 会漏掉热启动这条路径。
        consumeEntryAction()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FLIGHT_WINDOW, flightWindowVisible)
        outState.putLong(STATE_FLIGHT_WINDOW_POSITION,
            view?.findViewById<FlightWindowView>(R.id.flightWindow)?.playbackPositionMs() ?: flightWindowPosition)
        val state = Bundle()
        view?.findViewById<SourceMapHostView>(R.id.sourceMap)?.saveMapState(state)
        outState.putBundle(STATE_MAP, state.takeUnless { it.isEmpty } ?: savedMapState)
        super.onSaveInstanceState(outState)
    }

    private fun safeNavigate(actionId: Int) {
        runCatching { findNavController().navigate(actionId) }
            .onFailure { timber.log.Timber.w(it, "导航失败（可能是连点导致当前目的地已切换） action=%s", actionId) }
    }

    private fun safeNavigate(actionId: Int, args: Bundle) {
        runCatching { findNavController().navigate(actionId, args) }
            .onFailure { timber.log.Timber.w(it, "导航失败（可能是连点导致当前目的地已切换） action=%s", actionId) }
    }

    private companion object {
        const val STATE_MAP = "source_map_state"
        const val STATE_FLIGHT_WINDOW = "flight_window_visible"
        const val STATE_FLIGHT_WINDOW_POSITION = "flight_window_position"
        const val PREF_ORIGIN = "origin_code"
        const val PREF_DESTINATION = "destination_code"
    }
}
