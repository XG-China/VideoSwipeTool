package com.autoskip.ad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 红果免费短剧广告自动跳过无障碍服务(免 root):
 *  1. 只处理目标应用(默认红果免费短剧 com.phoenix.read);
 *     config 的 packageNames 已把系统事件源限定在这个包上,其他 App 完全不碰
 *     (注意 com.dragon.read 是番茄免费小说,不是红果,别混进来)
 *  2. 判定规则:关键词(默认「选集」)在树里存在但不可见 → 上滑。
 *     严格模式下来不到关键词的页面(首页/搜索页)不再误滑
 *  3. 用户手动翻页后 3 秒内一律不自动滑(不抢操作)
 *  4. 只响应"新出现"的判定(上升沿);同一状态不重复滑
 *  5. 滑完后安静 3 秒,防止误滑多次
 *  6. 判定需持续消失 ≥1 秒才算结束(防抖),再出现才视为新一轮
 *  7. 广告有观看时长限制时持续重试,重试前连续三次复核
 *  8. 悬浮球点击打开管理面板,长按快速开关总开关
 * 不做状态机、不自动点按钮、不猜倒计时文案——用户指哪打哪。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val POST_SWIPE_PAUSE_MS = 3000L    // 手势送达后暂停操作 3 秒,再恢复扫描
        private const val READY_BUFFER_MS = 4000L        // 服务连接后的缓冲期(期间不自动滑)
        private const val QUIET_MS = 3000L               // 上滑后的安静期
        private const val SWIPE_COOLDOWN_MS = 3000L      // 两次自动上滑最小间隔
        private const val USER_SCROLL_GUARD_MS = 3000L   // 用户手动翻页后的守卫时间
        private const val AD_DEBOUNCE_MS = 1000L         // 判定防抖:持续消失≥该时长才算结束
        private const val AUTO_SWIPE_WINDOW_MS = 1500L   // 自动上滑引发的滚动时间窗(不算用户操作)
        private const val BALL_RETRY_MS = 15000L         // 悬浮球自愈重试间隔
        private const val BALL_MISSING_LOG_MS = 30000L   // "悬浮球挂不上"的警告节流
        private const val AD_STUCK_LOG_MS = 30000L       // 判定持续成立但已滑过时的日志节流
        private const val MAX_SWIPE_ATTEMPTS = 10         // 观看时长限制的连滑上限
        private const val REQUIRED_MISS_SAMPLES = 3       // 重试前要连续几次确认"关键词不可见"

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
    private var lastStuckLogAt = 0L   // "提示持续残留"的日志节流
    private var swipeAttempts = 0     // 本轮连续上滑次数
    private var missSamples = 0       // 连续多少次校验都"看不到关键词"
    private var resumeScanAt = 0L     // 上滑后的操作暂停截止时刻
    private var resultTask: Runnable? = null
    private var scanning = false      // 防止重复启动扫描循环
    private var lastForegroundPkg = ""  // 事件里记录的前台包名,拿不到窗口时的兜底
    private var lastUnknownLogAt = 0L
    private var lastKnownEpisode: Int? = null   // 最近一次看到的「第X集」
    private var swipeEpisode: Int? = null       // 发起手势时所在的集数(防误滑换集的基准)
    private var swipeAt = 0L                    // 发起手势的时刻
    private var overshootLockUntil = 0L          // 误滑换集后的锁定截止时刻,期间不再自动滑
    private var ruleCacheText: String? = null    // 高级规则文本缓存
    private var ruleCache: CompiledRule? = null
    private var lastRuleErrorAt = 0L

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
        AppSettings.migrateConfig(this) // 只保留红果免费短剧,清掉历史保存的其他 App
        // 启动保活前台服务,防止 MIUI 冻结进程导致断连
        GuardService.start(this)
        // 注册手动上滑指令(验证手势链路 + 通知栏按钮用)
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
        readyAt = System.currentTimeMillis() + READY_BUFFER_MS
        // 启动定时扫描(静态广告无事件也能被发现)
        if (!scanning) {
            scanning = true
            handler.postDelayed(扫描任务, AppSettings.scanIntervalMs(this))
        }
        LogBuf.add("无障碍服务已连接,自动广告跳过已就绪(${AppSettings.statsText(this)})")
        broadcastConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg.startsWith("com.autoskip")) return // 不对自己 App 做动作
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
            if (autoSwipeAt == 0L || now - autoSwipeAt > AUTO_SWIPE_WINDOW_MS) {
                lastUserScrollAt = now
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
            if (now - lastBallMissingLogAt > BALL_MISSING_LOG_MS) {
                lastBallMissingLogAt = now
                LogBuf.add("悬浮球挂不上:未授予「显示在其他应用上层」权限,授权后会自动出现")
            }
            return
        }
        if (now - lastBallRetryAt < BALL_RETRY_MS) return
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

    /**
     * 执行一次扫描(定时器 + 无障碍事件都会触发)。
     * 判定规则见 [AdRule]:严格模式下只有"关键词在树里但不可见"才动手。
     */
    private fun 执行扫描() {
        if (!可以动手()) return
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

        val keywords = AppSettings.keywords(this)
        val label = keywords.joinToString("/")
        val scan = AdDetector.scan(root, keywords)
        // 记录最近一次看到的集数,作为"上滑是否换集"的基准
        AdDetector.episodeNumber(scan.episodeHeader)?.let { lastKnownEpisode = it }
        val strict = AppSettings.strictMode(this)
        val advanced = AppSettings.advancedRulesEnabled(this)
        val builtinState = AdRule.classify(scan, strict)
        val decision = 判定动作(scan, strict, advanced)

        val canSwipeAd = now >= readyAt && now >= quietUntil
                && now - lastSwipeAt >= SWIPE_COOLDOWN_MS && now - lastUserScrollAt >= USER_SCROLL_GUARD_MS

        when (decision) {
            RuleDecision.SWIPE -> {
                // 上一次自动上滑把集数滑变了:锁定一段时间,绝不再补滑,
                // 否则会一路从第2集滑到第3、4集
                if (now < overshootLockUntil) {
                    if (now - lastStuckLogAt >= AD_STUCK_LOG_MS) {
                        lastStuckLogAt = now
                        LogBuf.add("误滑换集锁定中,暂停自动上滑(剩余 ${(overshootLockUntil - now) / 1000}s)")
                    }
                    return
                }
                lastAdClearAt = 0L // 判定成立,清零防抖计时
                // 只响应"新出现"的判定:上升沿在手势送达成功时消耗。
                if (!prevAdContext) {
                    // 关键词不在树里、但顶部有「第X集」时,说明广告把整棵树替换了
                    val reason = when {
                        scan.keywordPresent -> "找不到可见的「$label」"
                        scan.episodeHeader != null -> "顶部有「${scan.episodeHeader}」但找不到可见的「$label」"
                        advanced -> "高级规则判定上滑"
                        else -> "找不到可见的「$label」"
                    }
                    if (!canSwipeAd) {
                        LogBuf.add("$reason,但当前处于冷却/守卫期,暂不动作")
                    } else {
                        LogBuf.add("$reason($pkg): 上滑| 文字数=${scan.textCount}")
                        swipeUpOnce("$reason,自动上滑", lastKnownEpisode)
                    }
                } else if (now - lastStuckLogAt >= AD_STUCK_LOG_MS) {
                    lastStuckLogAt = now
                    LogBuf.add("「$label」持续不可见,等广告的观看时长限制(第 $swipeAttempts 次) 文字数=${scan.textCount}")
                }
            }

            RuleDecision.IGNORE -> {
                // 「选集」重新可见,说明已经稳定在新页面,解除误滑锁
                if (scan.keywordVisible) overshootLockUntil = 0L
                // 防抖动:判定需持续消失 ≥1 秒才算真结束
                if (lastAdClearAt == 0L) lastAdClearAt = now
                if (now - lastAdClearAt >= AD_DEBOUNCE_MS) {
                    prevAdContext = false
                    swipeAttempts = 0
                    missSamples = 0
                }
                // 内置严格模式频繁回到首页时,30 秒提示一次"为何不动手",便于排查
                if (!advanced && builtinState == PageState.UNKNOWN && now - lastUnknownLogAt >= 30_000L) {
                    lastUnknownLogAt = now
                    LogBuf.add("当前页面没有「$label」节点,严格模式下不上滑(可在主界面关闭严格模式)")
                }
            }
        }
    }

    /**
     * 当前扫描该不该上滑:优先使用用户写的高级规则,否则用内置的严格/宽松判定。
     * 高级规则解析失败时回退内置判定,并做 30 秒日志节流。
     */
    private fun 判定动作(scan: AdScan, strict: Boolean, advanced: Boolean): RuleDecision {
        fun builtin(): RuleDecision =
            if (AdRule.classify(scan, strict) == PageState.AD) RuleDecision.SWIPE else RuleDecision.IGNORE

        if (!advanced) return builtin()

        val text = AppSettings.advancedRules(this)
        if (text != ruleCacheText) {
            ruleCacheText = text
            ruleCache = try {
                RuleEngine.compile(text)
            } catch (e: RuleParseException) {
                val now = System.currentTimeMillis()
                if (now - lastRuleErrorAt > 30_000L) {
                    lastRuleErrorAt = now
                    LogBuf.add("高级规则解析失败,已回退内置规则: ${e.message}")
                }
                null
            }
        }
        val rule = ruleCache ?: return builtin()
        return rule.decide(
            RuleContext(
                keywordVisible = scan.keywordVisible,
                keywordPresent = scan.keywordPresent,
                episode = AdDetector.episodeNumber(scan.episodeHeader),
                textCount = scan.textCount,
                strict = strict
            )
        )
    }

    /**
     * 上滑一次并进入安静期。返回是否真的滑了 ——
     * 被冷却/安静期挡下时返回 false,调用方要负责续上重试链。
     *
     * @param episode 发起手势时所在的集数,用于事后判断是否把集数滑变了;
     *                手动触发传 null 表示不做换集判定。
     */
    private fun swipeUpOnce(reason: String, episode: Int? = lastKnownEpisode): Boolean {
        val now = System.currentTimeMillis()
        if (now < quietUntil) return false
        if (now - lastSwipeAt < SWIPE_COOLDOWN_MS) return false
        lastSwipeAt = now
        quietUntil = now + QUIET_MS
        swipeAttempts++
        val 方式 = GestureKit.skipAd(this)
        val 已送达 = 方式 != null
        if (已送达) {
            autoSwipeAt = now
            missSamples = 0
            prevAdContext = true
            resumeScanAt = now + POST_SWIPE_PAUSE_MS
            swipeEpisode = episode
            swipeAt = now
            AppSettings.recordSwipe(this)
        }
        LogBuf.add(
            if (已送达) "已上滑($reason,第 $swipeAttempts 次) → 3 秒后校验是否跳掉"
            else "手势未能送达($reason),3 秒后重试"
        )
        AdHintOverlay.show(this, if (已送达) "已上滑 ✓" else "手势未能送达")
        resultTask?.let { handler.removeCallbacks(it) }
        resultTask = Runnable { checkSwipeResult() }
        handler.postDelayed(resultTask!!, QUIET_MS)
        return true
    }

    /**
     * 上滑后校验:「选集」回来了 = 跳掉了,链断。
     * 还是不可见 = 屏幕没变,按重试链处理。
     */
    private fun checkSwipeResult() {
        resultTask = null
        // 开关被关掉就立刻掐断整条重试链,并清掉计数
        if (!可以动手()) {
            if (swipeAttempts > 0) LogBuf.add("开关已关闭,停止重试(本轮已滑 $swipeAttempts 次)")
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
            return
        }
        val keywords = AppSettings.keywords(this)
        val label = keywords.joinToString("/")
        val scan = AdDetector.scan(root, keywords)
        val currentEpisode = AdDetector.episodeNumber(scan.episodeHeader)
        if (currentEpisode != null) lastKnownEpisode = currentEpisode

        // 误滑换集检测:手势前记下的集数,手势后变了 → 这一滑跨集了。
        // 期间有用户手动翻页时不归因于自动上滑。
        val fromEpisode = swipeEpisode
        if (fromEpisode != null && currentEpisode != null &&
            currentEpisode != fromEpisode && lastUserScrollAt <= swipeAt
        ) {
            LogBuf.add("检测到集数从第 ${fromEpisode} 集变成第 $currentEpisode 集,判定为误滑,停止本轮重试")
            AdHintOverlay.show(this, "误滑到第${currentEpisode}集,已停止")
            overshootLockUntil = System.currentTimeMillis() + 20_000L
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            swipeEpisode = null
            return
        }

        if (scan.keywordVisible) {
            AppSettings.recordSkip(this)
            LogBuf.add("已确认:「$label」回来了,上滑成功 ✓(本轮共滑 $swipeAttempts 次,${AppSettings.statsText(this)})")
            swipeAttempts = 0
            missSamples = 0
            return
        }
        // 当前规则已经不需要动作(离开播放页/自定义规则判定为不动)→ 终止本轮重试
        if (判定动作(
                scan,
                AppSettings.strictMode(this),
                AppSettings.advancedRulesEnabled(this)
            ) == RuleDecision.IGNORE
        ) {
            LogBuf.add("当前规则判定不动作,停止重试(本轮已滑 $swipeAttempts 次)")
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            return
        }
        if (missSamples < REQUIRED_MISS_SAMPLES) {
            missSamples++
            handler.postDelayed(Runnable { checkSwipeResult() }, AppSettings.scanIntervalMs(this))
            return
        }
        missSamples = 0
        if (swipeAttempts >= MAX_SWIPE_ATTEMPTS) {
            LogBuf.add("已连滑 $MAX_SWIPE_ATTEMPTS 次仍看不到「$label」,停止重试(可能是不给跳的插屏广告)")
            return
        }
        LogBuf.add("上滑后仍看不到「$label」(已复核 $REQUIRED_MISS_SAMPLES 次)→ 第 $swipeAttempts 次重试")
        if (!swipeUpOnce("第 $swipeAttempts 次重试", swipeEpisode ?: lastKnownEpisode)) {
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
        }
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
            LogBuf.add("收到手动上滑指令")
            lastSwipeAt = 0L
            quietUntil = 0L
            resumeScanAt = 0L
            swipeAttempts = 0
            missSamples = 0
            swipeUpOnce("手动触发", null)
        }
    }

    override fun onDestroy() {
        scanning = false
        handler.removeCallbacks(扫描任务)
        resultTask?.let { handler.removeCallbacks(it) }
        // 必须注销,否则服务重连后会重复注册,手动/通知栏指令会执行多次
        runCatching { unregisterReceiver(手动上滑) }
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
