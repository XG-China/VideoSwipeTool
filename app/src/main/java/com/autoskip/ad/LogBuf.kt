package com.autoskip.ad

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 内存环形日志,供 App 内日志区展示,便于调试验证。 */
object LogBuf {
    private const val MAX = 120
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 日志最多保留的行数(供 UI 文案展示)。 */
    val maxLines: Int get() = MAX

    @Synchronized
    fun add(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        // 镜像到 logcat,便于 adb 远程诊断
        android.util.Log.i("AutoSkip", msg)
    }

    @Synchronized
    fun snapshot(): List<String> = buf.toList()

    @Synchronized
    fun clear() = buf.clear()
}