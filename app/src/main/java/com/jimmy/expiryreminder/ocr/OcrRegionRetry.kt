package com.jimmy.expiryreminder.ocr

import android.graphics.Rect
import java.io.File

/**
 * 日期区域自动二次重识。
 *
 * 整图识别时，日期喷码常因分辨率不足而读错（比如把 2026 读成 2O26、
 * 丢掉最后一位）。这里把含连续 4 位以上数字的行裁出来放大重识，
 * 追加到原行后面交给抽取器再跑一遍——原行读对的保持不变，读错的
 * 由放大后的版本补上。只在整图结果里**没有可预填的日期候选**时才
 * 触发，不增加正常路径的耗时。
 */
object OcrRegionRetry {

    /** 连续 4 位数字：年份或紧凑日期，是「这行可能是个日期」的最低信号。 */
    private val DATE_HINT = Regex("""\d{4}""")

    /** 每张图最多重试几个区域，防止批号、配料表里的数字把耗时拖爆。 */
    private const val MAX_REGIONS = 3

    /**
     * 从 [file] 的落盘图里挑出疑似日期行，逐个裁剪放大重识。
     * 返回的行与原图行坐标无对应关系，只用于文本抽取，不要参与版面分析。
     */
    suspend fun retryDateRegions(file: File, lines: List<OcrLine>): List<OcrLine> {
        val boxes = lines
            .filter { it.height > 0 && DATE_HINT.containsMatchIn(it.text) }
            // 喷码通常比印刷体大不了多少，但至少排除极小的干扰行
            .filter { it.height >= 12 }
            .sortedByDescending { it.height }
            .take(MAX_REGIONS)
        if (boxes.isEmpty()) return emptyList()

        val out = mutableListOf<OcrLine>()
        for (box in boxes) {
            val long = maxOf(box.right - box.left, box.height)
            val target = (long * 3).coerceIn(640, 1536)
            val extra = runCatching {
                OcrEngine.recognizeRegionFile(file, Rect(box.left, box.top, box.right, box.bottom), target)
            }.getOrDefault(emptyList())
            out += extra
        }
        return out
    }
}
