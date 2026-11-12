package com.jimmy.expiryreminder.ocr

/**
 * 从版面里挑品名。
 *
 * 不用图像分类：离线 ImageNet 模型只能给出 bottle / box 这种粗类，跟本应用的
 * 分类体系对不上。包装上品名必然以最大字号出现，靠字号 + 黑名单就够了。
 */
object NameExtractor {

    private const val MIN_LEN = 2
    private const val MAX_LEN = 20
    private const val MAX_CANDIDATES = 3

    /** 命中这些词的行是行政信息，不是品名。 */
    private val EXCLUDE_WORDS = listOf(
        "净含量", "规格", "配料", "营养成分", "营养", "生产商", "生产企业", "生产厂", "生产者",
        "地址", "电话", "网址", "执行标准", "条形码", "保质期", "保存期", "贮存", "储存", "存储",
        "食用方法", "注意事项", "经销商", "委托方", "受托方", "产地", "许可证", "生产日期",
        "有效期", "批号", "客服", "服务热线", "热线", "扫码", "致敏", "过敏", "请勿", "置于",
        "儿童", "回收", "标志", "认证", "监制", "出品", "制造商", "代理商", "净重", "固形物",
        "SC", "GB", "ISO", "HACCP", "QS",
    )

    private val LONG_DIGITS = Regex("""\d{4,}""")
    private val DATE_LIKE = Regex("""\d{4}\s*[-–—./年]""")

    /**
     * @param lines 一张图的 OCR 行
     * @param imageIndex 图序号，用于跨图合并时统计「多图一致」
     */
    fun extract(lines: List<OcrLine>, imageIndex: Int = 0): List<NameCandidate> {
        if (lines.isEmpty()) return emptyList()
        val maxHeight = lines.maxOf { it.height }.coerceAtLeast(1)

        return lines
            .mapNotNull { line ->
                val text = TextNormalizer.prepare(line.text).trim()
                if (!isUsableName(text)) return@mapNotNull null
                val height = line.height.coerceAtLeast(0)
                // 字号越大越可能是品名：同字号时都拿 0.9，只用于排序
                val score = 0.62 + 0.28 * (height.toDouble() / maxHeight)
                NameCandidate(
                    text = text,
                    score = score.coerceAtMost(0.9),
                    imageCount = 1,
                    imageIndex = imageIndex,
                )
            }
            .sortedWith(compareByDescending<NameCandidate> { it.score }.thenBy { it.text.length })
            .take(MAX_CANDIDATES)
    }

    private fun isUsableName(text: String): Boolean {
        if (text.length < MIN_LEN || text.length > MAX_LEN) return false
        if (EXCLUDE_WORDS.any { text.contains(it, ignoreCase = true) }) return false
        if (DATE_LIKE.containsMatchIn(text)) return false
        if (LONG_DIGITS.containsMatchIn(text)) return false

        val hanCount = text.count { it.isHan() }
        val letterCount = text.count { it.isLetter() }
        // 中文为主，或至少有两个汉字（「纯牛奶」「特仑苏」这类短品名）
        return hanCount >= 2 || (letterCount >= 4 && hanCount == 0)
    }

    private fun Char.isHan(): Boolean = code in 0x4E00..0x9FFF
}
