package com.projectfocus.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.projectfocus.app.databinding.FragmentPlaceholderBinding
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.ui.glass.GlassLevel
import com.projectfocus.core.ui.glass.LiquidGlassView


class PlaceholderFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 之后被访问" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvTitle.text = getString(R.string.skeleton_title)
        binding.tvGlassLevel.text = when (binding.glassSelfCheck.getGlassLevel()) {
            GlassLevel.FULL -> getString(R.string.glass_level_full)
            GlassLevel.BLUR -> getString(R.string.glass_level_blur)
            GlassLevel.SOLID -> getString(R.string.glass_level_solid)
        }

        binding.tvDetail.text = buildString {
            appendLine("45 分钟 → ${"%.0f".format(FlightMath.distanceKmForMinutes(45))} km")
            appendLine("180 分钟 → ${"%.0f".format(FlightMath.distanceKmForMinutes(180))} km")
            appendLine("巡航速度 ${FlightMath.SPEED_KMH.toInt()} km/h")
            appendLine()
            append(getString(R.string.skeleton_modules))
        }
    }

    override fun onDestroyView() {

        //   一个 1080p 的 ARGB_8888 是 8MB，不解引用等到 GC 会有明显的「退出页面卡一下」。
        binding.glassSelfCheck.getGlassParams()
        _binding = null
        super.onDestroyView()
    }
}
