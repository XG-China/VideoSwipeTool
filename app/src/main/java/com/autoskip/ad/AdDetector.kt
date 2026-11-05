package com.autoskip.ad

import android.view.accessibility.AccessibilityNodeInfo

/** 一次屏幕扫描的结果。 */
data class AdScan(
    val 无选集: Boolean,        // 屏幕上找不到「选集」二字 → 上滑
    val textCount: Int         // 全树去重文字总数
)

/**
 * 扫描规则(只此一条):
 *
 *   屏幕上找不到「选集」二字 → 上滑。
 *
 * 只认 **isVisibleToUser = true** 的节点。剧集页常驻「选集」,但广告盖上来、
 * 通知栏拉下来时,那个节点还在树里却已经不可见 —— 这时就要滑。
 *
 * 纯本地 UI 树解析,不联网、不 OCR、不点按钮。
 */
object AdDetector {

    private val ZH_EPISODE = listOf("选集")

    private const val MAX_NODES = 2000

    fun scan(root: AccessibilityNodeInfo): AdScan {
        var 有选集 = false
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
                // 不可见的节点一律不算数:被上层盖住 = 当作没有
                if (!有选集 && node.isVisibleToUser) {
                    for (kw in ZH_EPISODE) {
                        if (full.contains(kw, ignoreCase = true)) { 有选集 = true; break }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AdScan(
            无选集 = !有选集,
            textCount = allTexts.size
        )
    }
}
