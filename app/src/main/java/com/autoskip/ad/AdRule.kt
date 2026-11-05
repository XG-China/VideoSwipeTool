package com.autoskip.ad

/**
 * 纯页面判定逻辑,与 Android API 解耦,便于单元测试。
 *
 * 优先级:
 *  1. 命中「返回」以外的广告关键词 → 广告页(上滑)
 *  2. 命中剧集页关键词(选集/第N集)→ 剧集页(不滑)
 *  3. 命中首页关键词(关注/推荐/首页)→ 首页(不滑)
 *  4. 什么都没命中,或只命中「返回」→ 广告页(上滑)
 *
 * 「返回」单独出现时是弱信号,不会覆盖剧集/首页,避免把剧集页的返回按钮误判成广告。
 */
object AdRule {
    fun classify(scan: AdScan): PageKind = when {
        scan.adStrongVisible -> PageKind.AD
        scan.episodeKeywordVisible -> PageKind.EPISODE
        scan.homeKeywordVisible -> PageKind.HOME
        else -> PageKind.AD
    }
}
