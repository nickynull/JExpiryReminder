package com.jimmy.expiryreminder.ocr

import com.jimmy.expiryreminder.core.ShelfLifeUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 从 OCR 文本行里抽日期与保质期。
 *
 * 设计取向：**宁缺勿错**。没有关键词锚定的日期一律降到 0.7 以下，只进候选区
 * 不参与预填——一张包装上通常同时印着生产日期、批号、有效期，猜错的代价
 * （错误提醒）远大于留空的代价。
 */
object DateExtractor {

    /** 基础分：只够进候选，不够预填。 */
    private const val BASE_SCORE = 0.55
    private const val SAME_LINE_BONUS = 0.30
    private const val NEARBY_LINE_BONUS = 0.10
    private const val PAST_EXPIRE_PENALTY = 0.40

    private val PRODUCE_WORDS = listOf(
        "生产日期", "制造日期", "包装日期", "灌装日期", "分装日期", "MFD", "MFG", "PROD",
    )

    /** 裸「生产」也能当关键词，但这些词里的「生产」是厂名，不是日期。 */
    private val PRODUCE_WORD_EXCLUDE = listOf(
        "生产商", "生产企业", "生产厂", "生产者", "生产地址", "生产许可", "生产批号",
    )

    private val EXPIRE_WORDS = listOf(
        "有效期至", "有效至", "有效期", "保质期至", "最佳食用日期", "最佳赏味期",
        "此日期前食用最佳", "EXP", "BBE", "USE BY",
    )

    private val SEPARATED = Regex(
        """(?<!\d)(\d{4})\s*[-–—./年]\s*(\d{1,2})\s*[-–—./月]\s*(\d{1,2})\s*日?""",
    )
    private val REVERSED = Regex(
        """(?<!\d)(\d{1,2})\s*[-–—./]\s*(\d{1,2})\s*[-–—./]\s*(\d{4})(?!\d)""",
    )
    private val COMPACT8 = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)""")
    private val COMPACT6 = Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})(?!\d)""")

    private val SHELF_LIFE = Regex(
        """(保质期|保存期|shelf\s*life)\s*:?\s*(\d{1,3})\s*(天|日|个月|月|年)""",
        RegexOption.IGNORE_CASE,
    )

    private val NOW = { LocalDate.now() }

    /**
     * 抽取一张图里的所有日期。
     *
     * @param lines 该图的 OCR 行（已带 boundingBox）
     * @param imageIndex 图在本次识别里的序号，用于跨图合并时统计出现次数
     * @param today 注入当前日期，方便单测
     */
    fun extract(
        lines: List<OcrLine>,
        imageIndex: Int = 0,
        today: LocalDate = NOW(),
    ): List<DateCandidate> {
        if (lines.isEmpty()) return emptyList()

        val prepared = lines.map { TextNormalizer.prepare(it.text) }
        val rowHasProduce = prepared.map { hasProduceWord(it) }
        val rowHasExpire = prepared.map { hasExpireWord(it) }

        val out = mutableListOf<DateCandidate>()
        prepared.forEachIndexed { rowIndex, text ->
            if (text.isEmpty()) return@forEachIndexed
            val occupied = BooleanArray(text.length)

            // 完整分隔格式最可靠，优先占位；紧凑数字其次，避免「20260315」被拆成 6 位。
            val matches = mutableListOf<Pair<MatchResult, Int>>()
            SEPARATED.findAll(text).forEach { matches += it to 0 }
            REVERSED.findAll(text).forEach { m ->
                if (!overlaps(occupied, m.range)) matches += m to 1
            }
            COMPACT8.findAll(text).forEach { m ->
                if (!overlaps(occupied, m.range)) matches += m to 8
            }
            COMPACT6.findAll(text).forEach { m ->
                if (!overlaps(occupied, m.range)) matches += m to 6
            }
            // 记录已产出的区间，防止同一段数字被不同正则重复解析
            matches.sortedBy { it.first.range.first }.forEach { (m, kind) ->
                if (overlaps(occupied, m.range)) return@forEach
                val parsed = parseMatch(m, kind, today) ?: return@forEach
                markOccupied(occupied, m.range)

                val (role, bonus) = resolveRole(
                    rowIndex = rowIndex,
                    matchStart = m.range.first,
                    rowHasProduce = rowHasProduce,
                    rowHasExpire = rowHasExpire,
                )
                // 紧凑格式没有分隔符，误判风险高，必须有同行关键词撑着
                if (kind >= 6 && bonus < SAME_LINE_BONUS) return@forEach

                var score = BASE_SCORE + bonus
                if (role == DateRole.expire) {
                    val days = ChronoUnit.DAYS.between(today, parsed)
                    if (days > 365L * 50) return@forEach
                    if (days < -365L * 2) score -= PAST_EXPIRE_PENALTY
                }
                if (score < CANDIDATE_SCORE) return@forEach

                out += DateCandidate(
                    raw = m.value.trim(),
                    isoDate = parsed.toString(),
                    role = role,
                    score = score.coerceAtMost(1.0),
                    sourceLine = text.take(40),
                    imageIndex = imageIndex,
                )
            }
        }
        return out.sortedByDescending { it.score }
    }

    /** 抽「保质期 12 个月」这类相对量。 */
    fun extractShelfLife(lines: List<OcrLine>, imageIndex: Int = 0): List<ShelfLifeCandidate> {
        val out = mutableListOf<ShelfLifeCandidate>()
        for (line in lines) {
            val text = TextNormalizer.prepare(line.text)
            if (text.isEmpty()) continue
            SHELF_LIFE.findAll(text).forEach { m ->
                val amount = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (amount <= 0) return@forEach
                val unit = when (m.groupValues[3]) {
                    "天", "日" -> ShelfLifeUnit.day
                    "年" -> ShelfLifeUnit.year
                    else -> ShelfLifeUnit.month
                }
                out += ShelfLifeCandidate(
                    amount = amount,
                    unit = unit,
                    score = 0.95,
                    sourceLine = text.take(40),
                    imageIndex = imageIndex,
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ 内部

    private fun parseMatch(m: MatchResult, kind: Int, today: LocalDate): LocalDate? {
        // 包装上被误识别成 2026-13-45 这类非法日期很常见，LocalDate.of 会直接抛异常，
        // 必须在这里兜住——不能让一个脏字段崩掉整次识别。
        val date = try {
            when (kind) {
                0 -> LocalDate.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                )
                1 -> {
                    // dd/MM/yyyy 与 MM/dd/yyyy 都不常见；超过 12 的那个数就是日
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toInt()
                    val y = m.groupValues[3].toInt()
                    when {
                        a > 12 -> LocalDate.of(y, b, a)
                        b > 12 -> LocalDate.of(y, a, b)
                        else -> LocalDate.of(y, b, a)
                    }
                }
                8 -> LocalDate.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                )
                else -> LocalDate.of(
                    m.groupValues[1].toInt() + 2000, m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                )
            }
        } catch (_: Exception) {
            return null
        }
        // 年份离当前太远的多半是批号或误识
        if (date.year < today.year - 30 || date.year > today.year + 20) return null
        return date
    }

    /**
     * 判定角色。返回角色与加分：同行关键词 +0.30，上下两行内有关键词 +0.10，
     * 都没有则视为未知（只进候选）。
     */
    private fun resolveRole(
        rowIndex: Int,
        matchStart: Int,
        rowHasProduce: List<Boolean>,
        rowHasExpire: List<Boolean>,
    ): Pair<DateRole, Double> {
        val inProduce = rowHasProduce[rowIndex]
        val inExpire = rowHasExpire[rowIndex]
        if (inProduce && !inExpire) return DateRole.produce to SAME_LINE_BONUS
        if (inExpire && !inProduce) return DateRole.expire to SAME_LINE_BONUS
        if (inProduce) {
            // 同一行两种关键词都有，理论上少见；按未知处理更安全
            return DateRole.unknown to 0.0
        }

        val from = (rowIndex - 2).coerceAtLeast(0)
        val to = (rowIndex + 2).coerceAtMost(rowHasProduce.size - 1)
        var produceNear = false
        var expireNear = false
        for (i in from..to) {
            if (i == rowIndex) continue
            if (rowHasProduce[i]) produceNear = true
            if (rowHasExpire[i]) expireNear = true
        }
        return when {
            produceNear && !expireNear -> DateRole.produce to NEARBY_LINE_BONUS
            expireNear && !produceNear -> DateRole.expire to NEARBY_LINE_BONUS
            else -> DateRole.unknown to 0.0
        }
    }

    private fun hasProduceWord(text: String): Boolean {
        val upper = text.uppercase()
        if (PRODUCE_WORDS.any { wordMatches(upper, it) }) return true
        // 裸「生产」只在不是厂名时才算
        if (!text.contains("生产")) return false
        return PRODUCE_WORD_EXCLUDE.none { text.contains(it) }
    }

    private fun hasExpireWord(text: String): Boolean {
        val upper = text.uppercase()
        return EXPIRE_WORDS.any { wordMatches(upper, it) }
    }

    /** 英文关键词按词边界匹配，避免 EXP 命中 EXPORT；中文直接找子串。 */
    private fun wordMatches(upperText: String, word: String): Boolean {
        val target = if (word.all { it.code < 128 }) word.uppercase() else word
        return if (target.all { it.code < 128 || it == ' ' }) {
            Regex("\\b${Regex.escape(target)}\\b").containsMatchIn(upperText)
        } else {
            upperText.contains(target)
        }
    }

    private fun overlaps(occupied: BooleanArray, range: IntRange): Boolean {
        for (i in range) {
            if (i in occupied.indices && occupied[i]) return true
        }
        return false
    }

    private fun markOccupied(occupied: BooleanArray, range: IntRange) {
        for (i in range) {
            if (i in occupied.indices) occupied[i] = true
        }
    }
}
