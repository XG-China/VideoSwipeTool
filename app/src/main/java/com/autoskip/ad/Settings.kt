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
     * 只支持红果免费短剧。
     *
     * 注意别把 [com.dragon.read] 混进来:那是**番茄免费小说**(小说阅读,没有短剧广告),
     * 两家是字节同源的两个产品,连启动类都共用 com.dragon.read.pages.splash.SplashActivity,
     * 极易看混。红果免费短剧是 [com.phoenix.read]。
     */
    val KNOWN_APPS = listOf(
        AppInfo("com.phoenix.read", "红果免费短剧"),
    )

    // 配置版本迁移:旧版本保存过抖音/快手/番茄免费小说等目标包,现在只保留红果免费短剧
    private const val PREFS_VERSION_KEY = "prefs_version"
    private const val PREFS_VERSION = 5

    fun migrateConfig(ctx: Context) {
        val v = sp(ctx).getInt(PREFS_VERSION_KEY, 0)
        if (v < PREFS_VERSION) {
            sp(ctx).edit()
                .remove("target_packages")
                .putInt(PREFS_VERSION_KEY, PREFS_VERSION)
                .apply()
        }
    }

    /** 当前启用的目标包集合;没保存过则默认全开。 */
    fun targetPackages(ctx: Context): Set<String> {
        val sp = sp(ctx)
        val known = KNOWN_APPS.map { it.pkg }.toSet()
        val saved = sp.getStringSet("target_packages", null) ?: return known
        // 与 KNOWN_APPS 取交集:既丢掉历史遗留的已下线包(番茄免费小说等),
        // 也让用户关掉某个目标后不会被这里重新加回来。
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

    // ---------- 判定关键词 ----------
    val DEFAULT_KEYWORDS = listOf("选集")

    /**
     * 把用户输入的原始文本解析成关键词列表。
     *
     * 支持中英文逗号、顿号、分号、换行和空格分隔;去重后为空则回退默认值。
     * 抽成纯函数,便于单元测试。
     */
    fun parseKeywords(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return DEFAULT_KEYWORDS
        return raw.split(',', '，', '、', ';', '；', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { DEFAULT_KEYWORDS }
    }

    fun keywords(ctx: Context): List<String> =
        parseKeywords(sp(ctx).getString("keywords", null))

    fun setKeywords(ctx: Context, raw: String) {
        sp(ctx).edit().putString("keywords", parseKeywords(raw).joinToString(",")).apply()
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

    // ---------- 严格判定 ----------
    /**
     * 严格模式(默认开):只有「关键词在 UI 树里存在但不可见」才判定为广告。
     * 首页/搜索页/榜单页根本没有关键词节点,因此不会被误滑;
     * 关闭后回到旧规则:找不到可见关键词就上滑。
     */
    fun strictMode(ctx: Context): Boolean = sp(ctx).getBoolean("strict_mode", true)
    fun setStrictMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("strict_mode", v).apply()

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

    /** 统计文案,主界面和日志共用。 */
    fun statsText(ctx: Context): String =
        "累计跳过 ${totalSkips(ctx)} 次 / 上滑 ${totalSwipes(ctx)} 次"
}
