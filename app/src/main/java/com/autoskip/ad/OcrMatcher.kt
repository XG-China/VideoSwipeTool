package com.autoskip.ad

/**
 * OCR 文本匹配。
 *
 * 目标:不只做"整段文字必须完全一致"的包含判断。
 *  OCR 经常出现空格、标点、个别字识别错误(例:「短剧」→「短刷」),
 *  这里先归一化,再对长关键词做小编辑距离的模糊匹配,提高命中率。
 *
 * 短关键词(返回/直播中)仍然要求精确,避免误判。
 */
object OcrMatcher {

    /** 去掉空白与标点,统一小写。中文字符不受影响。 */
    fun normalize(raw: String): String =
        raw.replace(Regex("[\\s\\p{P}\\p{S}]"), "").lowercase()

    /**
     * text 是否命中 keyword。
     *
     * - 归一化后直接包含 → 命中
     * - 长度 ≥4 的关键词允许少量 OCR 错字:
     *   4–6 字最多错 1 个,≥7 字最多错 2 个
     * - 长度 <4 的关键词只做精确匹配(返回/直播中)
     */
    fun matches(text: String, keyword: String): Boolean {
        val t = normalize(text)
        val k = normalize(keyword)
        if (k.isEmpty()) return false
        if (t.contains(k)) return true
        if (k.length < 4) return false
        val maxDistance = if (k.length >= 7) 2 else 1
        // 滑动等长窗口,找任意一段是否与关键词足够接近
        val last = t.length - k.length
        if (last < 0) return false
        for (i in 0..last) {
            if (withinDistance(t, i, k, maxDistance)) return true
        }
        return false
    }

    /** 返回 text 里第一个命中的关键词(按 keywords 顺序),没有则 null。 */
    fun firstHit(text: String, keywords: List<String>): String? =
        keywords.firstOrNull { matches(text, it) }

    /**
     * 在 text 的 offset 处取与 pattern 等长的窗口,计算有界编辑距离。
     * 支持替换/插入/删除,行内最小值超过 maxDistance 时提前退出。
     */
    private fun withinDistance(text: String, offset: Int, pattern: String, maxDistance: Int): Boolean {
        val n = pattern.length
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..n) {
                val cost = if (text[offset + i - 1] == pattern[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > maxDistance) return false
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[n] <= maxDistance
    }
}
