package com.projectfocus.app.shield

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.checkbox.MaterialCheckBox
import com.projectfocus.app.R
import com.projectfocus.app.databinding.FragmentShieldAppsBinding
import com.projectfocus.app.databinding.ShieldAppsHeaderBinding
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.service.focus.monitor.FocusPermissions
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShieldAppsFragment : Fragment(R.layout.fragment_shield_apps) {
    @Inject lateinit var settings: SettingsRepository

    private var header: ShieldAppsHeaderBinding? = null
    private var adapter: PickerAdapter? = null
    private var apps: List<ShieldApp>? = null
    private var selected: Set<String>? = null
    private var enabled = false
    private var loadFailed = false
    private var settingsFailed = false
    private var pending = false
    private var loading: Job? = null
    private var observing: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentShieldAppsBinding.bind(view)
        val controls = ShieldAppsHeaderBinding.inflate(layoutInflater, ui.apps, false)
        header = controls
        SkySurfaces.card(controls.shieldHeader, radius = 24f, elevation = 4f)
        SkySurfaces.secondary(controls.search, radius = 16f)
        SkySurfaces.secondary(controls.recover)
        SkyPressFeedback.install(ui.back)
        SkyPressFeedback.install(controls.recover)
        ViewCompat.setAccessibilityHeading(ui.title, true)
        ui.back.setOnClickListener { findNavController().navigateUp() }
        controls.search.doAfterTextChanged { render() }
        ui.apps.addHeaderView(controls.root, null, false)
        ui.apps.setItemsCanFocus(true)
        adapter = PickerAdapter(requireContext(), ::setSelected).also { ui.apps.adapter = it }
        controls.recover.setOnClickListener {
            if (loadFailed || settingsFailed) {
                observeSelection()
                loadApps()
            } else {
                openProtectionSettings()
            }
        }
        observeSelection()
        render()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun observeSelection() {
        observing?.cancel()
        settingsFailed = false
        observing = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.observeAllKeys(listOf(PrefKey.ShieldedPackages, PrefKey.BlacklistEnabled))
                    .catch { error ->
                        if (error is CancellationException) throw error
                        settingsFailed = true
                        render()
                    }
                    .collect { values ->
                        @Suppress("UNCHECKED_CAST")
                        selected = values[PrefKey.ShieldedPackages.name] as Set<String>
                        enabled = values[PrefKey.BlacklistEnabled.name] as Boolean
                        settingsFailed = false
                        render()
                    }
            }
        }
    }

    private fun loadApps() {
        loading?.cancel()
        val context = requireContext()
        val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
        loadFailed = false
        loading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                apps = InstalledShieldApps(context).list(locale)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loadFailed = true
            }
            render()
        }
    }

    private fun setSelected(item: ShieldApp, checked: Boolean) {
        if (pending || settingsFailed || loadFailed || selected == null || (checked && !item.installed)) return
        pending = true
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                selected = settings.setShieldedPackage(item.packageName, checked)
            } catch (_: IOException) {
                Toast.makeText(requireContext(), R.string.shield_apps_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                pending = false
                render()
            }
        }
    }

    private fun permissionNeeded(context: Context): FocusPermissions.Item? = when {
        !enabled -> null
        !FocusPermissions.hasUsageStats(context) -> FocusPermissions.Item.USAGE_STATS
        !FocusPermissions.hasOverlay(context) -> FocusPermissions.Item.OVERLAY
        else -> null
    }

    private fun openProtectionSettings() {
        val context = requireContext()
        val needed = permissionNeeded(context) ?: return
        try {
            startActivity(FocusPermissions.settingsIntent(context, needed))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.shield_apps_permission_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val ui = header ?: return
        val context = ui.root.context
        val available = apps
        val saved = selected
        val failed = loadFailed || settingsFailed
        val ready = !failed && available != null && saved != null
        val needed = permissionNeeded(context)
        ui.search.isEnabled = ready
        ui.progress.isVisible = !failed && !ready && ValueAnimator.areAnimatorsEnabled()
        ui.recover.isVisible = failed || ready && saved.isNotEmpty() && needed != null
        ui.recover.setText(when {
            failed -> R.string.shield_apps_retry
            needed == FocusPermissions.Item.USAGE_STATS -> R.string.shield_apps_usage_access
            else -> R.string.shield_apps_overlay_access
        })
        ui.summary.text = if (ready) {
            resources.getQuantityString(R.plurals.shield_apps_selected, saved.size, saved.size)
        } else {
            getString(R.string.shield_apps_subtitle)
        }
        ui.permissionNotice.isVisible = ready && (!enabled || needed != null && saved.isNotEmpty())
        ui.permissionNotice.setText(if (enabled) R.string.shield_apps_permission_needed else R.string.shield_apps_disabled)
        val visible = if (ready) {
            val unavailable = saved - available.map { it.packageName }.toSet()
            val all = available + unavailable.sorted().map { ShieldApp(it, it, installed = false) }
            val term = ui.search.text.toString().trim()
            all.filter { it.name.contains(term, ignoreCase = true) || it.packageName.contains(term, ignoreCase = true) }
        } else {
            emptyList()
        }
        adapter?.show(visible, saved.orEmpty(), !pending)
        ui.empty.isVisible = visible.isEmpty()
        ui.empty.setText(when {
            failed -> R.string.shield_apps_load_failed
            !ready -> R.string.shield_apps_loading
            ui.search.text.isNotBlank() -> R.string.shield_apps_no_match
            else -> R.string.shield_apps_empty
        })
    }

    override fun onDestroyView() {
        header = null
        adapter = null
        apps = null
        selected = null
        loadFailed = false
        super.onDestroyView()
    }

    private class PickerAdapter(
        private val context: Context,
        private val select: (ShieldApp, Boolean) -> Unit,
    ) : BaseAdapter() {
        private var apps = emptyList<ShieldApp>()
        private var selected = emptySet<String>()
        private var editable = true
        fun show(items: List<ShieldApp>, checked: Set<String>, enabled: Boolean) {
            if (apps == items && selected == checked && editable == enabled) return
            apps = items
            selected = checked
            editable = enabled
            notifyDataSetChanged()
        }

        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): ShieldApp = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val checkbox = convertView as? MaterialCheckBox ?: MaterialCheckBox(context).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                minHeight = SkySurfaces.dp(context, 58f)
                val pad = SkySurfaces.dp(context, 12f)
                setPaddingRelative(pad, pad, pad, pad)
                setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_ink))
                textSize = 16f
                val surfaces = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), SkySurfaces.shape(context, 14f, solid = true).apply {
                        setColor(context.getColor(com.projectfocus.core.ui.R.color.sky_selected))
                    })
                    addState(intArrayOf(), SkySurfaces.shape(context, 14f))
                }
                background = RippleDrawable(ColorStateList.valueOf(0x186A8FE8), surfaces, null)
            }
            checkbox.setOnCheckedChangeListener(null)
            checkbox.text = if (item.installed) item.name else context.getString(R.string.shield_apps_unavailable, item.name)
            checkbox.isChecked = item.packageName in selected
            checkbox.jumpDrawablesToCurrentState()
            checkbox.isEnabled = editable
            checkbox.setOnCheckedChangeListener { _, checked -> select(item, checked) }
            return checkbox
        }
    }
}
