package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
