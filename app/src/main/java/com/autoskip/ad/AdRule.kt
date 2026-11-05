package com.autoskip.ad

/** 当前页面相对判定规则的两种状态。 */
enum class PageState {
    /** 正常播放页:不动作。 */
    NORMAL,

    /** 关键词缺失/被盖住:上滑。 */
    AD
}

/**
 * 纯判定逻辑,与 Android API 解耦,便于单元测试。
 *
 * 严格模式(默认):
 *   屏幕上同时有「选集」和「第N集」→ NORMAL
 *   缺任意一个                    → AD(上滑)
 *
 * 宽松模式:
 *   只要任意一个关键词可见 → NORMAL
 *   一个都看不到           → AD
 */
object AdRule {
    fun classify(scan: AdScan, strict: Boolean): PageState = when {
        strict -> if (scan.keywordVisible) PageState.NORMAL else PageState.AD
        else -> if (scan.anyKeywordVisible) PageState.NORMAL else PageState.AD
    }
}
