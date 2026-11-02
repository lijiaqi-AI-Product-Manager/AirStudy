package com.projectfocus.feature.booking.seat

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.projectfocus.core.common.coroutines.runCatchingCancellable
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.common.nav.SeatSceneResult
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.core.ui.scene.ScenePickerOption
import com.projectfocus.feature.booking.R
import com.projectfocus.feature.booking.draft.BookingDraft
import com.projectfocus.feature.booking.draft.BookingDraftStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch


@AndroidEntryPoint
class SeatSelectionFragment : Fragment(R.layout.fragment_seat_selection) {
    @Inject lateinit var historyDao: FlightHistoryDao
    private lateinit var seatMap: SeatSelectionMapView
    private lateinit var confirmButton: MaterialButton
    private var selectedSeat: String? = null
    private var selectedScene: SeatSceneChoice? = null
    private var sceneDialog: Dialog? = null
    private var sceneEditorSession: SeatSceneEditorSession? = null
    private var confirming = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        seatMap = view.findViewById(R.id.seatMap)
        confirmButton = view.findViewById(R.id.btnConfirmSeat)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        seatMap.setLightCabin(!dark)

        confirmButton.backgroundTintList = ColorStateList.valueOf(if (dark) Color.WHITE else Color.BLACK)
        confirmButton.setTextColor(if (dark) Color.BLACK else Color.WHITE)
        val args = arguments ?: Bundle.EMPTY
        val flightNo = args.getString(ARG_FLIGHT_NO).orEmpty()
        val fromCode = args.getString(ARG_FROM).orEmpty()
        val toCode = args.getString(ARG_TO).orEmpty()
        val recordId = args.getString(ARG_RECORD_ID).orEmpty()
        val rawCurrentSeat = args.getString(ARG_SEAT).orEmpty()
        val currentSeat = canonicalSeat(rawCurrentSeat)
        val draftKey = seatDraftKey(recordId = recordId, draftKey = args.getString(ARG_DRAFT_KEY).orEmpty(), flightNo = flightNo)
        val store = SeatSelectionStore(requireContext())
        val sceneHost = activity as? SeatSceneChoicesHost
        val initialCatalog = sceneHost?.seatSceneChoices(args)
        val choices = (initialCatalog as? SeatSceneChoices.Ready)?.values.orEmpty()
            .filter { it.optionId.isNotBlank() }
        val preset = sceneHost?.presetSeatScene(args)
            ?.takeIf { it.optionId.isNotBlank() && it.title.trim { char -> char <= '\u0020' }.isNotEmpty() }
        fun updateConfirmation() {
            confirmButton.visibility = if (selectedSeat != null && selectedScene != null) View.VISIBLE else View.GONE
            confirmButton.isEnabled = !confirming
            seatMap.interactionEnabled = !confirming
            selectedSeat?.let { seat ->
                confirmButton.contentDescription = getString(R.string.seat_selection_selected_fmt, seat)
            }
        }
        fun choose(seat: String, scene: SeatSceneChoice) {
            if (confirming || this.view !== view) return
            selectedSeat = seat
            selectedScene = scene
            seatMap.select(seat, scene)
            // This is still an editable local seat draft, never a booking claim.
            if (!store.save(draftKey, seat)) toast(R.string.seat_selection_save_failed)
            updateConfirmation()
        }

        seatMap.onSeatTapped = { seat, anchor ->
            if (!confirming) {
                if (preset != null) choose(seat, preset) else when (val catalog = sceneHost?.seatSceneChoices(args)) {
                    is SeatSceneChoices.Ready -> {
                    var currentChoices = catalog.values.filter { it.optionId.isNotBlank() }
                    sceneDialog?.dismiss()
                    sceneEditorSession?.close()
                    val editor = sceneHost.createSeatSceneEditor(args)
                    sceneEditorSession = editor
                    fun reload(): List<ScenePickerOption> {
                        when (val refreshed = sceneHost.seatSceneChoices(args)) {
                            is SeatSceneChoices.Ready -> currentChoices = refreshed.values.filter { it.optionId.isNotBlank() }
                            else -> toast(R.string.seat_selection_scenes_unavailable)
                        }
                        return currentChoices.map { ScenePickerOption(it.optionId, it.title, it.iconRes, it.color) }
                    }
                    val dialog = AnchoredScenePickerDialog(
                        context = requireContext(), anchor = anchor,
                        seatCaption = getString(R.string.pf_seat_scene_seat_format, seat.trimStart('0')),
                        options = currentChoices.map { ScenePickerOption(it.optionId, it.title, it.iconRes, it.color) },
                        onSelected = { option ->
                            when (val refreshed = sceneHost.seatSceneChoices(args)) {
                                is SeatSceneChoices.Ready -> refreshed.values.firstOrNull { it.optionId == option.id }
                                    ?.let { choose(seat, it) }
                                else -> toast(R.string.seat_selection_scenes_unavailable)
                            }
                        },
                        onAdd = editor?.let { session -> { changed: () -> Unit -> session.add(changed) } },
                        onRemove = editor?.let { session ->
                            { option: ScenePickerOption, changed: () -> Unit -> session.remove(option.id, changed) }
                        },
                        reloadOptions = if (editor == null) null else ::reload,
                    )
                    dialog.setOnDismissListener {
                        editor?.close()
                        if (sceneEditorSession === editor) sceneEditorSession = null
                        if (sceneDialog === dialog) sceneDialog = null
                    }
                    sceneDialog = dialog
                    dialog.show()
                    }
                    SeatSceneChoices.Loading -> toast(R.string.seat_selection_scenes_loading)
                    SeatSceneChoices.Unavailable, null -> toast(R.string.seat_selection_scenes_unavailable)
                }
            }
        }
        if (rawCurrentSeat.isNotBlank() && currentSeat == null) {
            toast(R.string.seat_selection_invalid_record_seat, Toast.LENGTH_LONG)
        }
        selectedSeat = canonicalSeat(savedInstanceState?.getString(KEY_SEAT)) ?: currentSeat ?: store.load(draftKey)
        @Suppress("DEPRECATION")
        val restoredScene = savedInstanceState?.getSerializable(KEY_SCENE) as? SeatSceneChoice
        selectedScene = restoredScene?.let { old -> choices.firstOrNull { it.optionId == old.optionId } ?: preset?.takeIf { it.optionId == old.optionId } } ?: preset
        selectedSeat?.let { seatMap.select(it, selectedScene) }
        // Legacy seat-only drafts cannot fabricate a scene; another chair tap opens the picker.
        updateConfirmation()
        confirmButton.setOnClickListener {
            val seat = selectedSeat ?: return@setOnClickListener
            if (selectedScene == null || confirming) return@setOnClickListener
            if (!store.save(draftKey, seat)) {
                toast(R.string.seat_selection_save_failed)
                return@setOnClickListener
            }
            confirming = true
            updateConfirmation()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (!persistSeat(recordId, seat)) return@launch
                    if (!BookingDraftStore(requireContext()).save(
                            BookingDraft(
                                flightNo = flightNo, fromCode = fromCode, toCode = toCode,
                                seat = seat, savedAtMillis = System.currentTimeMillis(), recordId = recordId,
                            ), draftKey = draftKey,
                        )
                    ) {
                        toast(R.string.seat_selection_save_failed)
                        return@launch
                    }
                    parentFragmentManager.setFragmentResult(
                        REQUEST_KEY,
                        Bundle().apply {
                            putSerializable(SeatDraftRequests.RESULT_DATA, SeatSelectionResult(
                                seat, draftKey, recordId,
                                selectedScene?.let { SeatSceneResult(it.optionId, it.iconKey, it.title, it.type) },
                            ))
                        },
                    )
                    val popped = runCatching { findNavController().popBackStack() }.getOrDefault(false)
                    if (!popped) Toast.makeText(requireContext(), getString(R.string.seat_selection_confirmed_fmt, seat), Toast.LENGTH_SHORT).show()
                } finally {
                    confirming = false
                    if (this@SeatSelectionFragment.view === view) updateConfirmation()
                }
            }
        }
    }

    /** Keep record isolation and actual read-back before emitting the typed result. */
    private suspend fun persistSeat(recordId: String, seat: String): Boolean {
        if (recordId.isBlank()) return true
        val saved = runCatchingCancellable {
            val entity = historyDao.findById(recordId) ?: return@runCatchingCancellable false
            if (entity.seat != seat) historyDao.update(entity.copy(seat = seat))
            historyDao.findById(recordId)?.seat == seat
        }.getOrElse { false }
        if (!saved) toast(R.string.seat_selection_record_update_failed)
        return saved
    }

    private fun toast(resource: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(requireContext(), resource, duration).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SEAT, selectedSeat)
        outState.putSerializable(KEY_SCENE, selectedScene)
    }

    override fun onDestroyView() {
        seatMap.onSeatTapped = null
        seatMap.onSeatSelected = null
        sceneDialog?.dismiss()
        sceneDialog = null
        sceneEditorSession?.close()
        sceneEditorSession = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_FLIGHT_NO = "flightNo"
        const val ARG_FROM = "from"
        const val ARG_TO = "to"
        const val ARG_RECORD_ID = "recordId"
        const val ARG_SEAT = "seat"
        const val ARG_DRAFT_KEY = "draftKey"
        const val REQUEST_KEY = SeatDraftRequests.SELECTION
        const val REQUEST_CLEAR_DRAFT = SeatDraftRequests.CLEAR
        const val RESULT_SEAT = SeatDraftRequests.SEAT
        private const val KEY_SEAT = "selectedSeat"
        private const val KEY_SCENE = "selectedScene"
    }
}
