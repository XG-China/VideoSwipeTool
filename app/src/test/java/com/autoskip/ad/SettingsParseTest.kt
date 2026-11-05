package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsParseTest {

    @Test
    fun `空输入回退默认关键词`() {
        assertEquals(listOf("选集"), AppSettings.parseKeywords(null))
        assertEquals(listOf("选集"), AppSettings.parseKeywords("   "))
        assertEquals(listOf("选集"), AppSettings.parseKeywords("，，、;"))
    }

    @Test
    fun `支持中英文分隔符并去重`() {
        assertEquals(
            listOf("选集", "跳过", "广告"),
            AppSettings.parseKeywords("选集, 跳过，广告、跳过\n选集")
        )
    }

    @Test
    fun `关键词匹配忽略大小写`() {
        assertTrue(AdDetector.matchesAny("Skip AD", listOf("skip")))
        assertTrue(AdDetector.matchesAny("免费短剧 · 选集", listOf("选集")))
        assertFalse(AdDetector.matchesAny("首页推荐", listOf("选集")))
    }

    @Test
    fun `空白关键词不参与匹配`() {
        assertFalse(AdDetector.matchesAny("随便什么文字", listOf("", "  ")))
    }

    @Test
    fun `集数标题只接受1到999`() {
        assertEquals("第1集", AdDetector.findEpisodeHeader("第1集"))
        assertEquals("第999集", AdDetector.findEpisodeHeader("第999集"))
        assertEquals("第3集", AdDetector.findEpisodeHeader("第3集 全网..."))
        assertNull(AdDetector.findEpisodeHeader("第0集"))
        assertNull(AdDetector.findEpisodeHeader("第1000集"))
        assertNull(AdDetector.findEpisodeHeader("第03集"))
        assertNull(AdDetector.findEpisodeHeader("没有集数"))
    }

    @Test
    fun `集数解析用于误滑换集检测`() {
        assertEquals(1, AdDetector.episodeNumber("第1集"))
        assertEquals(2, AdDetector.episodeNumber("第2集"))
        assertEquals(999, AdDetector.episodeNumber("第999集"))
        assertNull(AdDetector.episodeNumber("第0集"))
        assertNull(AdDetector.episodeNumber(null))
    }
}
