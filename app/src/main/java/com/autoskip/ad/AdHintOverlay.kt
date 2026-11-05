package com.autoskip.ad

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 屏幕顶部提示条:上滑时显示「已上滑」,2.5 秒后自动消失;
 * 事后的校验结果(「选集」有没有回来)也走这里,一眼就能看出这次到底跳没跳掉。
 */
object AdHintOverlay {

    @Volatile private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideTask: Runnable? = null
    private var lastText = ""
    private var lastShownText = ""   // 上次实际显示过的文本(防抖用)
    private var lastShownAt = 0L     // 上次实际显示的时间

    fun show(ctx: Context, message: String) {
        // addView 必须在主线程执行(无障碍事件线程是 Binder 线程),跳一下
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(ctx, message) }
            return
        }
        val safeText = if (message.length > 60) message.substring(0, 60) else message
        val now = android.os.SystemClock.uptimeMillis()
        // 防抖:同样内容刚显示过且已自动消失时,3 秒内不重新弹出,避免残留广告反复触发提示条
        if (view == null && safeText == lastShownText && now - lastShownAt < 3000L) return
        val v = view
        if (v != null) {
            // 已有窗口:更新文本;只有文本变化才重置消失计时,同文本反复刷新不延长显示
            if (safeText != lastText) {
                lastText = safeText
                lastShownText = safeText
                lastShownAt = now
                v.text = safeText
                resetHide(ctx)
            }
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            text = safeText
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC000000.toInt())
            setPadding((density * 14).toInt(), (density * 7).toInt(), (density * 14).toInt(), (density * 7).toInt())
            typeface = Typeface.DEFAULT_BOLD
            alpha = 1f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (density * 90).toInt()
        }
        lastText = safeText
        // 关键:addView 成功后才置 view,失败则保持 null,下次检测到广告才重试
        try {
            wm.addView(tv, params)
            view = tv
            lastShownText = safeText
            lastShownAt = now
            LogBuf.add("提示条已显示: $safeText")
            resetHide(ctx)
        } catch (e: Exception) {
            view = null
            lastText = ""
            LogBuf.add("提示条显示失败: ${e.message}")
        }
    }

    fun hide(ctx: Context) {
        // removeView 与 addView 需同线程(主线程)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide(ctx) }
            return
        }
        hideTask?.let { handler.removeCallbacks(it) }
        hideTask = null
        val v = view ?: return
        view = null
        lastText = ""
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
    }

    private fun resetHide(ctx: Context) {
        hideTask?.let { handler.removeCallbacks(it) }
        hideTask = Runnable { hide(ctx) }
        handler.postDelayed(hideTask!!, 2500L)
    }
}