package com.jimmy.expiryreminder.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 品名抽取：靠字号选，靠黑名单挡行政信息。 */
class NameExtractorTest {

    private fun line(text: String, height: Int, top: Int = 0) = OcrLine(text, 0, top, 200, top + height)

    @Test
    fun `字号最大的行被选为品名`() {
        val lines = listOf(
            line("净含量 250mL", 12, 0),
            line("特仑苏纯牛奶", 42, 40),
            line("配料：生牛乳", 12, 90),
        )
        val result = NameExtractor.extract(lines)
        assertEquals("特仑苏纯牛奶", result.first().text)
    }

    @Test
    fun `行政信息行全部被排除`() {
        val lines = listOf(
            line("净含量 250mL", 30),
            line("配料 生牛乳", 30),
            line("生产商 某某乳业有限公司", 30),
            line("服务热线 400-000-0000", 30),
        )
        assertTrue(NameExtractor.extract(lines).isEmpty())
    }

    @Test
    fun `日期行不会被当成品名`() {
        assertTrue(NameExtractor.extract(listOf(line("2026-03-15", 40))).isEmpty())
        assertTrue(NameExtractor.extract(listOf(line("20260315", 40))).isEmpty())
    }

    @Test
    fun `过短或过长的文本被排除`() {
        assertTrue(NameExtractor.extract(listOf(line("奶", 40))).isEmpty())
        assertTrue(
            NameExtractor.extract(listOf(line("这是一个特别特别长的商品名称用来测试长度上限", 40))).isEmpty(),
        )
    }

    @Test
    fun `最多返回三条候选`() {
        val lines = (1..5).map { line("商品名称第${it}号", 40) }
        val result = NameExtractor.extract(lines)
        assertTrue(result.size <= 3)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `带规模标识的行不会被当成品名`() {
        // 「GB」「SC」这类标准号所在行是行政信息
        assertTrue(NameExtractor.extract(listOf(line("GB 25190 灭菌乳", 40))).isEmpty())
    }

    @Test
    fun `多行同字号时都是高置信候选`() {
        val lines = listOf(
            line("特仑苏", 40, 0),
            line("纯牛奶", 40, 45),
        )
        val result = NameExtractor.extract(lines)
        assertEquals(2, result.size)
        assertTrue(result.all { it.score >= 0.85 })
    }
}
