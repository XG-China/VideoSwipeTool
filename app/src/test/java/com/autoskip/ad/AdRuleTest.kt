package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Test

class AdRuleTest {

    private fun scan(visible: Boolean, present: Boolean) =
        AdScan(keywordVisible = visible, keywordPresent = present, textCount = 10)

    @Test
    fun `关键词可见时是正常页面`() {
        assertEquals(PageState.NORMAL, AdRule.classify(scan(visible = true, present = true), strict = true))
        assertEquals(PageState.NORMAL, AdRule.classify(scan(visible = true, present = true), strict = false))
    }

    @Test
    fun `严格模式下关键词存在但不可见才是广告`() {
        assertEquals(PageState.AD, AdRule.classify(scan(visible = false, present = true), strict = true))
    }

    @Test
    fun `严格模式下首页这类没有关键词的页面不动作`() {
        assertEquals(PageState.UNKNOWN, AdRule.classify(scan(visible = false, present = false), strict = true))
    }

    @Test
    fun `宽松模式保持旧的找不到就滑行为`() {
        assertEquals(PageState.AD, AdRule.classify(scan(visible = false, present = false), strict = false))
    }
}
