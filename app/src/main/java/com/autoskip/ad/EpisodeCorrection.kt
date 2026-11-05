package com.autoskip.ad

/** 自动上滑后,相对"上滑前集数"的结果。 */
sealed interface EpisodeOutcome {
    /** 没换集(广告被滑掉,仍在同一集)或拿不到集数。 */
    data object Same : EpisodeOutcome

    /** 正好落到下一集(第 N 集 → 第 N+1 集),视为正常。 */
    data object Next : EpisodeOutcome

    /** 跳过头了(≥ N+2),应该下滑回退到 target。 */
    data class CorrectTo(val target: Int) : EpisodeOutcome

    /** 往回走或不符合预期,停止本轮。 */
    data object Misfire : EpisodeOutcome
}

/**
 * 纯逻辑:根据上滑前后的集数决定要不要回退。
 *
 * 例:上滑前第 11 集
 *  - 上滑后第 11 集 → Same(广告被跳过,仍在第 11 集)
 *  - 上滑后第 12 集 → Next(正好是下一集)
 *  - 上滑后第 13/14 集 → CorrectTo(12)(连续下滑回退)
 */
object EpisodeCorrection {
    fun decide(from: Int?, current: Int?, autoCorrect: Boolean): EpisodeOutcome {
        if (from == null || current == null) return EpisodeOutcome.Same
        return when {
            current == from -> EpisodeOutcome.Same
            current == from + 1 -> EpisodeOutcome.Next
            autoCorrect && current > from + 1 -> EpisodeOutcome.CorrectTo(from + 1)
            else -> EpisodeOutcome.Misfire
        }
    }
}
