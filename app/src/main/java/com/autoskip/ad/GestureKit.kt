package com.autoskip.ad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 手势执行:上滑屏幕翻到下一页(跳过广告)。
 * 全部基于无障碍 dispatchGesture,无需 root。
 */
object GestureKit {

    /**
     * 执行“跳过当前广告”(上滑屏幕)。
     *
     * @return 手法名;null 表示**手势没能送达系统**(如上一次手势还在队列里)。
     *
     * 注意语义边界:非 null 只说明系统收下并执行了这个手势,**不代表 App 有反应** ——
     * 手势是绝对的,注入这一环不存在失败。所以"广告有没有被跳掉"不能看这里,
     * 必须事后校验(见 AdSkipAccessibilityService.checkSwipeResult)。
     */
    fun skipAd(svc: AccessibilityService): String? =
        if (swipeUp(svc)) "上滑" else null

    /** 从屏幕中下方向上快速上滑(0.80h → 0.15h,150ms)。 */
    fun swipeUp(svc: AccessibilityService, durationMs: Long = 150): Boolean {
        val w = svc.resources.displayMetrics.widthPixels.toFloat()
        val h = svc.resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, h * 0.80f)
            lineTo(w / 2f, h * 0.15f)
        }
        return dispatch(svc, path, durationMs)
    }

    /** 从屏幕中上方向下快速下滑(0.20h → 0.85h,200ms),用于误滑后回退一集。 */
    fun swipeDown(svc: AccessibilityService, durationMs: Long = 200): Boolean {
        val w = svc.resources.displayMetrics.widthPixels.toFloat()
        val h = svc.resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, h * 0.20f)
            lineTo(w / 2f, h * 0.85f)
        }
        return dispatch(svc, path, durationMs)
    }

    /**
     * 下达手势。true = 系统已接收并执行;false = 没能送达(队列占用/服务异常)。
     * **不含**「App 是否响应」的语义,那只能事后看屏幕。
     */
    private fun dispatch(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMs)
        ).build()
        return try {
            svc.dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
