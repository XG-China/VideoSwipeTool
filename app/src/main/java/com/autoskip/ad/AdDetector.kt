package com.autoskip.ad

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一次屏幕扫描的结果。
 *
 * @param keywordVisible    所有配置的关键词是否都可见
 * @param keywordPresent    是否至少有一个关键词存在于 UI 树(不看可见性,日志用)
 * @param textCount         全树去重文字总数
 * @param episodeHeader     顶部左侧识别到的「第X集」标题,没有则为 null
 * @param countdownSeconds  识别到的广告倒计时秒数,没有则为 null
 */
data class AdScan(
    val keywordVisible: Boolean,
    val keywordPresent: Boolean,
    val textCount: Int,
    val episodeHeader: String? = null,
    val countdownSeconds: Int? = null
)

/**
 * 扫描规则:
 *
 *   配置里的所有关键词同时可见 → 不滑;缺任意一个 → 上滑。
 *
 * 默认关键词是「选集」和「第N集」;换成别的关键词也遵循同一条规则。
 * 「第N集」里的 N 是通配符,匹配第1集、第16集、第1000集等任意数字。
 * 纯本地 UI 树解析,不联网、不 OCR、不点按钮。
 */
object AdDetector {

    private const val MAX_NODES = 2000

    /** 集数标题:第1集、第16集、第1000集……(N 为任意数字)。 */
    private val EPISODE_HEADER = Regex("""第(\d+)集""")

    /**
     * 广告倒计时:5s / 5S / 5秒 / 广告 5 / 剩余 5 秒。
     * 用负向前瞻/后顾排除 1.5s 里的 "5s"、分辨率、剧集数字等。
     */
    private val COUNTDOWN_PATTERNS = listOf(
        Regex("""(?<![\d.])(\d{1,3})\s*[sS](?![a-zA-Z])"""),
        Regex("""(?<![\d.])(\d{1,3})\s*秒"""),
        Regex("""广告\s*(\d{1,3})""")
    )

    /** 关键词里的集数通配符写法:第N集 / 第n集 / 第*集。 */
    fun isEpisodePatternKeyword(keyword: String): Boolean {
        val k = keyword.trim()
        return k == "第N集" || k == "第n集" || k == "第*集"
    }

    fun scan(
        root: AccessibilityNodeInfo,
        keywords: List<String> = AppSettings.DEFAULT_KEYWORDS
    ): AdScan {
        val required = keywords.filter { it.isNotBlank() }.ifEmpty { AppSettings.DEFAULT_KEYWORDS }
        val visible = HashSet<String>()
        val present = HashSet<String>()
        var episodeHeader: String? = null
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
                for (kw in required) {
                    if (!matchesKeyword(full, kw)) continue
                    present += kw
                    if (node.isVisibleToUser) visible += kw
                }
                // 「第X集」只在屏幕左上角生效,避免把列表/推荐卡片里的集数当成播放页标题
                if (episodeHeader == null && node.isVisibleToUser && full.contains('第')) {
                    val match = findEpisodeHeader(full)
                    if (match != null) {
                        val b = Rect().also { node.getBoundsInScreen(it) }
                        val inTopLeft = (b.top - rootBounds.top) <= screenH * 0.15 &&
                                (b.left - rootBounds.left) <= screenW * 0.55
                        if (inTopLeft) episodeHeader = match
                    }
                }
                // 广告倒计时通常是很短的文本节点(5s / 5秒 / 广告 5)
                if (countdownSeconds == null && node.isVisibleToUser && full.length <= 24) {
                    countdownSeconds = findCountdown(full)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AdScan(
            keywordVisible = visible.size >= required.size,
            keywordPresent = present.isNotEmpty(),
            textCount = allTexts.size,
            episodeHeader = episodeHeader,
            countdownSeconds = countdownSeconds
        )
    }

    /** 文本是否命中任一关键词;支持「第N集」通配符。纯函数,便于单元测试。 */
    fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { matchesKeyword(text, it) }

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
