package com.projectfocus.feature.sound

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch


@AndroidEntryPoint
class SoundFragment : Fragment(R.layout.fragment_sound) {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var player: AmbientSoundPlayer? = null
    private var enabled: Boolean = false
    private var volume: Float = 0.5f
    private var selected: AmbientSound? = null

    private lateinit var switch: SwitchCompat
    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var playingLabel: TextView
    private lateinit var rows: Map<AmbientSound, TextView>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        player = AmbientSoundPlayer(requireContext())
        rows = mapOf(
            AmbientSound.RAIN to view.findViewById(R.id.soundTrackRain),
            AmbientSound.BONFIRE to view.findViewById(R.id.soundTrackBonfire),
            AmbientSound.FOREST to view.findViewById(R.id.soundTrackForest),
            AmbientSound.OCEAN to view.findViewById(R.id.soundTrackOcean),
        )
        switch = view.findViewById(R.id.soundEnabledSwitch)
        volumeSeek = view.findViewById(R.id.soundVolumeSeek)
        volumeLabel = view.findViewById(R.id.soundVolumeLabel)
        playingLabel = view.findViewById(R.id.soundPlayingLabel)

        view.findViewById<View>(R.id.btnSoundBack).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<View>(R.id.btnSoundStop).setOnClickListener { stop() }

        switch.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            applyEnabled()
            if (!checked) stop()
            lifecycleScope.launch {
                settingsRepository.writeAll(
                    listOf(PrefKey.WhiteNoiseEnabled to checked),
                )
            }
        }

        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                volume = progress / 100f
                volumeLabel.text = getString(R.string.sound_volume_fmt, progress)
                player?.setVolume(volume)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val value = volume
                lifecycleScope.launch {
                    settingsRepository.writeAll(
                        listOf(PrefKey.WhiteNoiseEnvVolume to value),
                    )
                }
            }
        })

        rows.forEach { (sound, row) ->
            row.setOnClickListener { play(sound) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.observe(PrefKey.WhiteNoiseEnabled).collect { value ->
                    enabled = value
                    switch.isChecked = value
                    applyEnabled()
                    if (!value) stop()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.observe(PrefKey.WhiteNoiseEnvVolume).collect { value ->
                    volume = value.coerceIn(0f, 1f)
                    player?.setVolume(volume)
                    val percent = (volume * 100).toInt()
                    if (volumeSeek.progress != percent) volumeSeek.progress = percent
                    volumeLabel.text = getString(R.string.sound_volume_fmt, percent)
                }
            }
        }

        applyEnabled()
    }

    private fun applyEnabled() {
        rows.values.forEach { row ->
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.4f
        }
        volumeSeek.isEnabled = enabled
        volumeLabel.alpha = if (enabled) 1f else 0.4f
    }

    private fun play(sound: AmbientSound) {
        if (!enabled) {
            Toast.makeText(requireContext(), R.string.sound_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val player = player ?: return
        val ok = AmbientPlaybackRegistry.play(player) { player.play(sound) }
        if (!ok) {
            Toast.makeText(requireContext(), R.string.sound_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        selected = sound
        rows.forEach { (track, row) ->
            row.alpha = if (track == sound) 1f else 0.55f
        }
        playingLabel.text = getString(R.string.sound_playing_fmt, getString(labelRes(sound)))
    }

    private fun stop() {
        player?.let { AmbientPlaybackRegistry.stop(it) }
        selected = null
        rows.values.forEach { it.alpha = if (enabled) 1f else 0.4f }
        playingLabel.text = ""
    }

    private fun labelRes(sound: AmbientSound): Int = when (sound) {
        AmbientSound.RAIN -> R.string.sound_track_rain
        AmbientSound.BONFIRE -> R.string.sound_track_bonfire
        AmbientSound.FOREST -> R.string.sound_track_forest
        AmbientSound.OCEAN -> R.string.sound_track_ocean
    }

    override fun onDestroyView() {
        player?.let { AmbientPlaybackRegistry.release(it) }
        player = null
        super.onDestroyView()
    }
}
