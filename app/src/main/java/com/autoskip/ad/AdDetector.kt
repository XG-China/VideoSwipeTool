package com.autoskip.ad

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一次屏幕扫描的结果。
 *
 * @param keywordVisible 是否至少有一个可见节点命中关键词(剧集页正常情况下应为 true)
 * @param keywordPresent 是否至少有一个节点命中关键词,不管可见性
 *                       (广告覆盖时节点仍在树里但不可见,这是播放页的关键特征)
 * @param textCount      全树去重文字总数
 * @param episodeHeader  顶部左侧识别到的「第X集」标题(X=1..999),没有则为 null
 */
data class AdScan(
    val keywordVisible: Boolean,
    val keywordPresent: Boolean,
    val textCount: Int,
    val episodeHeader: String? = null
)

/**
 * 扫描规则:
 *
 *   播放页特征成立(关键词节点存在,或顶部有「第X集」),但关键词不可见 → 广告覆盖,上滑。
 *
 * 只认 **isVisibleToUser = true** 的节点来判断"可见";同时统计**不看可见性**的
 * 关键词节点数,用来区分"播放页被盖住"和"根本不是播放页"。
 *
 * 纯本地 UI 树解析,不联网、不 OCR、不点按钮。
 */
object AdDetector {

    private const val MAX_NODES = 2000

    /** 顶部集数标题:第1集 ~ 第999集(不接受 0、4 位以上数字)。 */
    private val EPISODE_HEADER = Regex("""第([1-9]\d{0,2})集""")

    fun scan(
        root: AccessibilityNodeInfo,
        keywords: List<String> = AppSettings.DEFAULT_KEYWORDS
    ): AdScan {
        var keywordVisible = false
        var keywordPresent = false
        var episodeHeader: String? = null
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
                if (!keywordPresent || !keywordVisible) {
                    val hit = matchesAny(full, keywords)
                    if (hit) {
                        // 不看可见性:只要树里有,就说明当前页面具备播放页特征
                        keywordPresent = true
                        if (node.isVisibleToUser) keywordVisible = true
                    }
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
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AdScan(
            keywordVisible = keywordVisible,
            keywordPresent = keywordPresent,
            textCount = allTexts.size,
            episodeHeader = episodeHeader
        )
    }

    /** 文本是否命中任一关键词(忽略大小写)。纯函数,便于单元测试。 */
    fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }

    /** 从文本中提取「第X集」(X=1..999);没有则返回 null。纯函数,便于单元测试。 */
    fun findEpisodeHeader(text: String): String? = EPISODE_HEADER.find(text)?.value

    /** 把「第3集」解析成 3;不是合法集数则返回 null。 */
    fun episodeNumber(header: String?): Int? =
        header?.let { EPISODE_HEADER.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}
