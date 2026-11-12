package com.jimmy.expiryreminder.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 中文识别器封装。
 *
 * 用的是 **bundled** 版（`com.google.mlkit:text-recognition-chinese`），模型随 APK
 * 分发，运行期不联网、不依赖 Google Play 服务。识别器实例在进程内复用，
 * 且**串行**调用——并发调同一个 recognizer 不可靠。
 */
object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 提前初始化模型，避免第一张图等待过久。幂等，可重复调用。 */
    fun warmUp() {
        runCatching { recognizer }
    }

    /**
     * 识别一张已落盘的压缩图片。
     *
     * 直接用 Bitmap 而不是 `InputImage.fromFilePath`：后者对 `file://` 支持不可靠，
     * 而这里的图片一定是内部存储里的普通文件。
     */
    suspend fun recognizeFile(file: File): List<OcrLine> {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } ?: throw IllegalStateException("图片解码失败")

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                    .addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
            }
            return text.toOcrLines()
        } finally {
            bitmap.recycle()
        }
    }

    /** 只保留带 boundingBox 的行——没有位置信息就没法做版面分析（按字号挑品名）。 */
    private fun Text.toOcrLines(): List<OcrLine> = buildList {
        for (block in textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val content = line.text
                if (content.isBlank()) continue
                add(OcrLine(content, box.left, box.top, box.right, box.bottom))
            }
        }
    }
}
