package com.autoskip.ad

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 内存环形日志 + 本地持久化文件。
 *
 * - 内存保留最近 [MAX] 行,供主界面展示;
 * - 服务连接后把每条日志同步写入 filesDir/logs/autoskip.log,
 *   进程被杀/手机重启后仍能找回最近的运行记录;
 * - 文件超过 2MB 自动轮转为 autoskip.log.1,只保留两份。
 */
object LogBuf {
    private const val MAX = 1000
    private const val MAX_FILE = 2 * 1024 * 1024
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var fileSize = 0L

    /** 日志最多保留的行数(供 UI 文案展示)。 */
    val maxLines: Int get() = MAX

    /** 服务/主界面启动时调用一次;重复调用无副作用。 */
    @Synchronized
    fun attachFile(ctx: Context) {
        if (writer != null) return
        runCatching {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "autoskip.log")
            if (f.length() > MAX_FILE) {
                val old = File(dir, "autoskip.log.1")
                old.delete()
                f.renameTo(old)
            }
            file = f
            fileSize = f.length()
            writer = BufferedWriter(FileWriter(f, true))
        }
    }

    @Synchronized
    fun add(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        // 镜像到 logcat,便于 adb 远程诊断
        android.util.Log.i("AutoSkip", msg)
        runCatching {
            val w = writer ?: return@runCatching
            w.append(line).append('\n').flush()
            fileSize += line.length + 1
            if (fileSize > MAX_FILE) rotate()
        }
    }

    @Synchronized
    fun snapshot(): List<String> = buf.toList()

    @Synchronized
    fun clear() = buf.clear()

    /** 当前持久化日志路径,导出时使用。 */
    @Synchronized
    fun filePath(): String? = file?.absolutePath

    private fun rotate() {
        runCatching {
            writer?.close()
            val f = file ?: return
            val dir = f.parentFile ?: return
            val old = File(dir, "autoskip.log.1")
            old.delete()
            f.renameTo(old)
            fileSize = 0L
            writer = BufferedWriter(FileWriter(f, true))
        }
    }
}
