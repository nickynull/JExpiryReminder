package com.jimmy.expiryreminder.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import kotlin.math.max

/**
 * ML Kit 中文识别器封装。
 *
 * 用的是 **bundled** 版（`com.google.mlkit:text-recognition-chinese`），模型随 APK
 * 分发，运行期不联网、不依赖 Google Play 服务。识别器实例在进程内复用，
 * 且**串行**调用——并发调同一个 recognizer 不可靠。
 *
 * 除整图识别外还提供区域识别：包装上的日期喷码在整张照片里只占很小的
 * 区域，落盘图最长边只有 1280px，喷码往往不到 200px 高——全图识别时
 * 分辨率被稀释，小字经常读错。把目标区域裁出来放大到接近引擎的舒适
 * 区再喂给识别器，等效分辨率能翻好几倍，是当前离线引擎下最有效的
 * 提准手段。
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
        val bitmap = withContext(Dispatchers.IO) { decodeFile(file) }
            ?: throw IllegalStateException("图片解码失败")
        try {
            return recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** 识别一张已在内存里的图。调用方保留所有权，本方法不回收。 */
    suspend fun recognizeBitmap(bitmap: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        }
        return text.toOcrLines()
    }

    /**
     * 区域识别：裁出落盘图里的 [crop] 区域，放大后再识别。
     *
     * [crop] 是原图像素坐标（无需精确框住目标，会自动外扩 20% 兜住相邻
     * 文字）；[targetLongSide] 是放大后的期望长边，实际放大倍数限制在
     * 4 倍以内、长边 2000px 以内，防止小区域被放得过大拖慢识别。
     */
    suspend fun recognizeRegionFile(
        file: File,
        crop: Rect,
        targetLongSide: Int = 1200,
    ): List<OcrLine> {
        val bitmap = withContext(Dispatchers.IO) { decodeFile(file) }
            ?: throw IllegalStateException("图片解码失败")
        try {
            val region = withContext(Dispatchers.IO) {
                cropAndUpscale(bitmap, crop, targetLongSide.coerceIn(320, 2000))
            }
            try {
                return recognizeBitmap(region)
            } finally {
                if (region !== bitmap) region.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------ 内部

    private fun decodeFile(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    /**
     * 裁剪并放大。裁剪区先外扩 20%（喷码旁的关键词如「有效期至」
     * 往往就在边上，扩一点能把它们一起带进识别结果），再按比例放大。
     */
    private fun cropAndUpscale(bitmap: Bitmap, crop: Rect, targetLongSide: Int): Bitmap {
        val growX = (crop.width() * 0.2f).toInt().coerceAtLeast(8)
        val growY = (crop.height() * 0.2f).toInt().coerceAtLeast(8)
        val left = (crop.left - growX).coerceAtLeast(0)
        val top = (crop.top - growY).coerceAtLeast(0)
        val right = (crop.right + growX).coerceAtMost(bitmap.width)
        val bottom = (crop.bottom + growY).coerceAtMost(bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return bitmap

        val region = Bitmap.createBitmap(bitmap, left, top, w, h)
        val scale = (targetLongSide.toFloat() / max(w, h)).coerceAtMost(4f)
        if (scale <= 1.05f) return region
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(region, scaledW, scaledH, true)
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
