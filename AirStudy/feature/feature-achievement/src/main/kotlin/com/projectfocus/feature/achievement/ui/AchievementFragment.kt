package com.projectfocus.feature.achievement.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.feature.achievement.R
import com.projectfocus.feature.achievement.catalog.AchievementCatalog
import com.projectfocus.feature.achievement.catalog.AchievementDefinition
import com.projectfocus.feature.achievement.catalog.AchievementEvaluator
import com.projectfocus.feature.achievement.catalog.AchievementRule
import com.projectfocus.feature.achievement.catalog.AchievementStats
import com.projectfocus.feature.achievement.databinding.FragmentAchievementsBinding
import com.projectfocus.feature.achievement.databinding.ItemAchievementBinding
import com.projectfocus.feature.achievement.databinding.ItemAchievementSectionBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch


@AndroidEntryPoint
class AchievementFragment : Fragment(R.layout.fragment_achievements) {

    @Inject
    lateinit var flightHistoryDao: FlightHistoryDao

    private var binding: FragmentAchievementsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentAchievementsBinding.bind(view)
        this.binding = binding

        binding.btnAchievementsBack.setOnClickListener {
            findNavController().navigateUp()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flightHistoryDao.observeAll().collect { rows ->
                    render(binding, rows)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentAchievementsBinding, rows: List<com.projectfocus.core.database.entity.FlightHistoryEntity>) {
        val stats = AchievementStats.from(rows)
        val all = AchievementCatalog.all
        val unlocked = all.count { AchievementEvaluator.isUnlocked(it, stats) }
        val supported = AchievementEvaluator.supportedCount()

        binding.tvAchievementsSummary.text = getString(
            R.string.achievements_summary,
            unlocked,
            all.size,
            supported,
        )

        binding.achievementList.removeAllViews()
        val inflater = LayoutInflater.from(binding.root.context)
        val supportedDefinitions = all.filter { it.rule != AchievementRule.Unsupported }
        val unsupportedDefinitions = all.filter { it.rule == AchievementRule.Unsupported }

        addSection(binding, inflater, R.string.achievements_section_supported)
        supportedDefinitions.forEach { definition ->
            addItem(binding, inflater, definition, stats)
        }

        addSection(binding, inflater, R.string.achievements_section_unsupported)
        unsupportedDefinitions.forEach { definition ->
            addItem(binding, inflater, definition, stats)
        }
    }

    private fun addSection(
        binding: FragmentAchievementsBinding,
        inflater: LayoutInflater,
        titleRes: Int,
    ) {
        val header = ItemAchievementSectionBinding.inflate(inflater, binding.achievementList, false)
        header.tvAchievementSection.setText(titleRes)
        binding.achievementList.addView(header.root)
    }

    private fun addItem(
        binding: FragmentAchievementsBinding,
        inflater: LayoutInflater,
        definition: AchievementDefinition,
        stats: AchievementStats,
    ) {
        val item = ItemAchievementBinding.inflate(inflater, binding.achievementList, false)
        item.tvAchievementTag.text = getString(definition.tagRes)
        item.tvAchievementDesc.text = getString(definition.descRes)

        val unsupported = definition.rule == AchievementRule.Unsupported
        val isUnlocked = !unsupported && AchievementEvaluator.isUnlocked(definition, stats)
        when {
            unsupported -> {
                item.tvAchievementStatus.setText(R.string.achievements_status_unsupported)
                item.tvAchievementStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.darker_gray),
                )
            }
            isUnlocked -> {
                item.tvAchievementStatus.setText(R.string.achievements_status_unlocked)
                item.tvAchievementStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark),
                )
            }
            else -> {
                item.tvAchievementStatus.setText(R.string.achievements_status_locked)
                item.tvAchievementStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.darker_gray),
                )
            }
        }
        binding.achievementList.addView(item.root)
    }
}
