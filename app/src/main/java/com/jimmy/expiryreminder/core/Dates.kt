package com.jimmy.expiryreminder.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 日期工具。 */
object Dates {
    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun toISODate(date: LocalDate): String = date.toString()

    fun todayISO(): String = toISODate(LocalDate.now())

    fun parseISO(value: String): LocalDate? {
        if (!ISO.matches(value)) return null
        return try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    fun isValidISODate(value: String): Boolean = parseISO(value) != null

    /** 距目标日期还有几天；负数表示已过期，日期无效返回 null。 */
    fun daysUntil(value: String, from: LocalDate = LocalDate.now()): Int? {
        val target = parseISO(value) ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(from, target).toInt()
    }

    fun addDays(value: String, days: Int): String {
        val date = parseISO(value) ?: return ""
        return toISODate(date.plusDays(days.toLong()))
    }

    /** 加整月，月末自动收敛（1 月 31 日 + 1 月 = 2 月 28/29 日）。 */
    fun addMonths(value: String, months: Int): String {
        val date = parseISO(value) ?: return ""
        return toISODate(date.plusMonths(months.toLong()))
    }

    /** 相对描述：已过期 3 天 / 今天到期 / 还有 12 天。 */
    fun describeRemaining(value: String, from: LocalDate = LocalDate.now()): String {
        val days = daysUntil(value, from) ?: return "日期无效"
        return when {
            days < 0 -> "已过期 ${-days} 天"
            days == 0 -> "今天到期"
            days == 1 -> "明天到期"
            else -> "还有 $days 天"
        }
    }

    fun nowIsoTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
