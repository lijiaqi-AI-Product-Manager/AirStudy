package com.projectfocus.service.focus.monitor

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.projectfocus.service.focus.R
import com.projectfocus.service.focus.databinding.ActivityBlockedAppBinding
import timber.log.Timber


class BlockedAppActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedAppBinding
    private var blockedAtMs: Long = 0L
    private var giveUpArmed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }


        //   minSdk = 26，那个分支永远不执行（lint ObsoleteSdkInt）。

        binding = ActivityBlockedAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        blockedAtMs = android.os.SystemClock.elapsedRealtime()

        val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.focus_blocked_unknown_app)
        val category = intent.getStringExtra(EXTRA_CATEGORY)

        binding.tvBody.text = if (category.isNullOrBlank()) {
            getString(R.string.focus_blocked_body, label)
        } else {
            getString(R.string.focus_blocked_body_category, label, category)
        }

        binding.btnBack.setOnClickListener { leaveBlockedScreen() }
        binding.btnBack.setOnLongClickListener {
            armGiveUp()
            true
        }

        binding.btnGiveUp.setOnClickListener {
            if (giveUpArmed) giveUpSession() else armGiveUp()
        }


        //   如果不拦截返回键，用户按一下返回就回到被屏蔽的 App 了 ——
        //   这是最容易被忽略、也最致命的漏洞。
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveBlockedScreen()
        })

        startElapsedTicker()
    }











    private fun startElapsedTicker() {
        val tick = object : Runnable {
            override fun run() {
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                val seconds = (android.os.SystemClock.elapsedRealtime() - blockedAtMs) / 1000
                binding.tvElapsed.text = getString(R.string.focus_blocked_elapsed, seconds)
                binding.root.postDelayed(this, 1_000L)
            }
        }
        binding.root.post(tick)
    }








    private fun leaveBlockedScreen() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            runCatching { startActivity(launch) }.onFailure {
                Timber.w(it, "无法拉起主界面，降级为回到桌面")
                goHome()
            }
        } else {
            goHome()
        }
        finish()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }
    }

    /** 第一次点了「放弃」按钮 → 变成「再点一次确认」 */
    private fun armGiveUp() {
        if (giveUpArmed) return
        giveUpArmed = true
        binding.btnGiveUp.visibility = View.VISIBLE
        binding.btnGiveUp.text = getString(R.string.focus_blocked_give_up_confirm)
        // 3 秒后自动解除武装 —— 否则「确认」状态会一直挂着，
        // 用户下次误触就直接放弃了。
        binding.btnGiveUp.postDelayed({
            if (!isFinishing) {
                giveUpArmed = false
                binding.btnGiveUp.text = getString(R.string.focus_blocked_give_up)
            }
        }, ARM_TIMEOUT_MS)
    }









    private fun giveUpSession() {
        startService(
            Intent(this, FocusMonitorService::class.java)
                .setAction(FocusMonitorService.ACTION_STOP),
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_blocked_package"
        const val EXTRA_LABEL = "extra_blocked_label"
        const val EXTRA_CATEGORY = "extra_blocked_category"
        const val EXTRA_DURATION_MS = "extra_blocked_duration"

        private const val ARM_TIMEOUT_MS = 3_000L
    }
}
