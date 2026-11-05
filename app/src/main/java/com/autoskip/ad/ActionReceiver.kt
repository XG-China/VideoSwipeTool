package com.autoskip.ad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏按钮的落点(manifest 注册,exported=false):
 *  - 开关总开关
 *  - 转发一次手动上滑给无障碍服务
 */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE = "com.autoskip.ad.action.TOGGLE_MASTER"
        const val ACTION_SWIPE = "com.autoskip.ad.action.NOTIF_SWIPE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val on = !AppSettings.masterEnabled(app)
                AppSettings.setMasterEnabled(app, on)
                if (!on) AdHintOverlay.hide(app)
                ToggleBall.refresh(app)
                GuardService.updateNotification(app)
                LogBuf.add("通知栏:自动跳过已${if (on) "开启" else "关闭"}")
                AdHintOverlay.show(app, if (on) "自动跳过已开启" else "自动跳过已关闭")
            }

            ACTION_SWIPE -> {
                LogBuf.add("通知栏:手动上滑")
                // 动态注册的接收器是 NOT_EXPORTED,同应用广播可以送达
                app.sendBroadcast(
                    Intent(AdSkipAccessibilityService.ACTION_SWIPE_NOW)
                        .setPackage(app.packageName)
                )
            }
        }
    }
}
