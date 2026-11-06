package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMatcherTest {

    @Test
    fun normalizeRemovesSpacesAndPunctuation() {
        assertEquals("上滑继续观看短剧", OcrMatcher.normalize("上滑 继续，观看。短剧！"))
    }

    @Test
    fun exactMatchWithSpaces() {
        assertTrue(OcrMatcher.matches("上滑 继续 观看 短剧", "上滑继续观看短剧"))
    }

    @Test
    fun longKeywordAllowsOneOcrError() {
        // OCR 把「剧」识别成「刷」
        assertTrue(OcrMatcher.matches("上滑继续观看短刷", "上滑继续观看短剧"))
    }

    @Test
    fun longKeywordAllowsTwoOcrErrors() {
        assertTrue(OcrMatcher.matches("上滑继渎观看短刷", "上滑继续观看短剧"))
    }

    @Test
    fun longKeywordRejectsTooManyErrors() {
        assertFalse(OcrMatcher.matches("上滑继渎观着短刷", "上滑继续观看短剧"))
    }

    @Test
    fun shortKeywordRequiresExactMatch() {
        assertFalse(OcrMatcher.matches("回返", "返回"))
        assertFalse(OcrMatcher.matches("直播", "直播中"))
        assertTrue(OcrMatcher.matches("请点击返回", "返回"))
    }

    @Test
    fun joinLineCanMatch() {
        assertTrue(OcrMatcher.matches("点击进入直播问好物热播中", "点击进入直播间"))
    }

    @Test
    fun firstHitUsesKeywordOrder() {
        assertEquals(
            "返回",
            OcrMatcher.firstHit("返回 上滑继续观看短剧", listOf("返回", "上滑继续观看短剧"))
        )
    }
}
