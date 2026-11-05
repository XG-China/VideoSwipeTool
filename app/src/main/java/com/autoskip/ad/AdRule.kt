package com.autoskip.ad

/** 当前页面相对判定规则的两种状态。 */
enum class PageState {
    /** 所有关键词都可见:不动作。 */
    NORMAL,

    /** 缺任意一个关键词:上滑。 */
    AD
}

/**
 * 纯判定逻辑,与 Android API 解耦,便于单元测试。
 *
 * 规则只有一条:配置里的所有关键词同时可见 → NORMAL;否则 → AD。
 * 默认关键词是「选集」和「第N集」,但这里不写死任何具体关键词。
 */
object AdRule {
    fun classify(scan: AdScan): PageState =
        if (scan.keywordVisible) PageState.NORMAL else PageState.AD
}
