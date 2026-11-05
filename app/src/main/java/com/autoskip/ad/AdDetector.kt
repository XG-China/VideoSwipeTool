package com.autoskip.ad

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一次屏幕扫描的结果。
 *
 * @param keywordVisible 是否至少有一个可见节点命中关键词(剧集页正常情况下应为 true)
 * @param keywordPresent 是否至少有一个节点命中关键词,不管可见性
 *                       (广告覆盖时节点仍在树里但不可见,这是播放页的关键特征)
 * @param textCount      全树去重文字总数
 */
data class AdScan(
    val keywordVisible: Boolean,
    val keywordPresent: Boolean,
    val textCount: Int
)

/**
 * 扫描规则:
 *
 *   屏幕上找不到可见的「选集」,但树里仍然存在「选集」节点 → 判定为广告覆盖,上滑。
 *
 * 只认 **isVisibleToUser = true** 的节点来判断"可见";同时统计**不看可见性**的
 * 关键词节点数,用来区分"播放页被盖住"和"根本不是播放页"。
 *
 * 纯本地 UI 树解析,不联网、不 OCR、不点按钮。
 */
object AdDetector {

    private const val MAX_NODES = 2000

    fun scan(
        root: AccessibilityNodeInfo,
        keywords: List<String> = AppSettings.DEFAULT_KEYWORDS
    ): AdScan {
        var keywordVisible = false
        var keywordPresent = false
        val allTexts = LinkedHashSet<String>()
        var visited = 0

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
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AdScan(
            keywordVisible = keywordVisible,
            keywordPresent = keywordPresent,
            textCount = allTexts.size
        )
    }

    /** 文本是否命中任一关键词(忽略大小写)。纯函数,便于单元测试。 */
    fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
}
