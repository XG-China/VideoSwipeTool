package com.autoskip.ad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 通用视频广告自动上滑无障碍服务(免 root):
 *  1. 只处理用户已开启的目标应用(默认一个都不开,由用户在 App 内选择);
 *     服务本身接收所有 App 的事件,但在 onAccessibilityEvent 里按目标集合过滤
 *  2. 用三组关键词判断页面:剧集页/首页不滑,广告页和未知页面上滑
 *  3. 用户手动翻页后 3 秒内一律不自动滑(不抢操作)
 * 不做状态机、不自动点按钮、不猜倒计时文案——用户指哪打哪。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_CORRECTIONS = 3              // 误滑换集后最多下滑回退几次
        private const val CORRECTION_PAUSE_MS = 2500L      // 每次回退后等待页面稳定再检查

        /** 手动上滑指令:adb shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW */
        const val ACTION_SWIPE_NOW = "com.autoskip.ad.action.SWIPE_NOW"
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var readyAt = 0L          // 服务刚连接后的缓冲期结束时间点(期间不自动滑)
    private var lastSwipeAt = 0L
    private var quietUntil = 0L       // 上滑后的安静期
    private var prevAdContext = false // 上一帧判定是否成立(上升沿判断)
    private var lastScanAt = 0L
    private var lastBallRetryAt = 0L
    private var lastBallMissingLogAt = 0L
    private var lastAdClearAt = 0L    // 判定开始持续消失的时刻
    private var autoSwipeAt = 0L      // 我们自己发起手势的时间
    private var lastUserScrollAt = 0L // 用户最近一次手动翻页的时间
    private var lastUserScrollLogAt = 0L
    private var lastCooldownLogAt = 0L
    private var lastStuckLogAt = 0L   // "提示持续残留"的日志节流
    private var swipeAttempts = 0     // 本轮连续上滑次数
    private var missSamples = 0       // 连续多少次校验都"看不到关键词"
    private var resumeScanAt = 0L     // 上滑后的操作暂停截止时刻
    private var resultTask: Runnable? = null
    private var scanning = false      // 防止重复启动扫描循环
    private var lastForegroundPkg = ""  // 事件里记录的前台包名,拿不到窗口时的兜底
    private var lastKnownEpisode: Int? = null   // 最近一次看到的「第X集」
    private var swipeEpisode: Int? = null       // 发起手势时所在的集数(防误滑换集的基准)
    private var swipeAt = 0L                    // 发起手势的时刻
    private var overshootLockUntil = 0L          // 误滑换集后的锁定截止时刻,期间不再自动滑
    private var correctionTarget: Int? = null    // 误滑后要回到的集数
    private var correctionStartedAt = 0L
    private var correctionAttempts = 0
    private var correctionRetries = 0
    private var correctionTask: Runnable? = null
    private var postCorrectionUntil = 0L          // 回退成功后的短暂静默期,避免立刻又上滑
    private var countdownUntil = 0L               // 广告倒计时预计结束时刻
    private var lastCountdownValue: Int? = null   // 上一次看到的倒计时秒数
    private var countdownValueAt = 0L
    private var episodeReadRetries = 0            // 换页动画期间读不到集数时重试次数
    private var resultRetries = 0                 // 拿不到窗口时的重试次数
    private var destroyed = false
    private var receiverRegistered = false
    private var pendingNextTarget: Int? = null    // 已到 N+1,继续观察是否会滑到 N+2
    private var pendingNextCount = 0
    private var lastPageKind: PageKind? = null
    private var lastPageLogAt = 0L

    /** 读取当前时序设置(修改后立即生效)。 */
    private fun t(timing: Timing): Long = AppSettings.timing(this, timing)

    // 定时主动扫描:红果广告可能是静态 SurfaceView(不产生无障碍事件),
    // 只靠事件驱动会漏扫 → 必须定时轮询。间隔由设置决定(默认 1 秒)。
    private val 扫描任务 = object : Runnable {
        override fun run() {
            runCatching { 自愈悬浮球(System.currentTimeMillis()) }
            runCatching { 执行扫描() }
            handler.postDelayed(this, AppSettings.scanIntervalMs(this@AdSkipAccessibilityService))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.migrateConfig(this) // 清掉历史目标应用,默认由用户重新选择
        // 启动保活前台服务,防止 MIUI 冻结进程导致断连
        GuardService.start(this)
        // 注册手动上滑指令(验证手势链路 + 通知栏按钮用)
        if (!receiverRegistered) {
            runCatching {
                val f = android.content.IntentFilter(ACTION_SWIPE_NOW)
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    手动上滑,
                    f,
                    // 保持 EXPORTED:README 里的 adb 调试指令来自 shell(不同 UID)。
                    // 风险仅限第三方 App 能触发一次上滑,不涉及数据。
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                receiverRegistered = true
            }.onFailure {
                LogBuf.add("手动上滑接收器注册失败: ${it.message}")
            }
        }
        // 悬浮球
        ToggleBall.show(this)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            LogBuf.add("警告:未授予「显示在其他应用上层」权限,悬浮球/提示条不可见,请到 设置→应用→AutoSkip 开启")
            runCatching {
                android.widget.Toast.makeText(this, "请开启「显示在其他应用上层」权限,否则悬浮球不可见", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // 重连/启动后缓冲期内不自动滑:防止恢复瞬间把正在看的视频划走
        readyAt = System.currentTimeMillis() + t(Timing.READY_BUFFER)
        // 启动定时扫描(静态广告无事件也能被发现)
        if (!scanning) {
            scanning = true
            handler.postDelayed(扫描任务, AppSettings.scanIntervalMs(this))
        }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        val targets = AppSettings.targetPackages(this).map { pkg ->
            AppSettings.KNOWN_APPS.firstOrNull { it.pkg == pkg }?.name ?: pkg
        }.joinToString("/").ifEmpty { "无" }
        LogBuf.add("[启动] v$version 目标=$targets")
        LogBuf.add(
            "[关键词] 剧集=${AppSettings.episodeKeywords(this).joinToString("/")} " +
                "首页=${AppSettings.homeKeywords(this).joinToString("/")} " +
                "广告=${AppSettings.adKeywords(this).joinToString("/")}"
        )
        LogBuf.add(
            "[时序] 扫描=${AppSettings.scanIntervalMs(this)}ms " +
                "缓冲=${t(Timing.READY_BUFFER)}ms 守卫=${t(Timing.USER_SCROLL_GUARD)}ms " +
                "冷却=${t(Timing.SWIPE_COOLDOWN)}ms 安静=${t(Timing.QUIET)}ms " +
                "复核=${t(Timing.REQUIRED_MISS_SAMPLES)}次 连滑=${t(Timing.MAX_SWIPE_ATTEMPTS)}次"
        )
        LogBuf.add("无障碍服务已连接,自动广告跳过已就绪(${AppSettings.statsText(this)})")
        broadcastConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (destroyed) return
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg.startsWith("com.autoskip")) return // 不对自己 App 做动作
        // 现在服务接收所有 App 的事件,这里只处理已开启的目标应用
        if (pkg !in AppSettings.targetPackages(this)) return
        val now = System.currentTimeMillis()
        // 记录前台包名,作为 rootInActiveWindow 短暂为 null 时的兜底
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPkg = pkg
        }
        // 悬浮球自愈:初次连接若悬浮窗权限未授,补授权后自动重挂
        自愈悬浮球(now)
        if (!可以动手()) return

        // 区分"用户手动翻页"与"我们的自动上滑"
        if (ev.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // 回退过程中给下滑手势更长的窗口,避免把延迟到达的滚动事件误判成用户翻页
            val autoWindow = if (correctionTarget != null) CORRECTION_PAUSE_MS + 500L else t(Timing.AUTO_SWIPE_WINDOW)
            if (autoSwipeAt == 0L || now - autoSwipeAt > autoWindow) {
                lastUserScrollAt = now
                postCorrectionUntil = 0L // 用户接管,解除回退后的静默
                if (now - lastUserScrollLogAt > 5000L) {
                    lastUserScrollLogAt = now
                    LogBuf.add("检测到用户手动翻页,3秒内自动跳过暂停")
                }
            }
        }
        // 事件到达时立即扫一次(定时器轮询为主,这里作低延迟补充)
        执行扫描()
    }

    /**
     * 悬浮球自愈:每 15 秒试一次。
     * 定时器和无障碍事件都会调 —— 红果播放时可能长时间没有无障碍事件。
     */
    private fun 自愈悬浮球(now: Long) {
        if (ToggleBall.isShowing) {
            lastBallMissingLogAt = 0L
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            if (now - lastBallMissingLogAt > t(Timing.BALL_MISSING_LOG)) {
                lastBallMissingLogAt = now
                LogBuf.add("悬浮球挂不上:未授予「显示在其他应用上层」权限,授权后会自动出现")
            }
            return
        }
        if (now - lastBallRetryAt < t(Timing.BALL_RETRY)) return
        lastBallRetryAt = now
        ToggleBall.show(this)
    }

    /** 当前前台包名:优先活动窗口,拿不到时用事件里记录的上一个前台包名。 */
    private fun 当前包名(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty().ifEmpty { lastForegroundPkg }

    /**
     * 现在允许对屏幕动手吗:总开关开着,且当前前台窗口属于已启用的目标应用。
     * 扫描入口和重试链都必须过这一关。
     */
    private fun 可以动手(): Boolean {
        if (!AppSettings.masterEnabled(this)) return false
        val pkg = 当前包名()
        // 实在拿不到包名时不在这里拦,交给 执行扫描() 的 root 判空处理。
        if (pkg.isEmpty()) return true
        return pkg in AppSettings.targetPackages(this)
    }

    /** 用三组关键词扫描当前页面。 */
    private fun 扫描页面(root: AccessibilityNodeInfo): AdScan = AdDetector.scan(
        root,
        AppSettings.episodeKeywords(this),
        AppSettings.homeKeywords(this),
        AppSettings.adKeywords(this)
    )

    /** 页面分类的日志标签。 */
    private fun 页面标签(scan: AdScan): String = when {
        scan.adStrongVisible -> "广告页(命中「${scan.adHit}」)"
        scan.episodeKeywordVisible -> "剧集页(命中「${scan.episodeHit}」)"
        scan.homeKeywordVisible -> "首页(命中「${scan.homeHit}」)"
        scan.adKeywordVisible -> "广告页(弱信号「${scan.adHit}」)"
        else -> "广告页(未命中任何关键词)"
    }

    /**
     * 执行一次扫描(定时器 + 无障碍事件都会触发)。
     * 判定规则见 [AdRule]:严格模式下只有"关键词在树里但不可见"才动手。
     */
    private fun 执行扫描() {
        if (destroyed) return
        if (!可以动手()) return
        // 正在回退换集时不再触发任何自动上滑
        if (correctionTarget != null) return
        val now = System.currentTimeMillis()
        // 手势送达后暂停扫描 3 秒(给跳过/翻页留时间)
        if (now < resumeScanAt) return
        // 扫描节奏由设置决定(默认每秒 1 次)
        if (now - lastScanAt < AppSettings.scanIntervalMs(this)) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        // 窗口已经切走(如通知栏/系统弹窗)时绝不动手
        if (pkg.isNotEmpty() && pkg !in AppSettings.targetPackages(this)) return

        val scan = 扫描页面(root)
        // 记录最近一次看到的集数,作为"上滑是否换集"的基准
        AdDetector.episodeNumber(scan.episodeHeader)?.let { lastKnownEpisode = it }
        val page = AdRule.classify(scan)

        // 页面切换日志(3 秒节流),方便排查为什么滑/不滑
        if (page != lastPageKind && now - lastPageLogAt >= 3000L) {
            lastPageKind = page
            lastPageLogAt = now
            LogBuf.add(
                "[页面] ${页面标签(scan)} | 剧集=${scan.episodeHit ?: "-"} " +
                    "首页=${scan.homeHit ?: "-"} 广告=${scan.adHit ?: "-"} " +
                    "集数=${scan.episodeHeader ?: "-"} 文字=${scan.textCount}"
            )
        }

        val canSwipeAd = now >= readyAt && now >= quietUntil
                && now - lastSwipeAt >= t(Timing.SWIPE_COOLDOWN) && now - lastUserScrollAt >= t(Timing.USER_SCROLL_GUARD)

        when (page) {
            PageKind.AD -> {
                // 广告倒计时未结束时先等,结束后再上滑
                val countdown = scan.countdownSeconds
                if (countdown != null) {
                    if (lastCountdownValue == null) {
                        LogBuf.add("[倒计时] 检测到 ${countdown}s,等待结束后再上滑")
                    }
                    if (countdown != lastCountdownValue) {
                        lastCountdownValue = countdown
                        countdownValueAt = now
                        countdownUntil = (now + countdown * 1000L + 1200L)
                            .coerceAtMost(now + 61_000L)
                    } else if (now - countdownValueAt > 8000L) {
                        // 文本一直不变,可能不是真倒计时,不再无限等待
                        countdownUntil = 0L
                    }
                } else {
                    if (lastCountdownValue != null) {
                        LogBuf.add("[倒计时] 结束,继续判定")
                    }
                    lastCountdownValue = null
                }
                if (now < countdownUntil) {
                    lastAdClearAt = 0L
                    if (now - lastStuckLogAt >= t(Timing.AD_STUCK_LOG)) {
                        lastStuckLogAt = now
                        LogBuf.add("检测到广告倒计时,等待结束再上滑(约 ${((countdownUntil - now) / 1000) + 1}s)")
                    }
                    return
                }
                // 回退刚完成时先静默一会儿,避免同一波广告立刻又触发上滑跳集
                if (now < postCorrectionUntil) {
                    if (now - lastStuckLogAt >= t(Timing.AD_STUCK_LOG)) {
                        lastStuckLogAt = now
                        LogBuf.add("回退后静默中,暂不自动上滑(剩余 ${(postCorrectionUntil - now) / 1000}s)")
                    }
                    return
                }
                // 上一次自动上滑把集数滑变了:锁定一段时间,绝不再补滑
                if (now < overshootLockUntil) {
                    if (now - lastStuckLogAt >= t(Timing.AD_STUCK_LOG)) {
                        lastStuckLogAt = now
                        LogBuf.add("误滑换集锁定中,暂停自动上滑(剩余 ${(overshootLockUntil - now) / 1000}s)")
                    }
                    return
                }
                lastAdClearAt = 0L // 判定成立,清零防抖计时
                // 只响应"新出现"的判定:上升沿在手势送达成功时消耗。
                if (!prevAdContext) {
                    val reason = when {
                        scan.adStrongVisible -> "命中广告关键词「${scan.adHit}」"
                        scan.adKeywordVisible -> "仅命中弱广告关键词「${scan.adHit}」"
                        else -> "未命中剧集/首页/广告关键词"
                    }
                    if (!canSwipeAd) {
                        if (now - lastCooldownLogAt >= 5000L) {
                            lastCooldownLogAt = now
                            LogBuf.add("$reason,但当前处于冷却/守卫期,暂不动作")
                        }
                    } else {
                        LogBuf.add("$reason($pkg): 上滑| 文字数=${scan.textCount}")
                        swipeUpOnce("$reason,自动上滑", lastKnownEpisode, hitKeyword = scan.adHit)
                    }
                } else if (now - lastStuckLogAt >= t(Timing.AD_STUCK_LOG)) {
                    lastStuckLogAt = now
                    LogBuf.add("广告页持续成立,等广告的观看时长限制(第 $swipeAttempts 次) 文字数=${scan.textCount}")
                }
            }

            PageKind.EPISODE, PageKind.HOME -> {
                // 剧集页/首页都不该滑;回到剧集页时解除误滑锁与倒计时
                if (page == PageKind.EPISODE) overshootLockUntil = 0L
                countdownUntil = 0L
                lastCountdownValue = null
                // 防抖动:判定需持续消失 ≥1 秒才算真结束
                if (lastAdClearAt == 0L) lastAdClearAt = now
                if (now - lastAdClearAt >= t(Timing.AD_DEBOUNCE)) {
                    prevAdContext = false
                    swipeAttempts = 0
                    missSamples = 0
                }
            }
        }
    }

    /**
     * 上滑一次并进入安静期。返回是否真的滑了 ——
     * 被冷却/安静期挡下时返回 false,调用方要负责续上重试链。
     *
     * @param episode 发起手势时所在的集数,用于事后判断是否把集数滑变了;
     *                手动触发传 null 表示不做换集判定。
     * @param manual 手动触发(通知栏/adb):不计入统计、不进入自动重试与回退链。
     */
    private fun swipeUpOnce(
        reason: String,
        episode: Int? = lastKnownEpisode,
        manual: Boolean = false,
        hitKeyword: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now < quietUntil) return false
        if (now - lastSwipeAt < t(Timing.SWIPE_COOLDOWN)) return false
        lastSwipeAt = now
        quietUntil = now + t(Timing.QUIET)
        if (manual) {
            swipeAttempts = 0
        } else {
            swipeAttempts++
        }
        val 方式 = GestureKit.skipAd(this)
        val 已送达 = 方式 != null
        if (已送达) {
            autoSwipeAt = now
            resumeScanAt = now + t(Timing.POST_SWIPE_PAUSE)
            if (manual) {
                // 手动操作不消耗自动判定的上升沿,也不参与换集校正
                missSamples = 0
                prevAdContext = false
                swipeEpisode = null
                swipeAt = 0L
            } else {
                missSamples = 0
                prevAdContext = true
                swipeEpisode = episode
                swipeAt = now
                episodeReadRetries = 0
                resultRetries = 0
                // 新一轮自动上滑,取消上一轮的 N+1 观察
                pendingNextTarget = null
                pendingNextCount = 0
                AppSettings.recordSwipe(this)
            }
        }
        val attempt = if (manual) "手动" else "第 $swipeAttempts 次"
        val start = episode?.let { "第${it}集" } ?: "集数未知"
        val quietSeconds = t(Timing.QUIET) / 1000.0
        LogBuf.add(
            if (已送达) "[上滑] $reason,$attempt,起点=$start → ${"%.1f".format(quietSeconds)} 秒后校验${if (manual) "(手动不计入统计)" else ""}"
            else "[上滑] 手势未能送达($reason),${"%.1f".format(quietSeconds)} 秒后重试"
        )
        AdHintOverlay.show(
            this,
            when {
                已送达 && manual -> "已上滑(手动)"
                已送达 && hitKeyword != null -> "命中「$hitKeyword」→ 上滑"
                已送达 -> "广告页·无关键词 → 上滑"
                else -> "手势未能送达"
            }
        )
        resultTask?.let { handler.removeCallbacks(it) }
        resultTask = Runnable { if (manual) checkManualSwipeResult() else checkSwipeResult() }
        handler.postDelayed(resultTask!!, t(Timing.QUIET))
        return true
    }

    /** 手动上滑只做一次结果确认:不计入统计、不重试、不回退。 */
    private fun checkManualSwipeResult() {
        if (destroyed) return
        resultTask = null
        val root = rootInActiveWindow
        if (root == null) {
            LogBuf.add("手动上滑:拿不到窗口,跳过结果确认")
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            swipeEpisode = null
            return
        }
        val scan = 扫描页面(root)
        val page = AdRule.classify(scan)
        val episode = AdDetector.episodeNumber(scan.episodeHeader)
        val episodeText = episode?.let { "第${it}集" } ?: "集数未识别"
        val pageText = when (page) {
            PageKind.EPISODE -> "剧集页"
            PageKind.HOME -> "首页"
            PageKind.AD -> "广告页"
        }
        LogBuf.add("手动上滑完成:已上滑到$episodeText,当前页面:$pageText(不计入统计)")
        AdHintOverlay.show(
            this,
            episode?.let { "已上滑到第${it}集(手动)" } ?: "已上滑(集数未识别,手动)"
        )
        swipeAttempts = 0
        missSamples = 0
        prevAdContext = false
        swipeEpisode = null
    }

    /**
     * 上滑后校验:「选集」回来了 = 跳掉了,链断。
     * 还是不可见 = 屏幕没变,按重试链处理。
     */
    private fun checkSwipeResult() {
        if (destroyed) return
        resultTask = null
        // 已经到 N+1,先进入观察期,确认不会继续滑到 N+2
        if (pendingNextTarget != null) {
            confirmPendingNext()
            return
        }
        // 开关被关掉就立刻掐断整条重试链,并清掉计数
        if (!可以动手()) {
            if (swipeAttempts > 0) LogBuf.add("开关已关闭,停止重试(本轮已滑 $swipeAttempts 次)")
            correctionTask?.let { handler.removeCallbacks(it) }
            correctionTarget = null
            correctionAttempts = 0
            correctionRetries = 0
            postCorrectionUntil = 0L
            countdownUntil = 0L
            lastCountdownValue = null
            pendingNextTarget = null
            pendingNextCount = 0
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            if (resultRetries < 5) {
                resultRetries++
                handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
            } else {
                LogBuf.add("连续拿不到窗口,停止本轮校验")
                resultRetries = 0
                swipeAttempts = 0
                missSamples = 0
                prevAdContext = false
            }
            return
        }
        resultRetries = 0
        val scan = 扫描页面(root)
        val page = AdRule.classify(scan)
        val currentEpisode = AdDetector.episodeNumber(scan.episodeHeader)
        if (currentEpisode != null) {
            lastKnownEpisode = currentEpisode
            episodeReadRetries = 0
        } else if (episodeReadRetries < 2) {
            // 换集动画期间顶部标题可能还没建出来,等 1 秒再读,避免误报"集数未识别"
            episodeReadRetries++
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
            return
        }
        // 校验时把实际落点告诉用户(用户手动翻过页时不冒充自动上滑的结果)
        val elapsed = if (swipeAt > 0) System.currentTimeMillis() - swipeAt else 0L
        LogBuf.add(
            "[校验] ${elapsed}ms 后: 页面=${页面标签(scan)} " +
                "集数=${currentEpisode?.let { "第${it}集" } ?: "未知"}" +
                if (lastUserScrollAt > swipeAt) " (用户已翻页)" else ""
        )
        if (lastUserScrollAt <= swipeAt) {
            AdHintOverlay.show(
                this,
                currentEpisode?.let { "已上滑到第${it}集" } ?: "已上滑(集数未识别)"
            )
        }

        // 误滑换集检测:手势前记下的集数,手势后变了 → 这一滑跨集了。
        // 期间有用户手动翻页时不归因于自动上滑。
        val fromEpisode = swipeEpisode
        if (fromEpisode != null && lastUserScrollAt <= swipeAt) {
            when (val outcome = EpisodeCorrection.decide(
                    fromEpisode,
                    currentEpisode,
                    AppSettings.autoCorrectEpisode(this)
                )
            ) {
                EpisodeOutcome.Same -> {
                    // 没换集,继续走正常的关键词可见校验
                }

                EpisodeOutcome.Next -> {
                    // 3 秒校验只看到 N+1 还不能收工:视频流的惯性/自动连播可能继续走到 N+2。
                    // 进入确认期,再观察最多约 6 秒。
                    pendingNextTarget = currentEpisode
                    pendingNextCount = 1
                    LogBuf.add("集数从第 $fromEpisode 集变为第 $currentEpisode 集,进入下一集确认期")
                    resultTask = Runnable { checkSwipeResult() }
                    handler.postDelayed(resultTask!!, 1800L)
                    return
                }

                is EpisodeOutcome.CorrectTo -> {
                    LogBuf.add("集数从第 $fromEpisode 集跳到第 $currentEpisode 集,超过预期第 ${outcome.target} 集,开始下滑回退")
                    overshootLockUntil = System.currentTimeMillis() + 20_000L
                    startCorrection(outcome.target)
                    return
                }

                EpisodeOutcome.Misfire -> {
                    LogBuf.add("检测到集数从第 $fromEpisode 集变为第 $currentEpisode 集,判定为误滑,停止本轮重试")
                    AdHintOverlay.show(this, "误滑到第${currentEpisode}集,已停止")
                    overshootLockUntil = System.currentTimeMillis() + 20_000L
                    swipeAttempts = 0
                    missSamples = 0
                    prevAdContext = false
                    swipeEpisode = null
                    return
                }
            }
        }

        if (page == PageKind.EPISODE) {
            if (lastUserScrollAt > swipeAt) {
                // 自动上滑后用户自己翻了页,这一轮不能算作"自动跳过成功"
                LogBuf.add("已回到剧集页,但期间有用户手动翻页,本次不计入跳过统计")
            } else {
                AppSettings.recordSkip(this)
                val episodeText = currentEpisode?.let { "第${it}集" } ?: "集数未识别"
                LogBuf.add("已确认回到剧集页(命中「${scan.episodeHit}」),已上滑到$episodeText,跳过成功 ✓(本轮共滑 $swipeAttempts 次,${AppSettings.statsText(this)})")
            }
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            swipeEpisode = null
            return
        }
        if (page == PageKind.HOME) {
            LogBuf.add("已到首页(命中「${scan.homeHit}」),停止本轮重试")
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            swipeEpisode = null
            return
        }
        // 仍是广告页,继续走复核/重试链
        val requiredMiss = t(Timing.REQUIRED_MISS_SAMPLES).toInt()
        if (missSamples < requiredMiss) {
            missSamples++
            handler.postDelayed(Runnable { checkSwipeResult() }, AppSettings.scanIntervalMs(this))
            return
        }
        missSamples = 0
        val maxAttempts = t(Timing.MAX_SWIPE_ATTEMPTS).toInt()
        if (swipeAttempts >= maxAttempts) {
            LogBuf.add("已连滑 $maxAttempts 次仍停留在广告页,停止重试(可能是不给跳的插屏广告)")
            return
        }
        LogBuf.add("上滑后仍是广告页(已复核 $requiredMiss 次)→ 第 $swipeAttempts 次重试")
        if (!swipeUpOnce("第 $swipeAttempts 次重试", swipeEpisode ?: lastKnownEpisode, hitKeyword = scan.adHit)) {
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
        }
    }

    /**
     * N+1 确认期:视频流可能因为惯性/自动连播继续走到 N+2。
     * 观察最多约 6 秒;一旦超过目标就立即回退,否则确认停住。
     */
    private fun confirmPendingNext() {
        if (destroyed) return
        val target = pendingNextTarget ?: return
        if (!可以动手()) {
            pendingNextTarget = null
            pendingNextCount = 0
            return
        }
        if (lastUserScrollAt > swipeAt) {
            LogBuf.add("确认期内检测到用户手动翻页,结束观察")
            pendingNextTarget = null
            pendingNextCount = 0
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            swipeEpisode = null
            return
        }
        val root = rootInActiveWindow
        val current = root?.let {
            AdDetector.episodeNumber(扫描页面(it).episodeHeader)
        }
        if (current != null && current > target) {
            LogBuf.add("确认期内集数变成第 $current 集,超过第 $target 集,开始回退")
            AdHintOverlay.show(this, "检测到第${current}集,回退到第${target}集")
            overshootLockUntil = System.currentTimeMillis() + 20_000L
            pendingNextTarget = null
            pendingNextCount = 0
            startCorrection(target)
            return
        }
        if ((current == null || current == target) && pendingNextCount < 3) {
            pendingNextCount++
            resultTask = Runnable { checkSwipeResult() }
            handler.postDelayed(resultTask!!, 1500L)
            return
        }
        finishPendingNext(current ?: target)
    }

    private fun finishPendingNext(episode: Int) {
        LogBuf.add("已确认停留在第 $episode 集")
        AdHintOverlay.show(this, "已上滑到第${episode}集")
        pendingNextTarget = null
        pendingNextCount = 0
        swipeAttempts = 0
        missSamples = 0
        prevAdContext = false
        swipeEpisode = null
        overshootLockUntil = 0L
    }

    /**
     * 误滑换集回退:当前集数已经超过目标(例如 11 → 13,目标 12),
     * 连续下滑,每次等待 [CORRECTION_PAUSE_MS] 再检查,最多 [MAX_CORRECTIONS] 次。
     * 用户一旦手动翻页立即中止;回退过头也中止,避免来回震荡。
     */
    private fun startCorrection(target: Int) {
        correctionTarget = target
        correctionStartedAt = System.currentTimeMillis()
        correctionAttempts = 0
        correctionRetries = 0
        performCorrectionSwipe()
    }

    private fun performCorrectionSwipe() {
        if (destroyed) return
        if (!可以动手()) {
            finishCorrection("开关已关闭或不在目标应用,停止回退")
            return
        }
        val target = correctionTarget ?: return
        val now = System.currentTimeMillis()
        if (correctionAttempts >= MAX_CORRECTIONS) {
            finishCorrection("已回退 $MAX_CORRECTIONS 次仍未到第 $target 集,停止")
            return
        }
        if (lastUserScrollAt > correctionStartedAt) {
            finishCorrection("检测到用户手动翻页,停止回退")
            return
        }
        correctionAttempts++
        autoSwipeAt = now // 让我们自己下滑引发的滚动不算用户翻页
        quietUntil = now + t(Timing.QUIET)
        resumeScanAt = now + CORRECTION_PAUSE_MS
        val done = GestureKit.swipeDown(this)
        LogBuf.add(
            if (done) "回退第 $correctionAttempts 次:目标第 $target 集"
            else "回退手势未能送达(第 $correctionAttempts 次)"
        )
        AdHintOverlay.show(this, "回退到第${target}集…")
        correctionTask?.let { handler.removeCallbacks(it) }
        correctionTask = Runnable { checkCorrection() }
        handler.postDelayed(correctionTask!!, CORRECTION_PAUSE_MS)
    }

    private fun checkCorrection() {
        if (destroyed) return
        correctionTask = null
        if (!可以动手()) {
            finishCorrection("开关已关闭或不在目标应用,停止回退")
            return
        }
        val target = correctionTarget ?: return
        if (lastUserScrollAt > correctionStartedAt) {
            finishCorrection("检测到用户手动翻页,停止回退")
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            retryCorrectionOrFinish(target)
            return
        }
        val scan = 扫描页面(root)
        val current = AdDetector.episodeNumber(scan.episodeHeader)
        if (current == null) {
            retryCorrectionOrFinish(target)
            return
        }
        when {
            current == target -> {
                LogBuf.add("已回退到第 $target 集,结束")
                AdHintOverlay.show(this, "已回到第${target}集")
                lastKnownEpisode = target
                correctionTarget = null
                correctionAttempts = 0
                correctionRetries = 0
                swipeEpisode = null
                swipeAttempts = 0
                missSamples = 0
                prevAdContext = false
                overshootLockUntil = 0L
                postCorrectionUntil = System.currentTimeMillis() + 10_000L
                resumeScanAt = System.currentTimeMillis() + 2000L
            }

            current < target -> finishCorrection("回退过头(当前第 $current 集),停止")

            else -> {
                if (correctionAttempts < MAX_CORRECTIONS) {
                    performCorrectionSwipe()
                } else {
                    finishCorrection("已回退 $MAX_CORRECTIONS 次,当前第 $current 集,停止")
                }
            }
        }
    }

    private fun retryCorrectionOrFinish(target: Int) {
        if (destroyed) return
        if (correctionRetries < 5) {
            correctionRetries++
            correctionTask?.let { handler.removeCallbacks(it) }
            correctionTask = Runnable { checkCorrection() }
            handler.postDelayed(correctionTask!!, 1000L)
        } else {
            finishCorrection("看不到集数,已回退 $correctionAttempts 次,停止")
        }
    }

    private fun finishCorrection(reason: String) {
        LogBuf.add(reason)
        correctionTask?.let { handler.removeCallbacks(it) }
        correctionTask = null
        correctionTarget = null
        correctionAttempts = 0
        correctionRetries = 0
        swipeEpisode = null
        swipeAttempts = 0
        missSamples = 0
        prevAdContext = false
        // 回退失败时保持锁定,避免马上又自动上滑再次跳集
        overshootLockUntil = System.currentTimeMillis() + 20_000L
        resumeScanAt = System.currentTimeMillis() + 2000L
    }

    override fun onInterrupt() {
        // 系统打断,无需处理
    }

    /**
     * 手动触发一次上滑,用于验证手势链路是否通。
     * `adb shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW`
     */
    private val 手动上滑 = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (destroyed) return
            LogBuf.add("收到手动上滑指令")
            // 手动操作优先:取消正在进行的回退
            correctionTask?.let { handler.removeCallbacks(it) }
            correctionTarget = null
            correctionAttempts = 0
            correctionRetries = 0
            postCorrectionUntil = 0L
            countdownUntil = 0L
            lastCountdownValue = null
            pendingNextTarget = null
            pendingNextCount = 0
            lastSwipeAt = 0L
            quietUntil = 0L
            resumeScanAt = 0L
            swipeAttempts = 0
            missSamples = 0
            swipeUpOnce("手动触发", null, manual = true)
        }
    }

    override fun onDestroy() {
        destroyed = true
        scanning = false
        pendingNextTarget = null
        pendingNextCount = 0
        handler.removeCallbacks(扫描任务)
        resultTask?.let { handler.removeCallbacks(it) }
        correctionTask?.let { handler.removeCallbacks(it) }
        // 必须注销,否则服务重连后会重复注册,手动/通知栏指令会执行多次
        if (receiverRegistered) {
            runCatching { unregisterReceiver(手动上滑) }
            receiverRegistered = false
        }
        ToggleBall.hide(this)
        AdHintOverlay.hide(this)
        GuardService.stop(this)
        LogBuf.add("无障碍服务已断开")
        broadcastConnected()
        super.onDestroy()
    }

    /** 通知主界面刷新无障碍状态。状态本身由 MainActivity 自己查,广播只当"该刷新了"的信号。 */
    private fun broadcastConnected() {
        try {
            sendBroadcast(Intent(MainActivity.ACTION_ACC_STATE).setPackage(packageName))
        } catch (_: Exception) { /* 广播未注册时忽略 */ }
    }
}
