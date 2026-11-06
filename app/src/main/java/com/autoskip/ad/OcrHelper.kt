package com.autoskip.ad

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 本地全屏识字(OCR)。
 *
 * 用 ML Kit 中文识别读取整屏文字,专门补无障碍树读不到广告文案的场景
 * (直播广告经常是 Surface/Canvas 渲染,UI 树里没有任何文字)。
 *
 * 隐私:截图只在本进程内存里识别,识别完立即回收,不落盘、不上传。
 * 模型是随 APK 打包的离线模型,不需要 INTERNET 权限。
 */
class OcrHelper {

    /**
     * 一次识别的结果。
     *
     * @param lines    整屏所有文字行
     * @param topLines 屏幕上方 25% 区域内的文字行,用于区分「广告左上角的返回文字」
     *                 和剧集页左上角的返回图标(图标没有文字)
     */
    data class OcrResult(
        val lines: List<String>,
        val topLines: List<String>
    )

    private var recognizer: TextRecognizer? = null
    private var closed = false

    private fun client(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        ).also { recognizer = it }

    /**
     * 识别一张截图。
     *
     * @param onResult 识别成功时回调(失败时回调空结果)
     * @param onDone   无论成功失败都会回调一次,用于回收 bitmap / 释放忙标记
     */
    fun recognize(
        bitmap: Bitmap,
        onResult: (OcrResult) -> Unit,
        onDone: () -> Unit
    ) {
        if (closed) {
            onDone()
            return
        }
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            onDone()
        }
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val topLimit = bitmap.height * 0.25f
            client().process(image)
                .addOnSuccessListener { result ->
                    val lines = ArrayList<String>(result.textBlocks.size * 4)
                    val topLines = ArrayList<String>(8)
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            if (text.isEmpty()) continue
                            lines += text
                            val box = line.boundingBox
                            if (box != null && box.centerY() < topLimit) topLines += text
                        }
                    }
                    if (!closed) onResult(OcrResult(lines, topLines))
                }
                .addOnFailureListener {
                    if (!closed) onResult(OcrResult(emptyList(), emptyList()))
                }
                .addOnCompleteListener { finish() }
        }.onFailure {
            if (!closed) onResult(OcrResult(emptyList(), emptyList()))
            finish()
        }
    }

    /** 服务销毁时释放识别器;之后再调用 [recognize] 会直接回调完成。 */
    fun close() {
        closed = true
        runCatching { recognizer?.close() }
        recognizer = null
    }
}
