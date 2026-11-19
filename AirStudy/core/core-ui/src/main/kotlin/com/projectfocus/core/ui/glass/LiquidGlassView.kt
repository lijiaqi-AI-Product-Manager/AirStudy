package com.projectfocus.core.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import com.projectfocus.core.ui.R
import com.projectfocus.core.ui.token.ThemeTokens


class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tokens = ThemeTokens(context)

    /** 当前档位。init 里探测一次，[glassLevel] 变化时重配。 */
    private var level: GlassLevel = GlassCapability.level(context)

    private var params: GlassParams = GlassParams(

        //   loadTintAlpha 是 Context 的扩展函数（定义在 companion 里），
        //   裸调用时的隐式接收者是 LiquidGlassView 而不是 Context，会编译失败。
        //   （this@LiquidGlassView 也不行：它不是 Context。）
        tintAlpha = context.loadTintAlpha(),
        tintColor = context.loadTintColor(),
    )

    /** 玻璃圆角（px）。默认 radius_l，XML 里可用 app:glassCornerRadius 覆盖。 */
    private var cornerRadiusPx: Float = tokens.radiusL.toFloat()

    // ---- 绘制资源 ----
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = tokens.strokeHairline.toFloat()
        color = tokens.surfaceGlassStroke
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val rectF = RectF()
    private val shaderMatrix = Matrix()

    // ---- FULL 档专用 ----
    //

    //
    //   原因：本工程的 minSdk 是 26，而 `RuntimeShader` 是 API 33 才有的类。
    //   lint 的 [NewApi] 不只是查方法调用，也查**字段类型与局部变量的类型**：
    //   在 API 26 的设备上加载 `LiquidGlassView` 这个类时，ART 需要解析
    //   所有字段的类型 —— 包括 `RuntimeShader`。如果它在 API 26 上不存在，
    //   类加载会抛 `NoClassDefFoundError`。
    //

    //     与**调用**一个高版本方法，是完全同级的危险。
    //     前者还更隐蔽 —— 因为代码里根本看不出哪一行"用了新 API"。
    //
    //   → 用 `Any?` 持有引用（ART 上任何引用都是 4 字节，零成本），
    //     只在 [applyUniformsFull] 这个 `@RequiresApi(33)` 函数内部
    //     做一次 `as RuntimeShader` 的收窄。这样：
    //       ① API 26 上加载本类不会碰 RuntimeShader 这个名字
    //       ② 所有真正用到它的地方集中在一个函数里，一眼看得完
    private var runtimeShaderRef: Any? = null
    private var backdrop: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private var backdropRect = Rect()

    // ---- BLUR 档专用 ----

    private var blurEffectRef: Any? = null

    @Volatile
    private var elapsedSeconds: Float = 0f

    /** 时间源。由外部的 [GlassTicker] 设置；为空则完全静止（0 功耗）。 */
    var ticker: GlassTicker? = null
        set(value) {
            if (field === value) return
            field?.removeListener(onTick)
            field = value
            value?.addListener(onTick)
            if (value != null) invalidate() else elapsedSeconds = 0f
        }

    private val onTick: (Float) -> Unit = { t ->
        if (isAttachedToWindow && isVisible && params.liquid > 0.01f) {
            elapsedSeconds = t
            invalidate()
        }
    }

    init {

        //   如果裁剪会把描边切掉一截，边缘出现一圈硬边。
        clipChildren = false
        clipToPadding = false
        // 玻璃是装饰，默认不消费触摸（让子 View 拿到）
        isClickable = false
        isFocusable = false


        //   否则「按钮 + 它的玻璃底板」会被读成两个节点，用户听到两次「起飞」。

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO


        //
        //   `use` 是 `AutoCloseable.use`，而 `TypedArray` 直到 **API 31**
        //   才实现 `AutoCloseable`。在高版本编译通过、lint 报错：
        //     Error: Implicit cast from TypedArray to AutoCloseable
        //            requires API level 31 (current min is 26) [NewApi]
        //   在 API 26~30 的设备上运行时，这一行会在**类加载/类型检查阶段**
        //   抛 `IncompatibleClassChangeError` 或 `NoSuchMethodError` ——
        //   表现为"玻璃控件一 inflate 就闪退"，而堆栈指向很含糊的位置。
        //


        //     `use`、`runCatching`、`Closeable` 这些都不是"Kotlin 语言特性"，
        //     它们是 JDK/Android 接口的包装。
        //

        //      用 `use` 时若 `getDimension` 抛异常，recycle 走 AutoCloseable.close()
        //      —— 而那个路径在低版本根本不存在。
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassView)
        try {
            cornerRadiusPx = typedArray.getDimension(
                R.styleable.LiquidGlassView_glassCornerRadius,
                tokens.radiusL.toFloat(),
            )
        } finally {
            typedArray.recycle()
        }

        applyLevel()
    }

    // ==================================================================
    // 公开 API
    // ==================================================================






    fun setGlassParams(newParams: GlassParams) {
        val safe = newParams.sanitized()
        if (safe == params) return
        params = safe
        applyLevel()
        invalidate()
    }

    fun getGlassParams(): GlassParams = params

    fun setGlassCornerRadius(radiusPx: Float) {
        if (radiusPx == cornerRadiusPx) return
        cornerRadiusPx = radiusPx.coerceAtLeast(0f)
        invalidate()
    }

    /** 当前实际生效的档位（可能比设备能力低，比如「减少动效」时） */
    fun getGlassLevel(): GlassLevel = level

    // ==================================================================
    // 档位应用
    // ==================================================================

    private fun applyLevel() {

        //

        //   隐藏前提是"level == FULL 就一定 >= API 33"。这个前提**今天成立，
        //   但没有任何东西保证它永远成立**：
        //     · `setGlassLevelOverrideForTest()` 可以在 API 26 上强制 FULL
        //     · 将来若把 level 持久化到 SharedPreferences，读到旧值就可能越档
        //   → 在这里再夹一次。代价是两次 int 比较，收益是"永远不会因为
        //     一次错误的赋值就整块玻璃崩掉"。
        //

        //     在消费它的 when 之前都要再验一次前提。**
        //     `@RequiresApi` 注解只是给 lint 看的，它在运行时不存在。
        val effective = when {
            level == GlassLevel.FULL && !GlassCapability.supportsRuntimeShader() -> GlassLevel.BLUR
            level == GlassLevel.BLUR && !GlassCapability.supportsRenderEffect() -> GlassLevel.SOLID
            level == GlassLevel.FULL && !GlassCapability.supportsRenderEffect() -> GlassLevel.FULL
            else -> level
        }
        when (effective) {
            GlassLevel.FULL -> {
                ensureShader()
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                //   「AGSL 折射 + 系统模糊」同时作用，画面糊成一团。
                //   这一行在 API < 31 上是空操作（本来就没设过），
                //   所以只在支持时调用，不写 if 也无害 —— 但写了更清楚。
                clearRenderEffect()
            }

            GlassLevel.BLUR -> {
                releaseShader()
                setLayerType(View.LAYER_TYPE_NONE, null)

                //

                //   名字里带 "IfSupported" 就以为它自己会判版本，
                //   但函数名不是契约，`@RequiresApi(31)` 才是。
                //   → lint 立刻抓到：`Call requires API level 31 (current min is 26)`。
                //

                //     如果走到 BLUR 档（`isDevicePowerfulEnough` 为 false
                //     的低端机，且 supportsRenderEffect 为 false 时本该降到 SOLID，
                //     但**力档 override 可以绕过**），就会调用一个 31+ 才有的方法。
                //

                //     叫 `xxxIfSupported` / `xxxSafe` 的函数，调用方仍需自己判版本 ——
                //     除非它内部真的判了（这个函数没有，因为它被设计成"已被判定后的执行体"）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    applyBlurRenderEffectIfSupported()
                }
            }

            GlassLevel.SOLID -> {
                releaseShader()
                setLayerType(View.LAYER_TYPE_NONE, null)
                clearRenderEffect()
            }
        }
    }










    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlurRenderEffectIfSupported() = applyBlurRenderEffect()

    /** API < 31 时 `setRenderEffect` 不存在，只能跳过。 */
    private fun clearRenderEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) clearRenderEffectV31()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun clearRenderEffectV31() {
        setRenderEffect(null)
        blurEffectRef = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlurRenderEffect() {
        if (!GlassCapability.supportsRenderEffect()) return
        val radiusPx = tokens.dp(params.blurRadiusDp)

        val effectV31: android.graphics.RenderEffect? = if (radiusPx > 0.5f) {
            android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        } else {
            null
        }
        blurEffectRef = effectV31
        setRenderEffect(effectV31)
    }














    private fun ensureShader() {
        if (runtimeShaderRef != null) return
        if (!GlassCapability.supportsRuntimeShader()) {

            //   但"理论上到不了"的代码就是最该写的代码。
            level = GlassLevel.BLUR
            return
        }
        runtimeShaderRef = try {
            newRuntimeShader()
        } catch (_: Throwable) {

            level = GlassLevel.BLUR
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun newRuntimeShader(): Any = RuntimeShader(readShaderSource())

    private fun releaseShader() {
        runtimeShaderRef = null
        backdropShader = null
        backdrop?.let { if (!it.isRecycled) it.recycle() }
        backdrop = null
    }

    private fun readShaderSource(): String =
        context.resources.openRawResource(R.raw.liquidglass_effect).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    // ==================================================================
    // 触摸 / 无障碍
    // ==================================================================
















    // ==================================================================
    // 测量 / 尺寸
    // ==================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())

        if (level == GlassLevel.FULL) {
            // 背景位图的尺寸必须与 View 一致（1:1），否则 shader 里
            // uContent.eval(pixelCoord) 的坐标对不上，会采到错位的内容
            if (backdrop?.width != w || backdrop?.height != h) {
                backdrop?.recycle()
                backdrop = createBitmap(w, h)
                backdropShader = BitmapShader(backdrop!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    override fun onDraw(canvas: Canvas) {

        //   玻璃的背景是「折射后的采样结果」，是动态的。
        //

        //    `when (level)` 假定「level 与当前 SDK 匹配」，而 level 是**可变字段**。
        //    一旦有人在 API 26 上把它设成 FULL，`drawFull` 里第一次碰
        //    `RuntimeShader` 就是 `NoClassDefFoundError` —— 而且是**在 onDraw 里**，
        //    也就是崩在绘制阶段，堆栈里全是 View 框架的帧，极难定位。
        when {
            level == GlassLevel.FULL && GlassCapability.supportsRuntimeShader() -> drawFull(canvas)
            level == GlassLevel.FULL || level == GlassLevel.BLUR -> drawBlurLevel(canvas)
            else -> drawSolid(canvas)
        }
        // 描边三档共用
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }

    private fun drawSolid(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.color = tokens.withAlpha(params.tintColor, params.tintAlpha)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, fillPaint)
    }

    private fun drawBlurLevel(canvas: Canvas) {
        // BLUR 档：View 自身已经被 RenderEffect 模糊（背景透过来的内容）
        //         这里只叠一层 tint，让玻璃「有颜色」
        fillPaint.shader = null
        fillPaint.color = tokens.withAlpha(params.tintColor, params.tintAlpha + 0.06f)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, fillPaint)
    }










    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawFull(canvas: Canvas) {

        //   形成无限递归（栈溢出）或至少是「玻璃里映着玻璃」的鬼影。
        if (suppressSelfDraw) return

        //   出现 `as RuntimeShader`，所以"哪里用了高版本类型"是**可 grep 的**。
        val shader = runtimeShaderRef as? RuntimeShader ?: run { drawSolid(canvas); return }
        val bmp = backdrop ?: run { drawSolid(canvas); return }
        val bmpShader = backdropShader ?: run { drawSolid(canvas); return }

        captureBackdrop(bmp)

        // ---- 绑定 uniform（顺序与 agsl 文件头契约一致）----
        try {
            shader.setInputShader(UNIFORM_CONTENT, bmpShader)
            shader.setFloatUniform(UNIFORM_SIZE, width.toFloat(), height.toFloat())
            shader.setFloatUniform(UNIFORM_RADIUS, cornerRadiusPx)
            shader.setFloatUniform(UNIFORM_BLUR_RADIUS, tokens.dp(params.blurRadiusDp))
            shader.setFloatUniform(UNIFORM_REFRACT_H, tokens.dp(params.refractionHeightDp))
            shader.setFloatUniform(UNIFORM_REFRACT_OFF, tokens.dp(params.refractionOffsetDp))
            shader.setFloatUniform(UNIFORM_DISPERSION, params.dispersion)
            shader.setFloatUniform(
                UNIFORM_LIQUID,
                // 系统开了「移除动效」时强制静止
                if (GlassCapability.isAnimationDisabled(context)) 0f else params.liquid,
            )
            shader.setFloatUniform(UNIFORM_TIME, elapsedSeconds)
            shader.setFloatUniform(
                UNIFORM_TINT,
                Color.red(params.tintColor) / 255f,
                Color.green(params.tintColor) / 255f,
                Color.blue(params.tintColor) / 255f,
                params.tintAlpha,
            )
            shader.setFloatUniform(UNIFORM_LIGHT_DIR, -0.7071f, -0.7071f)
            shader.setFloatUniform(UNIFORM_OPACITY, 1f)
        } catch (_: Throwable) {
            // 某个 uniform 名字对不上（改了 agsl 忘了改常量）—— 降级而不是崩
            drawSolid(canvas)
            return
        }

        fillPaint.shader = shader
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, fillPaint)
        fillPaint.shader = null
    }

























    private fun captureBackdrop(target: Bitmap) {
        val now = System.currentTimeMillis()
        val cacheValid = now - lastBackdropAt < BACKDROP_REFRESH_INTERVAL_MS &&
            lastBackdropSize == width to height
        if (cacheValid) return

        val root = findDrawRoot() ?: return
        val c = Canvas(target)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)


        val myLoc = IntArray(2)
        val rootLoc = IntArray(2)
        getLocationInWindow(myLoc)
        root.getLocationInWindow(rootLoc)
        val offsetX = rootLoc[0] - myLoc[0]
        val offsetY = rootLoc[1] - myLoc[1]
        c.translate(offsetX.toFloat(), offsetY.toFloat())

        // ② 临时屏蔽自己的绘制
        suppressSelfDraw = true
        try {
            root.draw(c)
        } catch (_: Throwable) {
            // draw 期间 View 树被改（比如 RecyclerView 在滚动）会抛
            // IllegalStateException。这不是致命错误，跳过这次采集，
            // 用上一帧的背景（或留空），下一帧会重试。
        } finally {
            suppressSelfDraw = false
        }

        lastBackdropAt = now
        lastBackdropSize = width to height
    }

    @Volatile
    private var suppressSelfDraw = false

    private var lastBackdropAt = 0L
    private var lastBackdropSize: Pair<Int, Int>? = null









    private fun findDrawRoot(): View? {
        var v: View? = this
        while (v != null) {
            val parent = v.parent
            if (parent is ViewGroup && parent.parent == null) return parent
            if (parent == null) return v
            v = parent as? View
        }
        return null
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker?.addListener(onTick)
        lastBackdropAt = 0L // 重新挂载时强制重采（可能换了窗口）
    }

    override fun onDetachedFromWindow() {
        ticker?.removeListener(onTick)
        super.onDetachedFromWindow()

        //   再挂回来时还要用。真正的释放放在 onWindowVisibilityChanged(GONE)。
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            // 窗口不可见时释放位图 —— 一个 1080p 的 ARGB_8888 是 8MB，
            // 页面上 6 个玻璃就是 48MB。这是「后台被 OOM」的真实来源。
            releaseBackdrop()
        } else {
            lastBackdropAt = 0L
            invalidate()
        }
    }

    private fun releaseBackdrop() {
        backdropShader = null
        backdrop?.let { if (!it.isRecycled) it.recycle() }
        backdrop = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 玻璃至少要有一个能画出弧形的尺寸。小于 2× 圆角时把圆角压回去，
        // 否则 SDF 会算出负的 halfSize，shader 里直接输出透明（整块看不见）。
        val minSide = minOf(measuredWidth, measuredHeight) / 2f
        if (cornerRadiusPx > minSide) cornerRadiusPx = minSide
    }

    companion object {
        // ---- AGSL uniform 名（必须与 liquidglass_effect.agsl 完全一致）----
        private const val UNIFORM_CONTENT = "uContent"
        private const val UNIFORM_SIZE = "uSize"
        private const val UNIFORM_RADIUS = "uRadius"
        private const val UNIFORM_BLUR_RADIUS = "uBlurRadius"
        private const val UNIFORM_REFRACT_H = "uRefractH"
        private const val UNIFORM_REFRACT_OFF = "uRefractOff"
        private const val UNIFORM_DISPERSION = "uDispersion"
        private const val UNIFORM_LIQUID = "uLiquid"
        private const val UNIFORM_TIME = "uTime"
        private const val UNIFORM_TINT = "uTint"
        private const val UNIFORM_LIGHT_DIR = "uLightDir"
        private const val UNIFORM_OPACITY = "uOpacity"










        private const val BACKDROP_REFRESH_INTERVAL_MS = 100L

        /** 同屏允许「流动」的玻璃数量上限，超出的自动静止（见类注释的性能说明） */
        const val MAX_LIQUID_VIEWS = 6








        private fun Context.loadTintAlpha(): Float = try {
            val typed = android.util.TypedValue()
            resources.getValue(R.dimen.glass_tint_alpha, typed, true)

            //   typed.float 会是 0.0（它只对 TYPE_FLOAT 有意义），
            //   而 getDimension 对 float 型 dimen 会抛异常。
            //   两边都校验，才能保证「写错格式」时退到默认值而不是静默变 0。
            if (typed.type == android.util.TypedValue.TYPE_FLOAT) typed.float
            else resources.getDimension(R.dimen.glass_tint_alpha) / resources.displayMetrics.density
        } catch (_: Throwable) {
            GlassParams.DEFAULT_TINT_ALPHA
        }

        private fun Context.loadTintColor(): Int = try {
            ContextCompat.getColor(this, R.color.surface_glass)
        } catch (_: Throwable) {
            GlassParams.DEFAULT_TINT_COLOR
        }
    }
}
