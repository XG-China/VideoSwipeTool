package com.autoskip.ad

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置:总开关、目标 App、判定关键词、扫描节奏、严格模式与累计统计。
 * 决策逻辑全部集中在服务端,这里只负责读写配置,不含业务判断。
 */
object AppSettings {
    private const val PREF = "settings"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- 目标 App 包名 ----------
    data class AppInfo(val pkg: String, val name: String)

    /**
     * 支持的目标应用。默认一个都不开,由用户在目标应用列表里选择。
     */
    val KNOWN_APPS = listOf(
        AppInfo("com.phoenix.read", "红果免费短剧"),
        AppInfo("com.dragon.read", "番茄免费小说"),
        AppInfo("com.ss.android.ugc.aweme", "抖音"),
        AppInfo("com.ss.android.ugc.aweme.lite", "抖音极速版"),
        AppInfo("com.smile.gifmaker", "快手"),
        AppInfo("com.kuaishou.nebula", "快手极速版"),
        AppInfo("com.ss.android.article.video", "西瓜视频"),
        AppInfo("com.ss.android.article.news", "今日头条"),
        AppInfo("tv.danmaku.bili", "哔哩哔哩"),
        AppInfo("com.tencent.qqlive", "腾讯视频"),
        AppInfo("com.qiyi.video", "爱奇艺"),
        AppInfo("com.youku.phone", "优酷"),
        AppInfo("com.hunantv.imgo.activity", "芒果TV"),
        AppInfo("com.xingin.xhs", "小红书"),
        AppInfo("com.sina.weibo", "微博"),
    )

    private val DEFAULT_TARGETS = emptySet<String>()

    // 配置版本迁移:清掉历史保存的目标应用,默认一个都不开,由用户重新选择
    private const val PREFS_VERSION_KEY = "prefs_version"
    private const val PREFS_VERSION = 6

    fun migrateConfig(ctx: Context) {
        val v = sp(ctx).getInt(PREFS_VERSION_KEY, 0)
        if (v < PREFS_VERSION) {
            sp(ctx).edit()
                .remove("target_packages")
                .putInt(PREFS_VERSION_KEY, PREFS_VERSION)
                .apply()
        }
    }

    /** 当前启用的目标包集合;默认一个都不开,由用户在目标应用里选择。 */
    fun targetPackages(ctx: Context): Set<String> {
        val sp = sp(ctx)
        val known = KNOWN_APPS.map { it.pkg }.toSet()
        val saved = sp.getStringSet("target_packages", null) ?: return DEFAULT_TARGETS
        // 与 KNOWN_APPS 取交集:既丢掉历史遗留的已下线包,也让用户关掉某个目标后不会被这里重新加回来。
        val cleaned = saved.intersect(known)
        if (cleaned.size != saved.size) {
            sp.edit().putStringSet("target_packages", cleaned).apply()
        }
        return cleaned
    }

    fun setTargetPackage(ctx: Context, pkg: String, on: Boolean) {
        val cur = targetPackages(ctx).toMutableSet()
        if (on) cur.add(pkg) else cur.remove(pkg)
        sp(ctx).edit().putStringSet("target_packages", cur).apply()
    }

    // ---------- 总开关 ----------
    fun masterEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("master_enabled", true)
    fun setMasterEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("master_enabled", v).apply()

    // ---------- 页面判定关键词 ----------
    /**
     * 三组关键词,用于判断当前页面:
     *  - 剧集页:选集、第N集(N 是通配符)
     *  - 首页:关注、推荐、首页
     *  - 广告页:返回、上滑继续看短剧、上滑继续观看短剧、点击进入直播间
     *
     * 什么都没命中时按广告页处理(也就是"空关键词"规则)。
     * 广告页里「返回」是弱信号:只有没命中剧集/首页关键词时才生效;
     * 其他广告关键词优先于剧集页,避免广告盖住剧集页时漏滑。
     */
    val DEFAULT_EPISODE_KEYWORDS = listOf("选集", "第N集")
    val DEFAULT_HOME_KEYWORDS = listOf("关注", "推荐", "首页")
    val DEFAULT_AD_KEYWORDS = listOf(
        "返回",
        "上滑继续看短剧",
        "上滑继续观看短剧",
        "点击进入直播间"
    )

    /**
     * 把用户输入的原始文本解析成关键词列表。
     *
     * 支持中英文逗号、顿号、分号、换行和空格分隔;去重后为空则回退 fallback。
     */
    fun parseKeywords(raw: String?, fallback: List<String> = DEFAULT_EPISODE_KEYWORDS): List<String> {
        if (raw.isNullOrBlank()) return fallback
        return raw.split(',', '，', '、', ';', '；', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { fallback }
    }

    fun episodeKeywords(ctx: Context): List<String> {
        val saved = sp(ctx).getString("episode_keywords", null)
        if (saved != null) return normalizeEpisode(parseKeywords(saved))
        // 迁移旧版本的单一 keywords 设置
        val old = sp(ctx).getString("keywords", null)
        return normalizeEpisode(parseKeywords(old))
    }

    fun homeKeywords(ctx: Context): List<String> =
        parseKeywords(sp(ctx).getString("home_keywords", null), DEFAULT_HOME_KEYWORDS)

    fun adKeywords(ctx: Context): List<String> =
        parseKeywords(sp(ctx).getString("ad_keywords", null), DEFAULT_AD_KEYWORDS)

    /** 旧版本默认值是单独一个「选集」;升级时补成「选集 + 第N集」,其他自定义词原样保留。 */
    fun normalizeEpisode(parsed: List<String>): List<String> =
        if (parsed == listOf("选集")) DEFAULT_EPISODE_KEYWORDS else parsed

    fun setEpisodeKeywords(ctx: Context, raw: String) {
        sp(ctx).edit().putString("episode_keywords", parseKeywords(raw).joinToString(",")).apply()
    }

    fun setHomeKeywords(ctx: Context, raw: String) {
        sp(ctx).edit().putString("home_keywords", parseKeywords(raw, DEFAULT_HOME_KEYWORDS).joinToString(",")).apply()
    }

    fun setAdKeywords(ctx: Context, raw: String) {
        sp(ctx).edit().putString("ad_keywords", parseKeywords(raw, DEFAULT_AD_KEYWORDS).joinToString(",")).apply()
    }

    // ---------- 扫描节奏 ----------
    const val MIN_SCAN_MS = 300L
    const val MAX_SCAN_MS = 5000L
    const val DEFAULT_SCAN_MS = 1000L

    fun scanIntervalMs(ctx: Context): Long =
        sp(ctx).getLong("scan_interval_ms", DEFAULT_SCAN_MS).coerceIn(MIN_SCAN_MS, MAX_SCAN_MS)

    fun setScanIntervalMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong("scan_interval_ms", ms.coerceIn(MIN_SCAN_MS, MAX_SCAN_MS)).apply()
    }

    // ---------- 高级时序 ----------
    fun timing(ctx: Context, timing: Timing): Long =
        sp(ctx).getLong(timing.key, timing.default).coerceIn(timing.min, timing.max)

    fun setTiming(ctx: Context, timing: Timing, value: Long) {
        sp(ctx).edit().putLong(timing.key, value.coerceIn(timing.min, timing.max)).apply()
    }

    fun resetTimings(ctx: Context) {
        val edit = sp(ctx).edit()
        Timing.entries.forEach { edit.putLong(it.key, it.default) }
        edit.apply()
    }

    // ---------- 误滑换集自动回退 ----------
    /**
     * 开启后:自动上滑把集数从第 N 集跳到第 N+2 集或更远时,
     * 目标设为第 N+1 集,连续下滑回退(最多 3 次)。
     */
    fun autoCorrectEpisode(ctx: Context): Boolean =
        sp(ctx).getBoolean("auto_correct_episode", true)

    fun setAutoCorrectEpisode(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("auto_correct_episode", v).apply()

    // ---------- 累计统计 ----------
    fun totalSwipes(ctx: Context): Int = sp(ctx).getInt("stat_swipes", 0)
    fun totalSkips(ctx: Context): Int = sp(ctx).getInt("stat_skips", 0)

    fun recordSwipe(ctx: Context) {
        sp(ctx).edit().putInt("stat_swipes", totalSwipes(ctx) + 1).apply()
    }

    fun recordSkip(ctx: Context) {
        sp(ctx).edit().putInt("stat_skips", totalSkips(ctx) + 1).apply()
    }

    fun resetStats(ctx: Context) {
        sp(ctx).edit().putInt("stat_swipes", 0).putInt("stat_skips", 0).apply()
    }

    /** 统计文案,主界面和日志共用。只统计自动上滑/自动跳过。 */
    fun statsText(ctx: Context): String =
        "自动跳过 ${totalSkips(ctx)} 次 / 自动上滑 ${totalSwipes(ctx)} 次"
}
