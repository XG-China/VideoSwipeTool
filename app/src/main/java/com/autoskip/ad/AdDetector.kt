package com.autoskip.ad

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** 当前页面类型。 */
enum class PageKind {
    /** 剧集播放页:命中「选集 / 第N集」。 */
    EPISODE,

    /** 首页:命中「关注 / 推荐 / 首页」。 */
    HOME,

    /** 广告页:命中广告关键词,或什么都没命中。 */
    AD
}

/**
 * 一次屏幕扫描的结果。
 *
 * @param episodeKeywordVisible 是否命中剧集页关键词(可见)
 * @param homeKeywordVisible    是否命中首页关键词(可见)
 * @param adKeywordVisible      是否命中广告页关键词(可见)
 * @param adStrongVisible       命中的广告关键词里是否有「返回」以外的强信号
 * @param episodeHit            实际命中的剧集关键词(用于日志)
 * @param homeHit               实际命中的首页关键词
 * @param adHit                 实际命中的广告关键词
 * @param episodeHeader         顶部左侧识别到的「第X集」标题
 * @param countdownSeconds      识别到的广告倒计时秒数
 */
data class AdScan(
    val episodeKeywordVisible: Boolean,
    val homeKeywordVisible: Boolean,
    val adKeywordVisible: Boolean,
    val adStrongVisible: Boolean,
    val textCount: Int,
    val episodeHit: String? = null,
    val homeHit: String? = null,
    val adHit: String? = null,
    val episodeHeader: String? = null,
    val countdownSeconds: Int? = null
)

/**
 * 扫描规则:用三组关键词判断当前页面。
 *
 *  - 剧集页关键词:选集、第N集 → 不滑
 *  - 首页关键词:关注、推荐、首页 → 不滑
 *  - 广告页关键词:返回、上滑继续看短剧、上滑继续观看短剧、点击进入直播间 → 上滑
 *  - 什么都没命中 → 按广告页处理,上滑
 *
 * 「第N集」里的 N 是通配符,匹配第1集、第16集、第1000集等任意数字。
 * 纯本地 UI 树解析,不联网、不 OCR、不点按钮。
 */
object AdDetector {

    private const val MAX_NODES = 2000

    /** 集数标题:第1集、第16集、第1000集……(N 为任意数字)。 */
    private val EPISODE_HEADER = Regex("""第(\d+)集""")

    /** 关键词里的集数通配符写法:第N集 / 第n集 / 第*集。 */
    fun isEpisodePatternKeyword(keyword: String): Boolean {
        val k = keyword.trim()
        return k == "第N集" || k == "第n集" || k == "第*集"
    }

    /**
     * 广告倒计时:5s / 5S / 5秒 / 广告 5 / 剩余 5 秒。
     * 用负向前瞻/后顾排除 1.5s 里的 "5s"、分辨率、剧集数字等。
     */
    private val COUNTDOWN_PATTERNS = listOf(
        Regex("""(?<![\d.])(\d{1,3})\s*[sS](?![a-zA-Z])"""),
        Regex("""(?<![\d.])(\d{1,3})\s*秒"""),
        Regex("""广告\s*(\d{1,3})""")
    )

    fun scan(
        root: AccessibilityNodeInfo,
        episodeKeywords: List<String> = AppSettings.DEFAULT_EPISODE_KEYWORDS,
        homeKeywords: List<String> = AppSettings.DEFAULT_HOME_KEYWORDS,
        adKeywords: List<String> = AppSettings.DEFAULT_AD_KEYWORDS
    ): AdScan {
        val episodeHits = HashSet<String>()
        val homeHits = HashSet<String>()
        val adHits = HashSet<String>()
        var episodeHeader: String? = null
        var episodeFallback: String? = null
        var countdownSeconds: Int? = null
        val allTexts = LinkedHashSet<String>()
        var visited = 0

        // 顶部区域的判定基准(用根节点 bounds,避免把状态栏算进去)
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val screenW = rootBounds.width().coerceAtLeast(1)
        val screenH = rootBounds.height().coerceAtLeast(1)

        // 迭代遍历 UI 树(避免深递归)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast() ?: continue
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val full = (text + " " + desc).trim()

            if (full.isNotEmpty()) {
                allTexts += full
                if (node.isVisibleToUser) {
                    for (kw in episodeKeywords) {
                        if (matchesKeyword(full, kw)) episodeHits += kw
                    }
                    for (kw in homeKeywords) {
                        if (matchesKeyword(full, kw)) homeHits += kw
                    }
                    for (kw in adKeywords) {
                        if (matchesKeyword(full, kw)) adHits += kw
                    }
                    // 「第X集」只在屏幕左上角生效,避免把列表/推荐卡片里的集数当成播放页标题
                    if (episodeHeader == null && full.contains('第')) {
                        val match = findEpisodeHeader(full)
                        if (match != null) {
                            val b = Rect().also { node.getBoundsInScreen(it) }
                            val inTopLeft = (b.top - rootBounds.top) <= screenH * 0.15 &&
                                    (b.left - rootBounds.left) <= screenW * 0.55
                            if (inTopLeft) {
                                episodeHeader = match
                            } else if (episodeFallback == null &&
                                (full.contains('|') || full.contains('｜') || full.contains('·'))
                            ) {
                                // 顶部标题读不到时,用底部标题「第37集 | 剧名」兜底
                                episodeFallback = match
                            }
                        }
                    }
                    // 广告倒计时通常是很短的文本节点(5s / 5秒 / 广告 5)
                    if (countdownSeconds == null && full.length <= 24) {
                        countdownSeconds = findCountdown(full)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AdScan(
            episodeKeywordVisible = episodeHits.isNotEmpty(),
            homeKeywordVisible = homeHits.isNotEmpty(),
            adKeywordVisible = adHits.isNotEmpty(),
            // 命中顺序按用户配置的关键词顺序,取第一个命中的词做日志/展示
            adStrongVisible = adKeywords.any { it.trim() != "返回" && it in adHits },
            textCount = allTexts.size,
            episodeHit = firstMatched(episodeKeywords, episodeHits),
            homeHit = firstMatched(homeKeywords, homeHits),
            adHit = firstMatched(adKeywords, adHits),
            episodeHeader = episodeHeader ?: episodeFallback,
            countdownSeconds = countdownSeconds
        )
    }

    /** 文本是否命中任一关键词;支持「第N集」通配符。纯函数,便于单元测试。 */
    fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { matchesKeyword(text, it) }

    /**
     * 按关键词列表顺序取第一个命中的词。
     * 例如广告词配置为 [返回, 上滑继续看短剧],两个都命中时返回「返回」。
     */
    fun firstMatched(keywords: List<String>, matched: Set<String>): String? =
        keywords.firstOrNull { it in matched }

    /** 单个关键词匹配:普通词按字面量,「第N集」按任意数字。 */
    fun matchesKeyword(text: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        if (isEpisodePatternKeyword(keyword)) return EPISODE_HEADER.containsMatchIn(text)
        return text.contains(keyword, ignoreCase = true)
    }

    /** 从文本中提取「第X集」;X 为任意数字。没有则返回 null。 */
    fun findEpisodeHeader(text: String): String? = EPISODE_HEADER.find(text)?.value

    /** 把「第3集」解析成 3;不是合法集数则返回 null。 */
    fun episodeNumber(header: String?): Int? =
        header?.let { EPISODE_HEADER.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    /** 从文本中识别广告倒计时秒数(0..120);不是倒计时返回 null。纯函数,便于单元测试。 */
    fun findCountdown(text: String): Int? {
        for (pattern in COUNTDOWN_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val seconds = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (seconds in 0..120) return seconds
        }
        return null
    }
}
