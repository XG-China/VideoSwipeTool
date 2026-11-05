package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsParseTest {

    @Test
    fun `空输入回退默认关键词`() {
        assertEquals(listOf("选集", "第N集"), AppSettings.parseKeywords(null))
        assertEquals(listOf("选集", "第N集"), AppSettings.parseKeywords("   "))
        assertEquals(listOf("选集", "第N集"), AppSettings.parseKeywords("，，、;"))
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
    fun `第N集中的N匹配任意数字`() {
        assertTrue(AdDetector.matchesAny("第1集", listOf("第N集")))
        assertTrue(AdDetector.matchesAny("第16集 全网...", listOf("第N集")))
        assertTrue(AdDetector.matchesAny("第1000集", listOf("第N集")))
        assertFalse(AdDetector.matchesAny("选集", listOf("第N集")))
        assertFalse(AdDetector.matchesAny("第N集", listOf("第N集")))
    }

    @Test
    fun `空白关键词不参与匹配`() {
        assertFalse(AdDetector.matchesAny("随便什么文字", listOf("", "  ")))
    }

    @Test
    fun `集数标题接受任意数字`() {
        assertEquals("第1集", AdDetector.findEpisodeHeader("第1集"))
        assertEquals("第999集", AdDetector.findEpisodeHeader("第999集"))
        assertEquals("第3集", AdDetector.findEpisodeHeader("第3集 全网..."))
        assertEquals("第0集", AdDetector.findEpisodeHeader("第0集"))
        assertEquals("第1000集", AdDetector.findEpisodeHeader("第1000集"))
        assertEquals("第03集", AdDetector.findEpisodeHeader("第03集"))
        assertNull(AdDetector.findEpisodeHeader("没有集数"))
    }

    @Test
    fun `集数解析用于误滑换集检测`() {
        assertEquals(1, AdDetector.episodeNumber("第1集"))
        assertEquals(2, AdDetector.episodeNumber("第2集"))
        assertEquals(999, AdDetector.episodeNumber("第999集"))
        assertEquals(1000, AdDetector.episodeNumber("第1000集"))
        assertNull(AdDetector.episodeNumber(null))
    }

    @Test
    fun `旧版本默认关键词升级后补齐第N集`() {
        assertEquals(listOf("选集", "第N集"), AppSettings.normalizeKeywords(listOf("选集")))
    }

    @Test
    fun `自定义关键词不会被强行追加第N集`() {
        assertEquals(listOf("广告", "跳过"), AppSettings.normalizeKeywords(listOf("广告", "跳过")))
        assertEquals(listOf("选集", "跳过"), AppSettings.normalizeKeywords(listOf("选集", "跳过")))
    }

    @Test
    fun `识别广告倒计时`() {
        assertEquals(5, AdDetector.findCountdown("5s"))
        assertEquals(5, AdDetector.findCountdown("5秒"))
        assertEquals(5, AdDetector.findCountdown("广告 5"))
        assertEquals(15, AdDetector.findCountdown("广告15秒后可跳过"))
        assertNull(AdDetector.findCountdown("1.0x"))
        assertNull(AdDetector.findCountdown("23.01"))
        assertNull(AdDetector.findCountdown("第5集"))
        assertNull(AdDetector.findCountdown("全129集"))
        assertNull(AdDetector.findCountdown("155s"))
    }
}
