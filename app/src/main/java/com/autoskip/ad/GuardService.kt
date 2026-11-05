package com.autoskip.ad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 后台保活前台服务:占住进程,防止 MIUI 省电策略冻结本应用导致无障碍服务反复断连。
 * 断连期间无法接收广告事件,广告就不会被自动滑——保活后此问题基本消失。
 */
class GuardService : Service() {

    companion object {
        const val CHANNEL_ID = "auto_skip_guard"
        const val NOTIF_ID = 3001

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        return START_STICKY
    }

    private fun startForegroundInternal() {
        createChannel()
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("自动广告跳过服务运行中")
            .setContentText("红果免费短剧广告将自动上滑跳过")
            .setSmallIcon(R.drawable.ic_stat_skip)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动跳过运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持后台运行,避免广告跳过被系统冻结"
                setShowBadge(false)
            }
        )
    }
}