package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Test

class AdRuleTest {

    private fun scan(
        episode: Boolean = false,
        home: Boolean = false,
        adWeak: Boolean = false,
        adStrong: Boolean = false,
        episodeHeader: String? = null
    ) = AdScan(
        episodeKeywordVisible = episode,
        homeKeywordVisible = home,
        adKeywordVisible = adWeak || adStrong,
        adStrongVisible = adStrong,
        textCount = 10,
        episodeHeader = episodeHeader
    )

    @Test
    fun `命中剧集关键词判为剧集页`() {
        assertEquals(PageKind.EPISODE, AdRule.classify(scan(episode = true)))
    }

    @Test
    fun `命中首页关键词判为首页`() {
        assertEquals(PageKind.HOME, AdRule.classify(scan(home = true)))
    }

    @Test
    fun `广告强关键词优先于剧集页`() {
        assertEquals(
            PageKind.AD,
            AdRule.classify(scan(episode = true, adStrong = true))
        )
    }

    @Test
    fun `只有返回这类弱广告词时不覆盖剧集页`() {
        assertEquals(
            PageKind.EPISODE,
            AdRule.classify(scan(episode = true, adWeak = true))
        )
    }

    @Test
    fun `什么都没命中时按广告页处理`() {
        assertEquals(PageKind.AD, AdRule.classify(scan()))
    }

    @Test
    fun `剧集页优先于首页`() {
        assertEquals(
            PageKind.EPISODE,
            AdRule.classify(scan(episode = true, home = true))
        )
    }
}
