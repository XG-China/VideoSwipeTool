package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Test

class AdRuleTest {

    private fun scan(
        allVisible: Boolean,
        anyVisible: Boolean = allVisible,
        present: Boolean = allVisible,
        episodeHeader: String? = null
    ) = AdScan(
        keywordVisible = allVisible,
        anyKeywordVisible = anyVisible,
        keywordPresent = present,
        textCount = 10,
        episodeHeader = episodeHeader
    )

    @Test
    fun `严格模式下两个关键词都可见才不滑`() {
        assertEquals(PageState.NORMAL, AdRule.classify(scan(allVisible = true), strict = true))
    }

    @Test
    fun `严格模式下缺任意一个关键词就上滑`() {
        assertEquals(PageState.AD, AdRule.classify(scan(allVisible = false, anyVisible = true), strict = true))
        assertEquals(PageState.AD, AdRule.classify(scan(allVisible = false, anyVisible = false), strict = true))
    }

    @Test
    fun `宽松模式下任意一个关键词可见就不滑`() {
        assertEquals(
            PageState.NORMAL,
            AdRule.classify(scan(allVisible = false, anyVisible = true), strict = false)
        )
    }

    @Test
    fun `宽松模式下一个关键词都看不到就上滑`() {
        assertEquals(
            PageState.AD,
            AdRule.classify(scan(allVisible = false, anyVisible = false), strict = false)
        )
    }

    @Test
    fun `顶部第X集不影响双关键词判定`() {
        assertEquals(
            PageState.AD,
            AdRule.classify(scan(allVisible = false, episodeHeader = "第3集"), strict = true)
        )
        assertEquals(
            PageState.NORMAL,
            AdRule.classify(scan(allVisible = true, episodeHeader = "第999集"), strict = true)
        )
    }
}
