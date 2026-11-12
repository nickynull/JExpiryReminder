package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.CategoryDef
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.hasNameConflict
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.normalizeImagePath
import com.jimmy.expiryreminder.core.normalizeRemindDays
import com.jimmy.expiryreminder.core.orderedBy
import com.jimmy.expiryreminder.core.remindDaysSummary
import com.jimmy.expiryreminder.notify.ReminderService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ModelsTest {

    private fun item(
        id: String = "i1",
        expireAt: String = "2026-12-01",
        remindDays: List<Int> = listOf(30, 7, 1, 0),
        archived: Boolean = false,
    ) = ExpiryItem(
        id = id, name = "牛奶", category = "food",
        productionDate = "2026-10-01", shelfLife = 60, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = expireAt, remindDays = remindDays, quantity = 2, note = "",
        images = listOf(ExpiryImage("img_1", "images/img_1.jpg", "2026-10-01T00:00:00")),
        notifiedKeys = emptyList(), archived = archived,
        createdAt = "2026-10-01T00:00:00", updatedAt = "2026-10-01T00:00:00",
    )

    @Test
    fun `toJson fromJson 往返无损`() {
        val original = item()
        val parsed = ExpiryItem.fromJson(original.toJson())!!
        assertEquals(original, parsed)
    }

    @Test
    fun `fromJson 容忍脏数据`() {
        val raw = JSONObject().put("id", "i2").put("name", "鸡蛋")
        val parsed = ExpiryItem.fromJson(raw)!!
        assertEquals("other", parsed.category) // 缺省分类
        assertEquals(emptyList<Int>(), parsed.remindDays)
        assertEquals(1, parsed.quantity)
        assertEquals(ShelfLifeUnit.month, parsed.shelfLifeUnit)
        assertNull(ExpiryItem.fromJson(JSONObject().put("name", "没 id 的")))
        assertNull(ExpiryItem.fromJson("not a json object"))
    }

    @Test
    fun `normalizeImagePath 拒绝目录穿越与绝对路径`() {
        assertEquals("images/img_1.jpg", normalizeImagePath("images/img_1.jpg"))
        assertEquals("images/img_1.jpg", normalizeImagePath("/images/img_1.jpg"))
        // 带模块前缀的路径原样保留
        assertEquals(
            "modules/expiry/images/img_1.jpg",
            normalizeImagePath("modules/expiry/images/img_1.jpg"),
        )
        assertEquals("", normalizeImagePath("../etc/passwd.jpg"))
        // 前导 / 会被剥掉，结果仍在数据目录内（与参考实现一致）
        assertEquals("abs/path/img.jpg", normalizeImagePath("/abs/path/img.jpg"))
        assertEquals("", normalizeImagePath("images/img.txt"))
        assertEquals("", normalizeImagePath(""))
        assertEquals("", normalizeImagePath(null))
    }

    @Test
    fun `computeExpireAt`() {
        assertEquals("2026-10-31", ExpiryCalc.computeExpireAt("2026-10-01", 30, ShelfLifeUnit.day))
        assertEquals("2026-11-01", ExpiryCalc.computeExpireAt("2026-10-01", 1, ShelfLifeUnit.month))
        assertEquals("2027-10-01", ExpiryCalc.computeExpireAt("2026-10-01", 1, ShelfLifeUnit.year))
        assertEquals("2026-01-31", ExpiryCalc.computeExpireAt("2025-10-31", 3, ShelfLifeUnit.month))
        // 条件不足
        assertEquals("", ExpiryCalc.computeExpireAt("bad", 1, ShelfLifeUnit.day))
        assertEquals("", ExpiryCalc.computeExpireAt("2026-10-01", 0, ShelfLifeUnit.day))
    }

    @Test
    fun `hashKey 稳定且在范围内`() {
        val a = ExpiryCalc.hashKey("2026-11-08:i1:7")
        val b = ExpiryCalc.hashKey("2026-11-08:i1:7")
        assertEquals(a, b)
        assertTrue(a in 1..2_000_000_000)
    }

    @Test
    fun `分类按 order 重排，未登记的排在最后`() {
        val all = listOf(
            CategoryDef("food", "食品", "#E6A23C"),
            CategoryDef("medicine", "药品", "#F56C6C"),
            CategoryDef("c_late", "临时", "#67C23A"),
        )
        // 自定义分类被拖到最前
        assertEquals(
            listOf("c_late", "medicine", "food"),
            all.orderedBy(listOf("c_late", "medicine", "food")).map { it.id },
        )
        // order 只登记了部分 id：没登记的保持原相对顺序排最后
        assertEquals(
            listOf("medicine", "food", "c_late"),
            all.orderedBy(listOf("medicine")).map { it.id },
        )
        // order 里有脏 id 时忽略；order 为空时原样返回
        assertEquals(
            listOf("food", "medicine", "c_late"),
            all.orderedBy(listOf("ghost")).map { it.id },
        )
        assertEquals(all.map { it.id }, all.orderedBy(emptyList()).map { it.id })
    }

    @Test
    fun `分类重名判断忽略首尾空格与大小写，改名时排除自己`() {
        val all = listOf(
            CategoryDef("food", "食品", "#E6A23C"),
            CategoryDef("c1", "Medicine", "#67C23A"),
        )
        assertTrue(all.hasNameConflict(" 食品 "))
        assertTrue(all.hasNameConflict("medicine"))
        // 名字两侧带空格也要正常比对
        assertFalse(all.hasNameConflict("  药品  "))
        // 改名：自己跟自己不算重名
        assertFalse(all.hasNameConflict("食品", exceptId = "food"))
        assertTrue(all.hasNameConflict("食品", exceptId = "c1"))
        // 空名交给调用方判空，不当成重名
        assertFalse(all.hasNameConflict("   "))
    }

    @Test
    fun `remindDaysSummary 输出提前档位`() {
        assertEquals("提前 30/7/1 天", remindDaysSummary(listOf(1, 30, 7)))
        assertEquals("提前 30/7/1 天 · 当天", remindDaysSummary(listOf(0, 1, 7, 30)))
        assertEquals("提前 7 天", remindDaysSummary(listOf(7)))
        assertEquals("当天提醒", remindDaysSummary(listOf(0)))
        assertNull(remindDaysSummary(emptyList()))
    }

    @Test
    fun `自定义档位同样进摘要`() {
        assertEquals("提前 45/7/1 天", remindDaysSummary(listOf(7, 45, 1)))
        assertEquals("提前 120/45 天 · 当天", remindDaysSummary(listOf(45, 0, 120)))
    }

    @Test
    fun `normalizeRemindDays 去重排序并丢弃越界值`() {
        assertEquals(listOf(45, 7, 1, 0), normalizeRemindDays(listOf(7, 45, 7, 1, 0)))
        // 负数（脏数据）与超过上限的天数直接丢掉
        assertEquals(listOf(365, 3), normalizeRemindDays(listOf(-1, 3, 366, 365)))
        assertEquals(emptyList<Int>(), normalizeRemindDays(listOf(-5, 400)))
    }

    @Test
    fun `mergeValue 带 id 数组按 id 覆盖`() {
        val base = JSONObject()
            .put("version", 5)
            .put(
                "items",
                JSONArray()
                    .put(JSONObject().put("id", "i1").put("name", "旧名"))
                    .put(JSONObject().put("id", "i2").put("name", "保留")),
            )
        val incoming = JSONObject()
            .put(
                "items",
                JSONArray().put(JSONObject().put("id", "i1").put("name", "新名")),
            )
            .put("categoryColors", JSONObject().put("food", "#67C23A"))

        val merged = ExpiryStore.mergeValue(base, incoming) as JSONObject
        val items = merged.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("新名", items.getJSONObject(0).getString("name"))
        assertEquals("保留", items.getJSONObject(1).getString("name"))
        assertEquals("#67C23A", merged.getJSONObject("categoryColors").getString("food"))
        assertEquals(5, merged.getInt("version"))
    }

    @Test
    fun `mergeValue 无 id 数组直接取新值`() {
        val base = JSONArray().put("a").put("b")
        val incoming = JSONArray().put("c")
        val merged = ExpiryStore.mergeValue(base, incoming) as JSONArray
        assertEquals(1, merged.length())
        assertEquals("c", merged.getString(0))
    }
}
