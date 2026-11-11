package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DatesTest {

    @Test
    fun `parseISO 与 isValidISODate`() {
        assertEquals(LocalDate.of(2026, 2, 28), Dates.parseISO("2026-02-28"))
        assertNull(Dates.parseISO("2026-13-01"))
        assertNull(Dates.parseISO("abc"))
        assertNull(Dates.parseISO("2026-2-8"))
        assertTrue(Dates.isValidISODate("2026-11-11"))
        assertFalse(Dates.isValidISODate("2026-02-30"))
    }

    @Test
    fun `daysUntil 负数为已过期 0 为今天`() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals(0, Dates.daysUntil("2026-11-11", today))
        assertEquals(1, Dates.daysUntil("2026-11-12", today))
        assertEquals(-3, Dates.daysUntil("2026-11-08", today))
        assertNull(Dates.daysUntil("bad", today))
    }

    @Test
    fun `addDays`() {
        assertEquals("2026-11-12", Dates.addDays("2026-11-11", 1))
        // 跨月
        assertEquals("2026-12-01", Dates.addDays("2026-11-30", 1))
        // 跨闰年
        assertEquals("2027-01-01", Dates.addDays("2026-12-31", 1))
    }

    @Test
    fun `addMonths 月末自动收敛`() {
        assertEquals("2026-02-28", Dates.addMonths("2026-01-31", 1))
        // 闰年
        assertEquals("2024-02-29", Dates.addMonths("2024-01-31", 1))
        assertEquals("2026-03-31", Dates.addMonths("2026-01-31", 2))
        // 12 个月
        assertEquals("2027-11-11", Dates.addMonths("2026-11-11", 12))
    }

    @Test
    fun `describeRemaining`() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("已过期 3 天", Dates.describeRemaining("2026-11-08", today))
        assertEquals("今天到期", Dates.describeRemaining("2026-11-11", today))
        assertEquals("明天到期", Dates.describeRemaining("2026-11-12", today))
        assertEquals("还有 12 天", Dates.describeRemaining("2026-11-23", today))
        assertEquals("日期无效", Dates.describeRemaining("bad", today))
    }
}
