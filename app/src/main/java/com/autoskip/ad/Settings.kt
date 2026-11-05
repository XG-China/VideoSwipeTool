package com.autoskip.ad

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置:总开关 + 目标 App 包名。
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
    private const val PREFS_VERSION = 4

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
}