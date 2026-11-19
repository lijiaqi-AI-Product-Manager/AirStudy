package com.projectfocus.core.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RuntimeShader
import android.os.Build


enum class GlassLevel {
    /** AGSL：折射 + 色散 + 液态流动，最接近 iOS 26 液态玻璃 */
    FULL,

    /** 系统模糊（RenderEffect）+ 叠色 + 描边 */
    BLUR,

    /** 纯半透明 + 描边，无模糊无折射 */
    SOLID,
}










object GlassCapability {

    @Volatile
    private var cachedLevel: GlassLevel? = null

    /** 探测并返回当前设备能用的最高档位。线程安全。 */
    fun level(context: Context): GlassLevel {
        cachedLevel?.let { return it }
        synchronized(this) {
            cachedLevel?.let { return it }
            val level = detect(context.applicationContext)
            cachedLevel = level
            return level
        }
    }

    fun resetForTest() {
        synchronized(this) { cachedLevel = null }
    }






























    private fun detect(context: Context): GlassLevel = when {
        !supportsRuntimeShader() -> blurOrSolid()


        //   所以 shaderCompiles() 的 @RequiresApi(33) 是满足的。
        !shaderCompiles() -> blurOrSolid()
        !isDevicePowerfulEnough(context) -> GlassLevel.BLUR
        else -> GlassLevel.FULL
    }

    private fun blurOrSolid(): GlassLevel =
        if (supportsRenderEffect()) GlassLevel.BLUR else GlassLevel.SOLID






















    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun supportsRuntimeShader(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun supportsRenderEffect(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S


































    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shaderCompiles(): Boolean = try {
        RuntimeShader(MINIMAL_SHADER)
        true
    } catch (_: Throwable) {
        false
    }

    private const val MINIMAL_SHADER = "half4 main(float2 p) { return half4(1.0); }"



















    private fun isDevicePowerfulEnough(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isLowRamDevice != true
    } catch (_: Throwable) {
        // 拿不到就当高性能 —— 宁可让高端机享受效果。
        // 反过来的话，一次系统 API 抖动会让所有高端机永久降级。
        true
    }

    /** 系统是否开了「移除动画」（无障碍 / 开发者选项） */
    fun isAnimationDisabled(context: Context): Boolean = try {
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    } catch (_: Throwable) {
        false
    }

    /** 是否支持 AGSL 的 `RuntimeShader` 完整 API（供设置页灰掉「液态玻璃」开关用） */
    fun canOfferFullGlass(context: Context): Boolean = level(context) == GlassLevel.FULL

    /** 探测包的 feature 标记（部分 ROM 会声明不支持 RenderScript 类似的 GPU 计算） */
    fun hasFeature(context: Context, name: String): Boolean = try {
        context.packageManager.hasSystemFeature(name)
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
