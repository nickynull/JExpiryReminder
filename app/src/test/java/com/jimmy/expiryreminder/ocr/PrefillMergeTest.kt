package com.jimmy.expiryreminder.ocr

import com.jimmy.expiryreminder.core.ShelfLifeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 跨图合并 + 预填映射。多图是这次流程的核心，合并规则错了会直接填错。 */
class PrefillMergeTest {

    private fun name(text: String, score: Double, imageIndex: Int) =
        NameCandidate(text, score, 1, imageIndex)

    private fun date(
        iso: String,
        role: DateRole,
        score: Double,
        imageIndex: Int,
    ) = DateCandidate(iso, iso, role, score, "", imageIndex, 1)

    @Test
    fun `两张图给出同一品名时标记多图一致并加分`() {
        val merged = ResultMerger.merge(
            listOf(
                listOf(name("特仑苏纯牛奶", 0.9, 0)),
                listOf(name("特仑苏纯牛奶", 0.85, 1)),
            ),
            emptyList(),
            emptyList(),
        )
        assertEquals(1, merged.names.size)
        assertEquals(2, merged.names[0].imageCount)
        assertTrue("跨图一致应当加分", merged.names[0].score > 0.9)
    }

    @Test
    fun `编辑距离两字以内视为同一候选`() {
        val merged = ResultMerger.merge(
            listOf(listOf(name("特仑苏纯牛奶", 0.9, 0)), listOf(name("特仑苏纯牛乳", 0.8, 1))),
            emptyList(),
            emptyList(),
        )
        assertEquals(1, merged.names.size)
    }

    @Test
    fun `差异过大的品名保留为两个候选`() {
        val merged = ResultMerger.merge(
            listOf(listOf(name("特仑苏纯牛奶", 0.9, 0)), listOf(name("蒙牛纯甄酸牛奶", 0.85, 1))),
            emptyList(),
            emptyList(),
        )
        assertEquals(2, merged.names.size)
    }

    @Test
    fun `跨图重复的日期加分并记录出现次数`() {
        val merged = ResultMerger.merge(
            emptyList(),
            listOf(
                listOf(date("2027-03-15", DateRole.expire, 0.85, 0)),
                listOf(date("2027-03-15", DateRole.expire, 0.85, 1)),
            ),
            emptyList(),
        )
        assertEquals(1, merged.dates.size)
        assertEquals(2, merged.dates[0].imageCount)
        assertTrue(merged.dates[0].score > 0.85)
    }

    @Test
    fun `生产日期加保质期可以推算出到期日`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(
                names = listOf(name("特仑苏纯牛奶", 0.9, 0)),
                dates = listOf(date("2026-03-15", DateRole.produce, 0.85, 0)),
                shelfLives = listOf(ShelfLifeCandidate(6, ShelfLifeUnit.month, 0.95, "", 0)),
            ),
        )
        assertEquals("2026-03-15", draft.productionDate)
        assertEquals(6, draft.shelfLife)
        assertEquals(ShelfLifeUnit.month, draft.shelfLifeUnit)
        assertEquals("2026-09-15", draft.expireAt)
        assertTrue("能推算时不该锁死到期日", !draft.manualExpire)
    }

    @Test
    fun `只识别出有效期至时直接填到期日并锁定`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(
                dates = listOf(date("2027-03-15", DateRole.expire, 0.85, 0)),
            ),
        )
        assertEquals("2027-03-15", draft.expireAt)
        assertTrue(draft.manualExpire)
        assertEquals("", draft.productionDate)
    }

    @Test
    fun `有效期至加生产日期时反推有效期`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(
                dates = listOf(
                    date("2026-03-15", DateRole.produce, 0.85, 0),
                    date("2026-09-15", DateRole.expire, 0.85, 0),
                ),
            ),
        )
        assertEquals("2026-03-15", draft.productionDate)
        assertEquals("2026-09-15", draft.expireAt)
        assertEquals(6, draft.shelfLife)
        assertEquals(ShelfLifeUnit.month, draft.shelfLifeUnit)
        assertTrue(draft.manualExpire)
    }

    @Test
    fun `两个日期互相矛盾时都留空`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(
                dates = listOf(
                    date("2027-03-15", DateRole.produce, 0.85, 0),
                    date("2026-09-15", DateRole.expire, 0.85, 0),
                ),
            ),
        )
        assertEquals("", draft.productionDate)
        assertEquals("", draft.expireAt)
    }

    @Test
    fun `只有生产日期时其余留空`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(dates = listOf(date("2026-03-15", DateRole.produce, 0.85, 0))),
        )
        assertEquals("2026-03-15", draft.productionDate)
        assertEquals("", draft.expireAt)
        assertEquals(0, draft.shelfLife)
    }

    @Test
    fun `低于阈值的日期不参与预填`() {
        val draft = PrefillMapper.toDraft(
            ResultMerger.Merged(dates = listOf(date("2026-03-15", DateRole.produce, 0.55, 0))),
        )
        assertEquals("", draft.productionDate)
        // 但候选仍然保留，供用户在添加页点选
        assertEquals(1, draft.dateCandidates.size)
    }

    @Test
    fun `反推有效期优先整年`() {
        val inferred = PrefillMapper.inferShelfLife(
            LocalDate.of(2026, 1, 10),
            LocalDate.of(2028, 1, 10),
        )
        assertEquals(2 to ShelfLifeUnit.year, inferred)
    }

    @Test
    fun `反推有效期按整月`() {
        val inferred = PrefillMapper.inferShelfLife(
            LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 4, 10),
        )
        assertEquals(3 to ShelfLifeUnit.month, inferred)
    }

    @Test
    fun `反推不出整月时按天`() {
        val inferred = PrefillMapper.inferShelfLife(
            LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 1, 25),
        )
        assertEquals(15 to ShelfLifeUnit.day, inferred)
    }

    @Test
    fun `品名能推断出内置分类`() {
        assertEquals("food", CategoryGuesser.guess("特仑苏纯牛奶"))
        assertEquals("medicine", CategoryGuesser.guess("维生素C胶囊"))
        assertEquals("cosmetic", CategoryGuesser.guess("保湿精华乳"))
        assertEquals("document", CategoryGuesser.guess("普通护照"))
    }

    @Test
    fun `推断不出分类时返回空`() {
        assertEquals(null, CategoryGuesser.guess("蓝色收纳盒"))
    }
}
