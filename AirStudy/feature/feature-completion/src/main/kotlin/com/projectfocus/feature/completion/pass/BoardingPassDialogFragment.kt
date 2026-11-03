package com.projectfocus.feature.completion.pass

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.projectfocus.feature.completion.R
import com.projectfocus.feature.completion.databinding.FragmentBoardingPassBinding













class BoardingPassDialogFragment : DialogFragment() {

    private var _binding: FragmentBoardingPassBinding? = null
    private val binding: FragmentBoardingPassBinding
        get() = checkNotNull(_binding) { "Binding accessed outside view lifecycle" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题：窗口本身不画背景，遮罩由 XML 根布局负责。
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBoardingPassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("DEPRECATION")
        val pass = arguments?.getSerializable(ARG_PASS) as? BoardingPassData
        if (pass == null) {
            // 参数丢失时直接关闭：宁可不动，也不展示一张空白登机牌。
            dismiss()
            return
        }

        val paper = view.findViewById<BoardingPassPaperLayout>(R.id.boarding_pass_root)
        BoardingPassBinder(paper).bind(pass)
        binding.btnClose.setOnClickListener { dismiss() }

        isCancelable = true
        dialog?.setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG: String = "boarding_pass_dialog"

        private const val ARG_PASS: String = "arg_pass"

        fun newInstance(pass: BoardingPassData): BoardingPassDialogFragment =
            BoardingPassDialogFragment().apply {
                arguments = Bundle().apply { putSerializable(ARG_PASS, pass) }
            }
    }
}
