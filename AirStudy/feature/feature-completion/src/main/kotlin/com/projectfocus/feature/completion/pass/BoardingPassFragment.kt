package com.projectfocus.feature.completion.pass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.projectfocus.feature.completion.R
import com.projectfocus.feature.completion.databinding.FragmentBoardingPassPageBinding













class BoardingPassFragment : Fragment() {

    private var _binding: FragmentBoardingPassPageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBoardingPassPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pass = arguments?.getSerializable(ARG_PASS) as? BoardingPassData
        if (pass == null) {
            // 参数缺失不是可恢复状态：直接退回上一页，不展示空票面。
            findNavController().popBackStack()
            return
        }

        val paper = binding.root.findViewById<BoardingPassPaperLayout>(R.id.boarding_pass_root)
        BoardingPassBinder(paper).bind(pass)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnDone.setOnClickListener { findNavController().popBackStack() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PASS: String = "arg_boarding_pass"

        fun newInstance(pass: BoardingPassData): BoardingPassFragment =
            BoardingPassFragment().apply {
                arguments = Bundle().apply { putSerializable(ARG_PASS, pass) }
            }
    }
}
