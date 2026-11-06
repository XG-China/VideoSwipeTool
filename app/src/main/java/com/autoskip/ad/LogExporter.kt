package com.autoskip.ad

import android.content.Context
import android.content.Intent
import android.os.Build
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
            val file = File(dir, "video-swipe-$stamp.txt")
            val version = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull().orEmpty()

            file.writeText(
                buildString {
                    appendLine("视频上滑工具运行日志")
                    appendLine("版本: $version")
                    appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("统计: ${AppSettings.statsText(ctx)}")
                    appendLine("自动回退: ${AppSettings.autoCorrectEpisode(ctx)}")
                    val targets = AppSettings.targetPackages(ctx).map { pkg ->
                        AppSettings.KNOWN_APPS.firstOrNull { it.pkg == pkg }?.name ?: pkg
                    }.joinToString("/").ifEmpty { "无" }
                    appendLine("目标应用: $targets")
                    appendLine("剧集关键词: ${AppSettings.episodeKeywords(ctx).joinToString("/")}")
                    appendLine("首页关键词: ${AppSettings.homeKeywords(ctx).joinToString("/")}")
                    appendLine("广告关键词: ${AppSettings.adKeywords(ctx).joinToString("/")}")
                    appendLine("扫描间隔: ${AppSettings.scanIntervalMs(ctx)} ms")
                    appendLine("全屏识字: ${AppSettings.ocrEnabled(ctx)} (SDK ${Build.VERSION.SDK_INT})")
                    val timings = Timing.entries.joinToString(" ") { timing ->
                        val value = AppSettings.timing(ctx, timing)
                        val text = if (timing.unit == "s") "%.1f s".format(value / 1000.0)
                        else "${value.toInt()} ${timing.unit}"
                        "${timing.label}=$text"
                    }
                    appendLine("时序: $timings")
                    appendLine("----------------------------------------")
                    // 优先附上持久化日志(进程被杀前的记录也在)
                    val persisted = LogBuf.filePath()?.let { File(it) }
                    if (persisted != null && persisted.exists()) {
                        appendLine("持久化日志: ${persisted.absolutePath}")
                        appendLine("---------- 最近 2000 行 ----------")
                        persisted.readLines().takeLast(2000).forEach { appendLine(it) }
                    } else {
                        LogBuf.snapshot().forEach { appendLine(it) }
                    }
                }
            )

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "视频上滑工具日志")
                clipData = android.content.ClipData.newRawUri("视频上滑工具日志", uri)
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
