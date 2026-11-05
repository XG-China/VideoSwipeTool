package com.autoskip.ad

/**
 * 主界面可调的高级时序参数。
 *
 * ms 类的值以毫秒存储、界面按秒显示；次数类以整数显示。
 */
enum class Timing(
    val key: String,
    val label: String,
    val default: Long,
    val min: Long,
    val max: Long,
    val step: Long,
    val unit: String
) {
    READY_BUFFER(
        "timing_ready_buffer_ms", "服务连接缓冲期",
        4_000, 0, 15_000, 500, "s"
    ),
    USER_SCROLL_GUARD(
        "timing_user_scroll_guard_ms", "用户翻页守卫",
        3_000, 0, 15_000, 500, "s"
    ),
    SWIPE_COOLDOWN(
        "timing_swipe_cooldown_ms", "两次上滑最小间隔",
        3_000, 0, 15_000, 500, "s"
    ),
    QUIET(
        "timing_quiet_ms", "上滑后安静期",
        3_000, 0, 15_000, 500, "s"
    ),
    POST_SWIPE_PAUSE(
        "timing_post_swipe_pause_ms", "手势后暂停扫描",
        3_000, 0, 15_000, 500, "s"
    ),
    AD_DEBOUNCE(
        "timing_ad_debounce_ms", "判定防抖",
        1_000, 0, 10_000, 500, "s"
    ),
    AUTO_SWIPE_WINDOW(
        "timing_auto_swipe_window_ms", "自动滑动时间窗",
        1_500, 0, 10_000, 500, "s"
    ),
    BALL_RETRY(
        "timing_ball_retry_ms", "悬浮球重试间隔",
        15_000, 3_000, 120_000, 1_000, "s"
    ),
    BALL_MISSING_LOG(
        "timing_ball_missing_log_ms", "悬浮球警告节流",
        30_000, 5_000, 300_000, 5_000, "s"
    ),
    AD_STUCK_LOG(
        "timing_ad_stuck_log_ms", "广告停留日志节流",
        30_000, 5_000, 300_000, 5_000, "s"
    ),
    MAX_SWIPE_ATTEMPTS(
        "timing_max_swipe_attempts", "最大连滑次数",
        10, 1, 30, 1, "次"
    ),
    REQUIRED_MISS_SAMPLES(
        "timing_required_miss_samples", "重试前复核次数",
        3, 1, 10, 1, "次"
    );

    /** Compose Slider 的 steps 参数。 */
    val sliderSteps: Int
        get() = (((max - min) / step).toInt() - 1).coerceAtLeast(0)
}
