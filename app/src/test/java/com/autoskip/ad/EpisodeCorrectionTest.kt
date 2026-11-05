package com.autoskip.ad

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCorrectionTest {

    @Test
    fun `没换集时保持原样`() {
        assertEquals(EpisodeOutcome.Same, EpisodeCorrection.decide(11, 11, autoCorrect = true))
        assertEquals(EpisodeOutcome.Same, EpisodeCorrection.decide(11, null, autoCorrect = true))
        assertEquals(EpisodeOutcome.Same, EpisodeCorrection.decide(null, 12, autoCorrect = true))
    }

    @Test
    fun `正好下一集视为正常`() {
        assertEquals(EpisodeOutcome.Next, EpisodeCorrection.decide(11, 12, autoCorrect = true))
    }

    @Test
    fun `跳过头时回退到下一集`() {
        assertEquals(EpisodeOutcome.CorrectTo(12), EpisodeCorrection.decide(11, 13, autoCorrect = true))
        assertEquals(EpisodeOutcome.CorrectTo(12), EpisodeCorrection.decide(11, 18, autoCorrect = true))
    }

    @Test
    fun `关闭自动回退时按误滑处理`() {
        assertEquals(EpisodeOutcome.Misfire, EpisodeCorrection.decide(11, 13, autoCorrect = false))
    }

    @Test
    fun `往回走按误滑处理`() {
        assertEquals(EpisodeOutcome.Misfire, EpisodeCorrection.decide(11, 10, autoCorrect = true))
    }
}
