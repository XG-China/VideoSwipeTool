package com.autoskip.ad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 后台保活前台服务:占住进程,防止 MIUI 省电策略冻结本应用导致无障碍服务反复断连。
 * 断连期间无法接收广告事件,广告就不会被自动滑——保活后此问题基本消失。
 *
 * Android 15+ 的 dataSync 类型有每日 6 小时上限,因此改用 specialUse(自用分发,
 * 不需要上架 Play),并保留 onTimeout 兜底。
 */
class GuardService : Service() {

    companion object {
        const val CHANNEL_ID = "auto_skip_guard"
        const val NOTIF_ID = 3001

        fun start(ctx: Context) {
            // Android 12+ 后台启动前台服务可能被系统拒绝,不能让它把进程带崩
            runCatching {
                ctx.startForegroundService(Intent(ctx, GuardService::class.java))
            }.onFailure {
                LogBuf.add("保活服务启动失败: ${it.message}")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GuardService::class.java)) }
        }

        /** 总开关变化后刷新通知文案与按钮(服务未运行时静默忽略)。 */
        fun updateNotification(ctx: Context) {
            runCatching {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(ctx))
            }
        }

        private fun buildNotification(ctx: Context): Notification {
            val master = AppSettings.masterEnabled(ctx)
            val togglePi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val swipePi = PendingIntent.getBroadcast(
                ctx, 2,
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_SWIPE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val pending = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val actionIcon = android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat_skip)
            return Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(if (master) "自动广告跳过运行中" else "自动广告跳过已暂停")
                .setContentText(
                    if (master) "红果免费短剧广告将自动上滑跳过 · ${AppSettings.statsText(ctx)}"
                    else "总开关已关闭,点击「开启」恢复自动上滑"
                )
                .setSmallIcon(R.drawable.ic_stat_skip)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .addAction(Notification.Action.Builder(actionIcon, if (master) "关闭" else "开启", togglePi).build())
                .addAction(Notification.Action.Builder(actionIcon, "上滑一次", swipePi).build())
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            startForegroundInternal()
            START_STICKY
        }.onFailure {
            LogBuf.add("保活服务启动失败: ${it.message}")
        }.getOrDefault(START_NOT_STICKY)
    }

    private fun startForegroundInternal() {
        createChannel()
        val notif = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** Android 15 起系统会在前台服务达到时长上限时调用;主动停止,避免 ANR。 */
    override fun onTimeout(startId: Int) {
        onForegroundTimeout("startId=$startId")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onForegroundTimeout("startId=$startId type=$fgsType")
    }

    private fun onForegroundTimeout(reason: String) {
        LogBuf.add("保活服务达到系统时长上限($reason),已停止;无障碍服务本身仍然生效")
        stopSelf()
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
