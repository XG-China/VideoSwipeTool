package com.autoskip.ad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 红果免费短剧广告自动跳过无障碍服务(免 root):
 *  1. 只处理红果免费短剧(com.phoenix.read);
 *     config 的 packageNames 已把系统事件源限定在这个包上,其他 App 完全不碰
 *     (注意 com.dragon.read 是番茄免费小说,不是红果,别混进来)
 *  2. 判定规则只此一条:屏幕上找不到「选集」二字 → 上滑
 *  3. 用户手动翻页后 3 秒内一律不自动滑(不抢操作)
 *  4. 只响应"新出现"的判定(上升沿);同一状态不重复滑
 *  5. 滑完后安静 3 秒,防止误滑多次
 *  6. 判定需持续消失 ≥1 秒才算结束(防抖),再出现才视为新一轮
 *  7. 广告有观看时长限制时持续重试,重试前连续三次复核
 *  8. 悬浮球点击打开管理面板,开关和目标应用都在面板里改
 * 不做状态机、不自动点按钮、不猜倒计时文案——用户指哪打哪。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val SCAN_THROTTLE_MS = 1000L       // 检测节奏:固定每秒扫一次
        private const val POST_SWIPE_PAUSE_MS = 3000L    // 手势送达后暂停操作 3 秒,再恢复每秒检测
        private const val READY_BUFFER_MS = 4000L        // 服务连接后的缓冲期(期间不自动滑)
        private const val QUIET_MS = 3000L               // 上滑后的安静期
        private const val SWIPE_COOLDOWN_MS = 3000L      // 两次自动上滑最小间隔
        private const val USER_SCROLL_GUARD_MS = 3000L   // 用户手动翻页后的守卫时间
        private const val AD_DEBOUNCE_MS = 1000L         // 判定防抖:持续消失≥该时长才算结束
        private const val AUTO_SWIPE_WINDOW_MS = 1500L   // 自动上滑引发的滚动时间窗(不算用户操作)
        private const val BALL_RETRY_MS = 15000L         // 悬浮球自愈重试间隔
        private const val BALL_MISSING_LOG_MS = 30000L   // "悬浮球挂不上"的警告节流
        private const val AD_STUCK_LOG_MS = 30000L       // 判定持续成立但已滑过时的日志节流(30 秒一条,只用于发现异常)
        private const val MAX_SWIPE_ATTEMPTS = 10         // 观看时长限制的连滑上限(每轮 = 3s 安静期 + 3×1s 复核 = 6s,10 轮 ≈ 60 秒)
        private const val REQUIRED_MISS_SAMPLES = 3       // 重试前要连续几次确认"找不到选集"(过滤广告刚放完、树还没重建的中间态)

        /** 手动上滑指令:adb shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW */
        const val ACTION_SWIPE_NOW = "com.autoskip.ad.action.SWIPE_NOW"
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var readyAt = 0L          // 服务刚连接后的缓冲期结束时间点(期间不自动滑)
    private var lastSwipeAt = 0L
    private var quietUntil = 0L       // 上滑后的安静期:期间不自动滑,防止错误上滑多次
    private var prevAdContext = false // 上一帧判定是否成立(用于"只滑新出现的那一次"上升沿判断)
    private var lastScanAt = 0L
    private var lastBallRetryAt = 0L
    private var lastBallMissingLogAt = 0L  // 上次"悬浮球挂不上"警告(30 秒节流)
    private var lastAdClearAt = 0L    // 判定开始持续消失的时刻(防抖:一闪而过的消失不算新一轮)
    private var autoSwipeAt = 0L       // 我们自己发起手势的时间(区分"用户翻页"和"自动上滑")
    private var lastUserScrollAt = 0L  // 用户最近一次手动翻页的时间
    private var lastUserScrollLogAt = 0L
    private var lastStuckLogAt = 0L    // 上次记录"提示持续残留"的时间(30 秒节流,只用于发现异常)
    private var swipeAttempts = 0     // 本轮连续上滑次数(用于"等广告观看时长限制"的重试)
    private var missSamples = 0       // 连续多少次校验都"找不到选集"(重试前的二次确认)
    private var resumeScanAt = 0L      // 上滑成功后的操作暂停截止时刻(暂停 3 秒再恢复每秒检测)
    private var resultTask: Runnable? = null

    // 定时主动扫描:红果广告可能是静态 SurfaceView(不产生无障碍事件),
    // 只靠事件驱动会漏扫 → 必须用定时器每秒轮询一次。
    private val 扫描任务 = object : Runnable {
        override fun run() {
            runCatching { 自愈悬浮球(System.currentTimeMillis()) }
            runCatching { 执行扫描() }
            handler.postDelayed(this, SCAN_THROTTLE_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.migrateConfig(this) // 只保留红果免费短剧,清掉历史保存的其他 App(含番茄免费小说)
        // 启动保活前台服务,防止 MIUI 冻结进程导致断连
        GuardService.start(this)
        // 注册手动上滑指令(验证手势链路用)
        runCatching {
            val f = android.content.IntentFilter(ACTION_SWIPE_NOW)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(手动上滑, f, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(手动上滑, f)
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
        readyAt = System.currentTimeMillis() + READY_BUFFER_MS
        // 启动定时扫描(静态广告无事件也能被发现)
        handler.postDelayed(扫描任务, SCAN_THROTTLE_MS)
        LogBuf.add("无障碍服务已连接,自动广告跳过已就绪")
        broadcastConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg.startsWith("com.autoskip")) return // 不对自己 App 做动作
        val now = System.currentTimeMillis()
        // 悬浮球自愈:初次连接若悬浮窗权限未授,补授权后自动重挂
        自愈悬浮球(now)
        if (!可以动手()) return

        // 区分"用户手动翻页"与"我们的自动上滑":
        // 自动上滑引发的滚动事件会在时间窗内到达,不算用户操作;其余滚动都视为用户翻页
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
     *
     * 定时器和无障碍事件都会调 —— 因为红果播放时可能长时间没有任何无障碍事件,
     * 只靠事件驱动就永远等不到"用户授权后自动重挂"。
     * 权限没授时不重复刷警告(30 秒一条),避免刷屏。
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

    /**
     * 现在允许对屏幕动手吗:总开关开着,且当前前台窗口属于已启用的目标应用。
     *
     * 扫描入口和重试链都必须过这一关。只在 [执行扫描] 里判是不够的 ——
     * 重试链是独立的定时任务,关掉开关后它照样会滑满 10 次。
     */
    private fun 可以动手(): Boolean {
        if (!AppSettings.masterEnabled(this)) return false
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        // 拿不到包名时不在这里拦:交给调用方原有的 root==null 分支处理,
        // 免得因为一次取不到窗口就把用户正在看的内容误判成"不允许"。
        if (pkg.isEmpty()) return true
        return pkg in AppSettings.targetPackages(this)
    }

    /**
     * 执行一次扫描(定时器每秒 + 无障碍事件都会触发)。
     * 判定规则只此一条:屏幕上找不到「选集」二字 → 上滑。
     */
    private fun 执行扫描() {
        if (!可以动手()) return
        val now = System.currentTimeMillis()
        // 手势送达后暂停扫描 3 秒(给跳过/翻页留时间),再恢复每秒一次
        if (now < resumeScanAt) return
        // 扫描节奏固定每秒 1 次,不因广告降速
        if (now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()

        val scan = AdDetector.scan(root)
        // 判定规则(只此一条):屏幕上找不到「选集」二字 → 上滑
        val adVisible = scan.无选集
        val canSwipeAd = now >= readyAt && now >= quietUntil
                && now - lastSwipeAt >= SWIPE_COOLDOWN_MS && now - lastUserScrollAt >= USER_SCROLL_GUARD_MS
        if (adVisible) {
            lastAdClearAt = 0L // 判定成立,清零防抖计时
            // 只响应"新出现"的判定:上升沿在手势下达成功时消耗(prevAdContext=true)。
            // 若此刻被重连缓冲/安静期/翻页守卫挡住,则不消耗上升沿,等条件满足后继续尝试
            if (!prevAdContext) {
                if (!canSwipeAd) {
                    // 还不能滑(重连缓冲/安静期/翻页守卫),不弹"将自动上滑"免得言而无信,
                    // 也不消耗上升沿,条件满足后这一帧自然会滑
                    LogBuf.add("找不到「选集」,但当前处于冷却/守卫期,暂不动作")
                } else {
                    // 提示条由 swipeUpOnce() 统一弹「已上滑 ✓」,这里不再预弹一次:
                    // 两次调用只隔几毫秒,前一条根本来不及被看见。
                    LogBuf.add("找不到「选集」($pkg): 上滑| 文字数=${scan.textCount}")
                    swipeUpOnce("找不到「选集」,自动上滑")
                }
            } else if (now - lastStuckLogAt >= AD_STUCK_LOG_MS) {
                // 上升沿已消耗但判定还成立:30 秒记一条。
                // 重试由 checkSwipeResult() 负责(每轮 3 秒安静期 + 3×1 秒复核 = 6 秒一次),
                // 这里只负责提示"还在等",不重复滑,避免和重试链叠加。
                lastStuckLogAt = now
                LogBuf.add("「选集」持续找不到,等广告的观看时长限制(第 $swipeAttempts 次) 文字数=${scan.textCount}")
            }
        } else {
            // 防抖动:判定需持续消失 ≥1 秒才算真结束
            if (lastAdClearAt == 0L) lastAdClearAt = now
            if (now - lastAdClearAt >= AD_DEBOUNCE_MS) {
                prevAdContext = false
                swipeAttempts = 0 // 这一波过去了,下一波重新从 0 次算起
                missSamples = 0
            }
        }
    }

    /**
     * 上滑一次并进入安静期。返回是否真的滑了 ——
     * 被冷却/安静期挡下时返回 false,调用方要负责续上重试链,否则链会静默断掉。
     */
    private fun swipeUpOnce(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < quietUntil) return false
        if (now - lastSwipeAt < SWIPE_COOLDOWN_MS) return false
        lastSwipeAt = now
        quietUntil = now + QUIET_MS
        swipeAttempts++
        // 手势是绝对的:送达即执行,不存在"滑了但没滑到"的情况。
        // 所以"送达"和"广告被跳掉"是两件事 —— 送达只用来决定要不要继续重试,
        // 跳没跳掉一律由 checkSwipeResult() 事后校验。
        val 方式 = GestureKit.skipAd(this)
        val 已送达 = 方式 != null
        if (已送达) {
            autoSwipeAt = now
            missSamples = 0 // 新一轮确认,重试前的两次复核从头数
            // 上升沿在手势送达时就消耗,不是校验成功。
            // 所以"广告有观看时长限制、第一次滑了没反应"由 checkSwipeResult() 兜底重试,
            // 扫描循环这边看到 prevAdContext=true 不会再滑,避免两条路径叠着滑。
            prevAdContext = true
            resumeScanAt = now + POST_SWIPE_PAUSE_MS // 送达 → 暂停操作 3 秒再恢复每秒检测
        }
        LogBuf.add(
            if (已送达) "已上滑($reason,第 $swipeAttempts 次) → 3 秒后校验是否跳掉"
            else "手势未能送达($reason),3 秒后重试"
        )
        AdHintOverlay.show(this, if (已送达) "已上滑 ✓" else "手势未能送达")
        // 安静期结束后校验结果:没跳过就再来一次(见 checkSwipeResult)
        resultTask?.let { handler.removeCallbacks(it) }
        resultTask = Runnable { checkSwipeResult() }
        handler.postDelayed(resultTask!!, QUIET_MS)
        return true
    }

    /**
     * 上滑后校验:「选集」回来了 = 跳掉了,链断。
     *
     * 还是找不到 = 屏幕没变。手势这一环是绝对的(送达即执行),所以**不可能是滑歪了**,
     * 只剩两种可能,处理方式恰好一样 —— 都不是注入能解决的,只能等/放弃:
     *   ① 广告有观看时长限制,得先看几秒才让滑
     *   ② 广告根本不响应滑动(实测过的满屏插屏品牌广告就是这种)
     *
     * 与其去猜倒计时文案或按钮置灰(红果的倒计时是否暴露在 UI 树里没验证过),
     * 不如把**重试间隔当成"等的时长"**:5 秒、15 秒、30 秒的限制都自动覆盖。
     * ② 会被上限兜住(白滑十次,手势对全屏广告无副作用)。
     *
     * **重试前必须连续 $REQUIRED_MISS_SAMPLES 次(每次间隔 1 秒)都确认找不到「选集」** ——
     * 广告自己播完消失的那一瞬间,树还没重建,单次采样仍是"找不到",
     * 这时补一次重试手势就打在剧集页上,帮你多翻一集(用户看到的"莫名其妙滑两次")。
     * 三次采样共跨 2 秒,足够滤掉这种中间态。
     */
    private fun checkSwipeResult() {
        resultTask = null
        // 开关被关掉就立刻掐断整条重试链。
        // 这条链是独立于扫描循环的定时任务,不受 执行扫描() 里那句 masterEnabled 把关,
        // 所以只在入口判的话,已经跑起来的链会无视开关继续滑满 MAX_SWIPE_ATTEMPTS(约 60 秒),
        // 表现就是「功能都关了还在上滑」。
        // 顺手把计数清零:否则开关再打开时残留的 prevAdContext=true 会吃掉上升沿,
        // 广告明明还在屏幕上却再也不滑了。
        if (!可以动手()) {
            if (swipeAttempts > 0) LogBuf.add("开关已关闭,停止重试(本轮已滑 $swipeAttempts 次)")
            swipeAttempts = 0
            missSamples = 0
            prevAdContext = false
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            // 拿不到树(窗口切走了),1 秒后再看一眼,别把链断掉
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
            return
        }
        val scan = AdDetector.scan(root)
        if (!scan.无选集) {
            LogBuf.add("已确认:「选集」回来了,上滑成功 ✓(本轮共滑 $swipeAttempts 次)")
            swipeAttempts = 0
            missSamples = 0
            return
        }
        if (missSamples < REQUIRED_MISS_SAMPLES) {
            // 还没攒够复核次数,再等一个扫描周期确认一次,防止在中间态上误补一刀。
            missSamples++
            handler.postDelayed(Runnable { checkSwipeResult() }, SCAN_THROTTLE_MS)
            return
        }
        missSamples = 0
        if (swipeAttempts >= MAX_SWIPE_ATTEMPTS) {
            LogBuf.add("已连滑 $MAX_SWIPE_ATTEMPTS 次仍找不到「选集」,停止重试(可能是不给跳的插屏广告)")
            return
        }
        LogBuf.add("上滑后仍找不到「选集」(已复核 $REQUIRED_MISS_SAMPLES 次)→ 第 $swipeAttempts 次重试(等广告的观看时长限制)")
        if (!swipeUpOnce("第 $swipeAttempts 次重试")) {
            // 被冷却/安静期挡下了(正常不该发生,QUIET_MS 刚好等于等待时间)。
            // 不续链的话重试就到此为止,所以 1 秒后再校验一次。
            handler.postDelayed(Runnable { checkSwipeResult() }, 1000L)
        }
    }

    override fun onInterrupt() {
        // 系统打断,无需处理
    }

    /**
     * 手动触发一次上滑,用于验证手势链路是否通。
     * `adb shell am broadcast -a com.autoskip.ad.action.SWIPE_NOW`
     *
     * 走的是和自动跳过完全相同的 [swipeUpOnce] 路径(含冷却/安静期判断),
     * 所以能真实反映"自动跳过能不能滑出去"。
     */
    private val 手动上滑 = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            LogBuf.add("收到手动上滑指令")
            // 清掉冷却/安静期,保证手动指令一定执行
            lastSwipeAt = 0L
            quietUntil = 0L
            resumeScanAt = 0L
            swipeAttempts = 0
            missSamples = 0
            swipeUpOnce("手动触发")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(扫描任务)
        resultTask?.let { handler.removeCallbacks(it) }
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
            sendBroadcast(Intent(MainActivity.ACTION_ACC_STATE))
        } catch (_: Exception) { /* 广播未注册时忽略 */ }
    }
}
