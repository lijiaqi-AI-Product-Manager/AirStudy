package com.projectfocus.service.focus.monitor


object FocusMonitorConfig {


    const val POLL_INTERVAL_MS: Long = 500L


    const val BLOCK_THRESHOLD_MS: Long = 2_000L


    const val NOTIFICATION_ID: Int = 2001


    const val CHANNEL_ID: String = "app_monitoring_channel"


    val DEFAULT_WHITELIST: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.incallui",
        "com.android.dialer",
        "com.android.server.telecom",
    )















    val DEFAULT_WHITELIST_PREFIXES: List<String> = listOf(
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.android.settings",
    )

    /**
     * 状态表容量上限。
     *
     * 256 的依据：正常用户一节课切不到 20 个应用；256 已经能覆盖
     * 「输入法每次按键都报一次前台」这种异常输入的短期爆发。
     * 超了按插入顺序淘汰最旧的（LinkedHashMap 天然支持）。
     */
    const val MAX_TRACKED_PACKAGES: Int = 256
}









data class UsageEvent(
    /** 进入前台的包名 */
    val packageName: String,






    val timestampMs: Long,
)

/** 屏蔽规则类型。与 `BlockedAppType` 一一对应，但这里是纯逻辑层，不依赖 DB 实体。 */
enum class RuleType {
    /** 精确匹配包名 */
    PACKAGE,

    /** 按应用分类（如 "social"），由外壳层预先把分类展开成包名集合 */
    CATEGORY,

    /** 按域名（WebView / 浏览器场景，M3 接入时启用） */
    DOMAIN,
}











data class BlockRule(
    val type: RuleType,
    /** PACKAGE 时是包名；CATEGORY 时是展开后的一组包名；DOMAIN 时是域名 */
    val pattern: String,

    val categoryLabel: String? = null,
    /** 展开后的包名集合（仅 CATEGORY 使用） */
    val expandedPackages: Set<String> = emptySet(),
)

/**
 * 前台状态跟踪器。
 *
 * 语义：记录「每个包名从什么时候开始连续在前台」。
 * 关键操作是 [onForeground] 与 [invalidate]，两者的区别见方法注释。
 */
class ForegroundTracker(
    private val maxTracked: Int = FocusMonitorConfig.MAX_TRACKED_PACKAGES,
) {


    private val since = LinkedHashMap<String, Long>()

    /** 当前记录的包名数量（供诊断用） */
    val trackedCount: Int get() = since.size

    /**
     * 记一次「pkg 进入前台」。
     *
     * @return 该 pkg 连续在前台的起点时间戳（新记录则为 [timestampMs]）
     */
    fun onForeground(packageName: String, timestampMs: Long): Long {
        val existing = since[packageName]

        if (existing != null && timestampMs >= existing) return existing

        since.remove(packageName) // 保证重新插入到链表尾部（更新「最近活跃」顺序）
        since[packageName] = timestampMs
        evictIfNeeded()
        return timestampMs
    }

    /** 该 pkg 连续在前台的起点；未记录返回 null */
    fun startOf(packageName: String): Long? = since[packageName]

    /** 连续在前台的时长（毫秒）。未记录返回 0，回拨情况返回 0 而非负数。 */
    fun durationOf(packageName: String, nowMs: Long): Long {
        val start = since[packageName] ?: return 0L
        return (nowMs - start).coerceAtLeast(0L)
    }


    fun invalidate(packageName: String) {
        since.remove(packageName)
    }


    fun resetStart(packageName: String, nowMs: Long) {
        since.remove(packageName)
        since[packageName] = nowMs
        evictIfNeeded()
    }

    fun clear() = since.clear()









    private fun evictIfNeeded() {
        while (since.size > maxTracked) {
            val oldest = since.keys.firstOrNull() ?: break
            since.remove(oldest)
        }
    }
}

/**
 * 判定结果。
 */
sealed interface EnforcementDecision {
    /** 不需要做任何事 */
    data object None : EnforcementDecision

    /** 应该拦截，拉起遮挡页 */
    data class Block(
        val packageName: String,
        /** 人类可读的应用名（外壳层从 PackageManager 拿，判定层不碰） */
        val appLabel: String?,
        /** 命中的分类名，用于遮挡页副标题 */
        val categoryLabel: String?,
        /** 已经连续在前台多久（毫秒），用于埋点 */
        val foregroundDurationMs: Long,
    ) : EnforcementDecision
}

/**
 * 判定器：吃事件、吐决策。**无 Android 依赖，可完整单测。**
 *
 * 用法（外壳层，每 500ms 一次）：
 * ```
 * val decision = enforcer.evaluate(eventsSinceLastPoll, elapsedRealtime())
 * when (decision) {
 *     is Block -> startBlockedActivity(decision)
 *     None -> Unit
 * }
 * ```
 */
class FocusEnforcer(
    private val tracker: ForegroundTracker = ForegroundTracker(),
    private var whitelist: Set<String> = FocusMonitorConfig.DEFAULT_WHITELIST,
    private var whitelistPrefixes: List<String> = FocusMonitorConfig.DEFAULT_WHITELIST_PREFIXES,
    private val blockThresholdMs: Long = FocusMonitorConfig.BLOCK_THRESHOLD_MS,
) {
    private var rules: List<BlockRule> = emptyList()
    private var blockedPackages: Set<String> = emptySet()

    /** 本 App 自己的包名 —— 永远是前台就是「用户在专注页」，不该拦 */
    private var selfPackage: String? = null


    private var currentForeground: String? = null

    /** 是否启用。关闭时 [evaluate] 恒返回 None，且会清空跟踪状态。 */
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                tracker.clear()
                currentForeground = null
            }
        }

    /** 会话是否暂停（用户在专注页点了暂停） */
    var paused: Boolean = false
        set(value) {
            field = value
            if (value) {
                tracker.clear()
                currentForeground = null
            }
        }

    fun configure(
        selfPackage: String,
        rules: List<BlockRule>,
        whitelist: Set<String> = FocusMonitorConfig.DEFAULT_WHITELIST,
        whitelistPrefixes: List<String> = FocusMonitorConfig.DEFAULT_WHITELIST_PREFIXES,
    ) {
        this.selfPackage = selfPackage
        this.rules = rules
        this.whitelist = whitelist
        this.whitelistPrefixes = whitelistPrefixes
        this.blockedPackages = rules.flatMapTo(mutableSetOf()) { rule ->
            when (rule.type) {
                RuleType.PACKAGE -> listOf(rule.pattern)
                RuleType.CATEGORY -> rule.expandedPackages

                //   它属于 WebView 内的 URL 拦截，M3 接入时由另一条路径处理。
                RuleType.DOMAIN -> emptyList()
            }
        }

        //   继续按旧结论拦就是误杀。全部清空，等下一次事件重建。
        tracker.clear()
        currentForeground = null
    }

    /** 诊断用：当前生效的屏蔽包名集合 */
    fun blockedPackageCount(): Int = blockedPackages.size


    fun evaluate(events: List<UsageEvent>, nowMs: Long): EnforcementDecision {
        if (!enabled || paused) {
            tracker.clear()
            currentForeground = null
            return EnforcementDecision.None
        }

        for (event in events) {
            val pkg = event.packageName
            when {

                pkg == selfPackage -> {
                    tracker.invalidate(pkg)
                    currentForeground = null
                }

                // ② 白名单 → 不参与判定，也不清其它包的时间


                //      不该因为之前待在抖音就把他顶回去。
                isWhitelisted(pkg) -> {
                    currentForeground = null
                }

                // ③ 不在屏蔽名单 → 记录但不拦。

                //      之间快速切换，如果切到微信时把抖音的记录删了，
                //      第二次回抖音就要重新等 2 秒。



                pkg !in blockedPackages -> {
                    tracker.onForeground(pkg, event.timestampMs)
                    currentForeground = pkg
                }

                else -> {
                    tracker.onForeground(pkg, event.timestampMs)
                    currentForeground = pkg
                }
            }
        }


        //    用户静置不动时事件列表是空的，但 currentForeground 依然是抖音 ——
        //    这正是「静静刷 2 秒就被拦」能生效的原因。
        val target = currentForeground?.takeIf { it in blockedPackages }
            ?: return EnforcementDecision.None

        val duration = tracker.durationOf(target, nowMs)
        if (duration <= blockThresholdMs) return EnforcementDecision.None

        // 命中：把起点推到 now，形成「每 2 秒弹一次」的持续压制
        tracker.resetStart(target, nowMs)

        val rule = rules.firstOrNull { r ->
            when (r.type) {
                RuleType.PACKAGE -> r.pattern == target
                RuleType.CATEGORY -> target in r.expandedPackages
                RuleType.DOMAIN -> false
            }
        }
        return EnforcementDecision.Block(
            packageName = target,
            appLabel = null,
            categoryLabel = rule?.categoryLabel,
            foregroundDurationMs = duration,
        )
    }

    private fun isWhitelisted(pkg: String): Boolean =
        pkg in whitelist || whitelistPrefixes.any { pkg.startsWith(it) }
}
