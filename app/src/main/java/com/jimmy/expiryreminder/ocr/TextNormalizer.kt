package com.jimmy.expiryreminder.ocr

/**
 * OCR 文本归一化。
 *
 * ML Kit 输出里混着全角标点、全角数字、零宽字符和多余空白；中文包装还常用
 * 「二〇二六年三月十五日」这种写法。这些都要在进入规则引擎之前抹平。
 */
object TextNormalizer {

    private val ZERO_WIDTH = Regex("[\u200B-\u200F\uFEFF]")
    private val SPACES = Regex("[\\s\u00A0]+")

    /** 全角转半角 + 去零宽 + 压缩空白 + 去首尾空格。 */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(c)
        }
        return SPACES.replace(ZERO_WIDTH.replace(sb.toString(), ""), " ").trim()
    }

    /** 聚类/去重用的比较键：只留字母与数字，忽略大小写与标点。 */
    fun comparisonKey(raw: String): String =
        normalize(raw).filter { it.isLetterOrDigit() }.lowercase()

    private val CN_DATE = Regex(
        "([〇零一二三四五六七八九]{2,4})\\s*年\\s*([一二三四五六七八九十]{1,3})\\s*月" +
            "\\s*([一二三四五六七八九十]{1,3})\\s*日",
    )

    /** 把「二〇二六年三月十五日」改写成「2026年3月15日」，解析不了的段保持原样。 */
    fun normalizeChineseDates(text: String): String {
        if (text.isEmpty()) return text
        return CN_DATE.replace(text) { m ->
            val year = cnYearToInt(m.groupValues[1])
            val month = cnSmallToInt(m.groupValues[2])
            val day = cnSmallToInt(m.groupValues[3])
            if (year == null || month == null || day == null) {
                m.value
            } else {
                "${year}年${month}月${day}日"
            }
        }
    }

    /** 预处理入口：先做字符归一，再处理中文数字日期。 */
    fun prepare(raw: String): String = normalizeChineseDates(normalize(raw))

    private fun cnDigit(c: Char): Int? = when (c) {
        '〇', '零' -> 0
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
    }

    /** 年份是逐位念的（二〇二六 = 2026），不做「十」的进位。 */
    private fun cnYearToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val builder = StringBuilder(s.length)
        for (ch in s) {
            builder.append(cnDigit(ch) ?: return null)
        }
        return builder.toString().toIntOrNull()
    }

    /** 月/日是正常中文计数：三 → 3，十 → 10，十五 → 15，二十一 → 21。 */
    private fun cnSmallToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.length == 1) return cnDigit(s[0]) ?: if (s[0] == '十') 10 else null
        val tenIndex = s.indexOf('十')
        if (tenIndex < 0) {
            // 逐位写法（如「二五」）
            val digits = s.map { cnDigit(it) ?: return null }
            return digits.joinToString("").toIntOrNull()
        }
        val tens = if (tenIndex == 0) 1 else cnDigit(s[tenIndex - 1]) ?: return null
        val ones = if (tenIndex == s.length - 1) 0 else cnDigit(s[tenIndex + 1]) ?: return null
        return tens * 10 + ones
    }
}
