package com.jimmy.expiryreminder.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日期抽取规则。样本取自真实包装上常见的写法，重点是「不该填的别填」。
 */
class DateExtractorTest {

    private val today = LocalDate.of(2026, 11, 12)

    private fun line(text: String, height: Int = 20) = OcrLine(text, 0, 0, 200, height)

    private fun extract(vararg texts: String) =
        DateExtractor.extract(texts.map { line(it) }, 0, today)

    @Test
    fun `生产日期带关键词时判定为生产且可预填`() {
        val result = extract("生产日期 2026-03-15")
        assertEquals(1, result.size)
        assertEquals("2026-03-15", result[0].isoDate)
        assertEquals(DateRole.produce, result[0].role)
        assertTrue("有同行关键词应当达到预填阈值", result[0].prefillable)
    }

    @Test
    fun `有效期至判定为到期`() {
        val result = extract("有效期至 2027/03/15")
        assertEquals(1, result.size)
        assertEquals(DateRole.expire, result[0].role)
        assertEquals("2027-03-15", result[0].isoDate)
    }

    @Test
    fun `中文年月日格式`() {
        val result = extract("生产日期：2026年3月15日")
        assertEquals("2026-03-15", result.first().isoDate)
        assertEquals(DateRole.produce, result.first().role)
    }

    @Test
    fun `中文数字日期先归一化再解析`() {
        val result = extract("生产日期 二〇二六年三月十五日")
        assertEquals("2026-03-15", result.first().isoDate)
    }

    @Test
    fun `没有关键词的日期只进候选不预填`() {
        val result = extract("2026-03-15")
        assertEquals(1, result.size)
        assertEquals(DateRole.unknown, result[0].role)
        assertTrue("没有关键词锚定时不能预填", !result[0].prefillable)
    }

    @Test
    fun `八位紧凑格式没有关键词时直接丢弃`() {
        // 20260315 这种八位数字在包装上更可能是批号
        assertTrue(extract("20260315").isEmpty())
    }

    @Test
    fun `八位紧凑格式有关键词时接受`() {
        val result = extract("生产日期 20260315")
        assertEquals(1, result.size)
        assertEquals("2026-03-15", result[0].isoDate)
    }

    @Test
    fun `批号行不会被当成生产日期`() {
        // 既有「批号」字样又有八位数字，不能判成日期
        assertTrue(extract("批号 20250315").isEmpty())
    }

    @Test
    fun `生产商后面跟的日期不算生产日期`() {
        // 「生产商」是厂名，不是生产日期关键词；此处只能退化成 unknown
        val result = extract("生产商 2026-03-15")
        assertEquals(1, result.size)
        assertEquals(DateRole.unknown, result[0].role)
        assertTrue(!result[0].prefillable)
    }

    @Test
    fun `年份超出合理区间直接丢弃`() {
        assertTrue(extract("生产日期 1990-03-15").isEmpty())
        assertTrue(extract("生产日期 2080-03-15").isEmpty())
    }

    @Test
    fun `过期很久的到期日被降权`() {
        val result = extract("有效期至 2020-03-15")
        // 仍然能抽出来（可能是旧包装），但分数不够预填
        assertEquals(1, result.size)
        assertTrue(!result[0].prefillable)
    }

    @Test
    fun `非法日期不产生结果`() {
        assertTrue(extract("生产日期 2026-13-45").isEmpty())
    }

    @Test
    fun `同一行的日期与关键词无关时按未知处理`() {
        val result = extract("净含量 250mL 2027-01-01")
        assertEquals(1, result.size)
        assertEquals(DateRole.unknown, result[0].role)
    }

    @Test
    fun `相邻行的关键词可以给弱锚定`() {
        val lines = listOf(
            line("保质期至"),
            line("2027-03-15"),
        )
        val result = DateExtractor.extract(lines, 0, today)
        assertEquals(1, result.size)
        assertEquals(DateRole.expire, result[0].role)
        // 跨行只给弱锚定，仍在候选区间
        assertTrue(!result[0].prefillable)
        assertTrue(result[0].score >= CANDIDATE_SCORE)
    }

    @Test
    fun `保质期相对量能抽出来`() {
        val result = DateExtractor.extractShelfLife(listOf(line("保质期 12 个月")), 0)
        assertEquals(1, result.size)
        assertEquals(12, result[0].amount)
        assertEquals(com.jimmy.expiryreminder.core.ShelfLifeUnit.month, result[0].unit)
        assertTrue(result[0].prefillable)
    }

    @Test
    fun `保质期按天与年`() {
        val days = DateExtractor.extractShelfLife(listOf(line("保质期：45天")), 0)
        assertEquals(com.jimmy.expiryreminder.core.ShelfLifeUnit.day, days.first().unit)
        assertEquals(45, days.first().amount)

        val years = DateExtractor.extractShelfLife(listOf(line("保质期 3 年")), 0)
        assertEquals(com.jimmy.expiryreminder.core.ShelfLifeUnit.year, years.first().unit)
        assertEquals(3, years.first().amount)
    }

    @Test
    fun `净含量不会被当成保质期`() {
        assertTrue(DateExtractor.extractShelfLife(listOf(line("净含量 250 毫升")), 0).isEmpty())
    }

    @Test
    fun `英文 EXP 用词边界匹配`() {
        val hit = extract("EXP 2027-03-15")
        assertEquals(DateRole.expire, hit.first().role)
        // EXPORT 里不该命中 EXP
        assertTrue(extract("EXPORT 2027-03-15").first().role == DateRole.unknown)
    }

    @Test
    fun `全角数字与全角分隔符`() {
        val result = extract("生产日期 ２０２６－０３－１５")
        assertNotNull(result.firstOrNull())
        assertEquals("2026-03-15", result.first().isoDate)
    }
}
