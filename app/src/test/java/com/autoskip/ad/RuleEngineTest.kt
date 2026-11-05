package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleEngineTest {

    private fun ctx(
        visible: Boolean = false,
        present: Boolean = false,
        episode: Int? = null,
        texts: Int = 20,
        strict: Boolean = true
    ) = RuleContext(visible, present, episode, texts, strict)

    @Test
    fun `默认规则等价于严格判定`() {
        val rule = RuleEngine.compile(AppSettings.DEFAULT_RULE_TEXT)
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(visible = true, present = true, episode = 2)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(visible = false, present = true, episode = 2)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(visible = false, present = false, episode = 3)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(visible = false, present = false, episode = null)))
    }

    @Test
    fun `如果真与如果等价`() {
        val rule = RuleEngine.compile(
            """
            如果真 集数 >= 2 且 集数 <= 999
                上滑
            结束如果
            """.trimIndent()
        )
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(episode = 2)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(episode = 999)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = 1)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = null)))
    }

    @Test
    fun `支持与或非和括号`() {
        val rule = RuleEngine.compile(
            """
            如果 (可见关键词 或 存在关键词) 且 非 (集数 = 5)
                上滑
            结束如果
            """.trimIndent()
        )
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(visible = true, episode = 2)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(present = true, episode = 2)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(visible = true, episode = 5)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(visible = false, present = false)))
    }

    @Test
    fun `否则如果按顺序匹配`() {
        val rule = RuleEngine.compile(
            """
            如果 集数 >= 10
                不动作
            否则如果 集数 >= 2
                上滑
            否则
                不动作
            结束如果
            """.trimIndent()
        )
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = 10)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(episode = 3)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = 1)))
    }

    @Test
    fun `文字数条件生效`() {
        val rule = RuleEngine.compile(
            """
            如果 文字数 >= 30 且 存在关键词
                上滑
            结束如果
            """.trimIndent()
        )
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(present = true, texts = 46)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(present = true, texts = 12)))
    }

    @Test
    fun `没有集数时数值比较为假`() {
        val rule = RuleEngine.compile(
            """
            如果 集数 != 3
                上滑
            结束如果
            """.trimIndent()
        )
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = null)))
        assertEquals(RuleDecision.IGNORE, rule.decide(ctx(episode = 3)))
        assertEquals(RuleDecision.SWIPE, rule.decide(ctx(episode = 4)))
    }

    @Test
    fun `格式错误会给出提示`() {
        assertThrows(RuleParseException::class.java) {
            RuleEngine.compile("如果 可见关键词\n    上滑")
        }
        assertThrows(RuleParseException::class.java) {
            RuleEngine.compile("如果 可见关键词\n    乱写动作\n结束如果")
        }
        assertThrows(RuleParseException::class.java) {
            RuleEngine.compile("如果 可见关键词\n结束如果")
        }
        assertThrows(RuleParseException::class.java) {
            RuleEngine.compile("如果 未知信号\n    上滑\n结束如果")
        }
    }

    @Test
    fun `一个分支不能写两个动作`() {
        assertThrows(RuleParseException::class.java) {
            RuleEngine.compile(
                """
                如果 可见关键词
                    上滑
                    不动作
                结束如果
                """.trimIndent()
            )
        }
    }
}
