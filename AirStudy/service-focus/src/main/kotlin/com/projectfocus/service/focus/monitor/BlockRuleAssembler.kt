package com.projectfocus.service.focus.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class BlockRuleAssembler @Inject constructor(
    @ApplicationContext private val context: Context,
) {












    private val labelCache = LruCache<String, String>(128)












    fun assemble(rows: List<BlockedRow>): List<BlockRule> {


        //   合并后 1 次。表越大差距越明显（用户可能勾了 50 个）。
        val packageRules = mutableListOf<BlockRule>()
        val byCategory = mutableMapOf<String, MutableSet<String>>()
        val categoryLabels = mutableMapOf<String, String>()

        rows.forEach { row ->
            when (row.type) {
                "APP" -> packageRules += BlockRule(
                    type = RuleType.PACKAGE,
                    pattern = row.packageName,
                    categoryLabel = row.categoryLabel,
                )

                "CATEGORY" -> {
                    val packages = BuiltInAppCategories.expand(row.packageName)
                    if (packages.isEmpty()) {

                        //   用户可能是在新版本 App 上建了分类、又降级回来，

                        //   记录日志让线上可观测，运营侧才能发现问题。
                        Timber.w("未知的应用分类：%s，该规则暂不生效", row.packageName)
                    } else {
                        byCategory.getOrPut(row.packageName) { mutableSetOf() }
                            .addAll(packages)
                        row.categoryLabel?.let { categoryLabels[row.packageName] = it }
                    }
                }

                "WEBSITE" -> packageRules += BlockRule(
                    type = RuleType.DOMAIN,
                    pattern = row.packageName,
                    categoryLabel = row.categoryLabel,
                )

                else -> Timber.w("未知的屏蔽类型：%s（包名 %s）", row.type, row.packageName)
            }
        }

        val categoryRules = byCategory.map { (pattern, packages) ->
            BlockRule(
                type = RuleType.CATEGORY,
                pattern = pattern,
                categoryLabel = categoryLabels[pattern],
                expandedPackages = packages,
            )
        }

        return packageRules + categoryRules
    }














    fun labelOf(packageName: String): String {
        labelCache.get(packageName)?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName }
        labelCache.put(packageName, label)
        return label
    }

    /** 清空标签缓存（用户改语言 / 应用更新后调用） */
    fun invalidateLabels() = labelCache.evictAll()


    suspend fun installedLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }.getOrElse {
            Timber.w(it, "查询启动器应用失败")
            return@withContext emptyList()
        }

        resolved.asSequence()
            .mapNotNull { info -> info.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            // 排除自己
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = runCatching {
                        @Suppress("DEPRECATION")
                        pm.getApplicationLabel(appInfo).toString()
                    }.getOrElse { appInfo.packageName },
                    isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** 诊断用：当前进程是否为前台（帮助排查「为什么没拦」） */
    fun isSelfForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        @Suppress("DEPRECATION")
        val processes = am.runningAppProcesses ?: return false
        return processes.any {
            it.processName == context.packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}

/** 数据库行的纯数据投影。见 [BlockRuleAssembler.assemble] 的参数说明。 */
data class BlockedRow(
    /** APP / CATEGORY / WEBSITE */
    val type: String,
    /** APP 时是包名；CATEGORY 时是分类名；WEBSITE 时是域名 */
    val packageName: String,
    /** 来源分类的显示名 */
    val categoryLabel: String?,
)

/** 「选择要屏蔽的应用」列表项 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)


























object BuiltInAppCategories {

    private val SOCIAL: Set<String> = setOf(
        "com.tencent.mm",                    // 微信
        "com.tencent.mobileqq",              // QQ
        "com.sina.weibo",                    // 微博
        "com.xingin.xhs",                    // 小红书
        "com.instagram.android",
        "com.facebook.katana",
        "com.twitter.android",
        "org.telegram.messenger",
        "com.whatsapp",
        "com.snapchat.android",
        "com.zhihu.android",                 // 知乎
        "com.douban.frodo",                  // 豆瓣
    )

    private val VIDEO: Set<String> = setOf(
        "com.ss.android.ugc.aweme",          // 抖音
        "com.ss.android.ugc.aweme.lite",     // 抖音极速版
        "com.smile.gifmaker",                // 快手
        "com.kuaishou.nebula",               // 快手极速版
        "tv.danmaku.bili",                   // B 站
        "com.tencent.qqlive",                // 腾讯视频
        "com.qiyi.video",                    // 爱奇艺
        "com.youku.phone",                   // 优酷
        "com.hunantv.imgo.activity",         // 芒果 TV
        "com.google.android.youtube",
        "com.netflix.mediaclient",
    )

    private val GAME: Set<String> = setOf(
        "com.tencent.tmgp.sgame",            // 王者荣耀
        "com.tencent.tmgp.pubgmhd",          // 和平精英
        "com.miHoYo.Yuanshen",               // 原神
        "com.miHoYo.hkrpg",                  // 崩坏：星穹铁道
        "com.netease.onmyoji",               // 阴阳师
        "com.roblox.client",
        "com.supercell.clashofclans",
    )

    private val SHOPPING: Set<String> = setOf(
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "com.achievo.vipshop",
        "com.amazon.mShop.android.shopping",
    )

    private val NEWS: Set<String> = setOf(
        "com.ss.android.article.news",       // 今日头条
        "com.netease.newsreader.activity",   // 网易新闻
        "com.tencent.news",                  // 腾讯新闻
        "com.google.android.apps.magazines",
    )

    private val MAPPING: Map<String, Set<String>> = mapOf(
        "social" to SOCIAL,
        "video" to VIDEO,
        "game" to GAME,
        "shopping" to SHOPPING,
        "news" to NEWS,
    )

    /** 展开一个分类名。未知分类返回空集合。 */
    fun expand(category: String): Set<String> =
        MAPPING[category.lowercase()] ?: emptySet()

    /** 全部分类名。供 UI 的「按分类屏蔽」页使用。 */
    fun knownCategories(): Set<String> = MAPPING.keys









    fun categoriesOf(packageName: String): Set<String> =
        MAPPING.filterValues { packageName in it }.keys
}
