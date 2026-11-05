package com.autoskip.ad

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把内存日志导出成文本文件,通过系统分享面板发出去(不联网,由用户选择落点)。
 * 用于真机排查:日志原本进程一死就没了。
 */
object LogExporter {

    fun share(ctx: Context) {
        runCatching {
            val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "autoskip-$stamp.txt")
            val version = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull().orEmpty()

            file.writeText(
                buildString {
                    appendLine("AutoSkip 运行日志")
                    appendLine("版本: $version")
                    appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("统计: ${AppSettings.statsText(ctx)}")
                    appendLine("自动回退: ${AppSettings.autoCorrectEpisode(ctx)}")
                    appendLine("关键词: ${AppSettings.keywords(ctx).joinToString("/")}")
                    appendLine("扫描间隔: ${AppSettings.scanIntervalMs(ctx)} ms")
                    appendLine("----------------------------------------")
                    LogBuf.snapshot().forEach { appendLine(it) }
                }
            )

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AutoSkip 运行日志")
                clipData = android.content.ClipData.newRawUri("AutoSkip 运行日志", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "导出运行日志")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            LogBuf.add("日志已导出: ${file.name}")
        }.onFailure {
            LogBuf.add("日志导出失败: ${it.message}")
        }
    }
}
