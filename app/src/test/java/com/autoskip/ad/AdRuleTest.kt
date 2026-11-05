package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Test

class AdRuleTest {

    private fun scan(allVisible: Boolean, episodeHeader: String? = null) = AdScan(
        keywordVisible = allVisible,
        keywordPresent = allVisible,
        textCount = 10,
        episodeHeader = episodeHeader
    )

    @Test
    fun `所有关键词都可见时不滑`() {
        assertEquals(PageState.NORMAL, AdRule.classify(scan(allVisible = true)))
    }

    @Test
    fun `缺任意一个关键词就上滑`() {
        assertEquals(PageState.AD, AdRule.classify(scan(allVisible = false)))
    }

    @Test
    fun `顶部第X集不影响双关键词判定`() {
        assertEquals(PageState.AD, AdRule.classify(scan(allVisible = false, episodeHeader = "第3集")))
        assertEquals(PageState.NORMAL, AdRule.classify(scan(allVisible = true, episodeHeader = "第999集")))
    }
}
