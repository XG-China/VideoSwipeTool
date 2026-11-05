package com.autoskip.ad

/** 当前页面相对判定规则的三种状态。 */
enum class PageState {
    /** 关键词可见:正常剧集页(或选集面板),不该动手。 */
    NORMAL,

    /** 判定为广告覆盖:关键词在树里但不可见(或宽松模式下的"完全找不到")。 */
    AD,

    /** 严格模式下关键词完全不存在:不是播放页,不动手。 */
    UNKNOWN
}

/**
 * 纯判定逻辑,与 Android API 解耦,便于单元测试。
 *
 * 严格模式(默认):
 *   - 关键词可见                          → NORMAL
 *   - 关键词存在但不可见                  → AD(播放页被广告盖住)
 *   - 关键词完全不存在,但顶部有「第X集」  → AD(整棵树被广告替换,靠集数标题认播放页)
 *   - 以上都没有                          → UNKNOWN(首页/搜索页/榜单页,不滑)
 *
 * 宽松模式(旧行为):
 *   - 关键词可见                          → NORMAL
 *   - 其余                                → AD
 */
object AdRule {
    fun classify(scan: AdScan, strict: Boolean): PageState = when {
        scan.keywordVisible -> PageState.NORMAL
        scan.keywordPresent -> PageState.AD
        !strict -> PageState.AD
        scan.episodeHeader != null -> PageState.AD
        else -> PageState.UNKNOWN
    }
}
