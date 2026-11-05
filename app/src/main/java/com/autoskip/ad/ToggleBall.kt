package com.autoskip.ad

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 悬浮球:可拖动,单击 = 打开管理面板。
 * 球体本身不再直接开关:开关、目标应用全部在弹出的悬浮面板里改,避免误触。
 * 球内文字「开 / 关」表示总开关状态(仅作指示)。位置自动记忆。
 */
object ToggleBall {

    /** 球位置的 SharedPreferences 文件名(键是 "x" / "y")。 */
    private const val PREF_POS = "tball_pos"

    private const val PANEL_W_DP = 264      // 面板宽度,须与 ball_panel.xml 一致
    private const val PANEL_GAP_DP = 12     // 面板与球之间的间距

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var ballView: FrameLayout? = null
    private var textView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    @Volatile private var panelView: View? = null
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialRawX = 0f
    private var initialRawY = 0f
    private var dragThreshold = 0f
    private var isDragging = false
    private var touchDownAt = 0L

    val isShowing: Boolean get() = ballView != null
    private val isPanelShowing: Boolean get() = panelView != null

    fun show(ctx: Context) {
        if (isShowing) return
        // 没有悬浮窗权限就绝不挂窗:HyperOS 上 addView 照样成功、窗口也注册了,
        // 但系统不显示。此时 isShowing 会误报 true,服务的自愈重试就永远不再触发,
        // 球会一直隐形。宁可保持 ballView=null,让重试循环继续等授权。
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        // addView 必须在主线程执行(自愈重试可能来自无障碍事件线程)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(ctx) }
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (ctx.resources.displayMetrics.density * 44f).toInt()
        val root = LayoutInflater.from(ctx).inflate(R.layout.bubble_toggle, null) as FrameLayout
        root.layoutParams = FrameLayout.LayoutParams(size, size)
        textView = root.findViewById(R.id.toggle_text)
        updateState(ctx)

        val prefs = ctx.getSharedPreferences(PREF_POS, Context.MODE_PRIVATE)
        val bounds = screenBounds(ctx)
        // 位置可能来自旧分辨率/旧版本,夹回屏内,避免球挂在看不见的地方
        val maxX = (bounds.width() - size).coerceAtLeast(0)
        val maxY = (bounds.height() - size).coerceAtLeast(0)
        val x = prefs.getInt("x", bounds.width() - size * 2).coerceIn(0, maxX)
        val y = prefs.getInt("y", bounds.height() / 2).coerceIn(0, maxY)

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        dragThreshold = ctx.resources.displayMetrics.density * 16f

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialRawX = params.x.toFloat()
                    initialRawY = params.y.toFloat()
                    touchDownAt = android.os.SystemClock.uptimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold) {
                        if (!isDragging) hidePanel(ctx) // 开始拖动就把面板收掉,否则它留在旧位置
                        isDragging = true
                    }
                    if (isDragging) {
                        // 拖动过程中就夹在屏内,避免存下屏幕外坐标
                        params.x = (initialRawX + dx).toInt().coerceIn(0, maxX)
                        params.y = (initialRawY + dy).toInt().coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 短按 = 打开面板;长按 = 快速切换总开关
                        val longPress = android.os.SystemClock.uptimeMillis() - touchDownAt >=
                                ViewConfiguration.getLongPressTimeout()
                        if (longPress) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            toggleMaster(ctx)
                        } else {
                            togglePanel(ctx)
                        }
                    } else {
                        params.x = params.x.coerceIn(0, maxX)
                        params.y = params.y.coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(v, params) }
                        ctx.getSharedPreferences(PREF_POS, Context.MODE_PRIVATE).edit()
                            .putInt("x", params.x).putInt("y", params.y).apply()
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // 关键:addView 成功后才置 ballView,失败则保持 null,之后会自动重试
        try {
            wm.addView(root, params)
            ballView = root
            ballParams = params
            LogBuf.add("悬浮球已显示,点击可打开管理面板")
        } catch (e: Exception) {
            ballView = null
            ballParams = null
            textView = null
            LogBuf.add("悬浮球显示失败: ${e.message}")
        }
    }

    fun hide(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide(ctx) }
            return
        }
        hidePanel(ctx) // 球没了,面板也不能留着
        val v = ballView ?: return
        ballView = null
        textView = null
        ballParams = null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
    }

    /** 外部(通知栏按钮/主界面)改了总开关后,同步球上的「开/关」指示。 */
    fun refresh(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { updateState(ctx) }
            return
        }
        updateState(ctx)
    }

    /** 长按悬浮球:快速切换总开关。 */
    private fun toggleMaster(ctx: Context) {
        val on = !AppSettings.masterEnabled(ctx)
        AppSettings.setMasterEnabled(ctx, on)
        updateState(ctx)
        if (!on) AdHintOverlay.hide(ctx)
        LogBuf.add("悬浮球长按:自动跳过已${if (on) "开启" else "关闭"}")
        AdHintOverlay.show(ctx, if (on) "自动跳过已开启" else "自动跳过已关闭")
        GuardService.updateNotification(ctx)
    }

    // ---------- 管理面板 ----------

    /** 单击悬浮球:面板开着就收掉,没开就弹出。 */
    private fun togglePanel(ctx: Context) {
        if (isPanelShowing) hidePanel(ctx) else showPanel(ctx)
    }

    private fun showPanel(ctx: Context) {
        // addView 必须在主线程(无障碍事件线程是 Binder 线程),跳一下
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showPanel(ctx) }
            return
        }
        if (panelView != null) return

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        val inflater = LayoutInflater.from(ctx)
        val root = inflater.inflate(R.layout.ball_panel, null) as ViewGroup

        // 总开关
        root.findViewById<Switch>(R.id.panel_master).apply {
            isChecked = AppSettings.masterEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setMasterEnabled(ctx, checked)
                updateState(ctx) // 球体同步显示当前状态
                if (!checked) AdHintOverlay.hide(ctx) // 关掉时清掉残留提示
                GuardService.updateNotification(ctx)
                LogBuf.add("面板:自动跳过已${if (checked) "开启" else "关闭"}")
            }
        }

        // 目标应用:按 AppSettings.KNOWN_APPS 动态生成,加一个 App 不用改布局
        val list = root.findViewById<LinearLayout>(R.id.panel_app_list)
        val targets = AppSettings.targetPackages(ctx)
        AppSettings.KNOWN_APPS.forEach { app ->
            val row = inflater.inflate(R.layout.ball_panel_row, list, false) as ViewGroup
            row.findViewById<TextView>(R.id.row_name).text = app.name
            row.findViewById<Switch>(R.id.row_switch).apply {
                isChecked = app.pkg in targets
                setOnCheckedChangeListener { _, checked ->
                    AppSettings.setTargetPackage(ctx, app.pkg, checked)
                    LogBuf.add("面板:目标 ${app.pkg} ${if (checked) "开启" else "关闭"}")
                }
            }
            list.addView(row)
        }

        root.findViewById<View>(R.id.panel_close).setOnClickListener { hidePanel(ctx) }
        root.findViewById<View>(R.id.panel_open_main).setOnClickListener {
            hidePanel(ctx)
            runCatching { ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) }
        }

        // 面板贴着球弹出:球靠右就往左展开,靠左往右展开,全部夹在屏内
        val ball = ballParams
        val ballSize = ball?.width?.takeIf { it > 0 } ?: (44 * density).toInt()
        val panelW = (PANEL_W_DP * density).toInt()
        val gap = (PANEL_GAP_DP * density).toInt()
        val bounds = screenBounds(ctx)
        val bx = ball?.x ?: (bounds.width() - ballSize * 2)
        val by = ball?.y ?: (bounds.height() / 2)
        // 先量一遍才能知道面板多高,量完再定位
        root.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val panelH = root.measuredHeight

        val params = WindowManager.LayoutParams(
            panelW, panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH:面板外的触摸透传给下面的 App,
            // 并在收到 ACTION_OUTSIDE 时自动收起面板
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bx + ballSize + gap + panelW <= bounds.width()) bx + ballSize + gap
            else (bx - gap - panelW).coerceAtLeast(0)
            y = (by + ballSize / 2 - panelH / 2)
                .coerceIn(0, (bounds.height() - panelH).coerceAtLeast(0))
        }

        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hidePanel(ctx); true
            } else false
        }

        // 关键:addView 成功后才置 panelView,失败则保持 null,下次点击还能重试
        try {
            wm.addView(root, params)
            panelView = root
            LogBuf.add("管理面板已打开")
        } catch (e: Exception) {
            panelView = null
            LogBuf.add("管理面板显示失败: ${e.message}")
        }
    }

    fun hidePanel(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hidePanel(ctx) }
            return
        }
        val v = panelView ?: return
        panelView = null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
    }

    private fun updateState(ctx: Context) {
        val tv = textView ?: return
        val on = AppSettings.masterEnabled(ctx)
        tv.text = if (on) "开" else "关"
        tv.setBackgroundResource(if (on) R.drawable.ball_on else R.drawable.ball_off)
    }

    /** 当前屏幕可用区域(含导航栏,故用 maximumWindowMetrics 而非 displayMetrics)。 */
    private fun screenBounds(ctx: Context): Rect {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
        else {
            val dm = ctx.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }
}