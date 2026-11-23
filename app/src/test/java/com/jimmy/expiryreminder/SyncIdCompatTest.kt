package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.DATA_VERSION
import com.jimmy.expiryreminder.core.ExpiryData
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncIdCompatTest {

    private fun item(syncId: String) = ExpiryItem(
        id = "i1",
        name = "牛奶",
        category = "food",
        productionDate = "",
        shelfLife = 0,
        shelfLifeUnit = ShelfLifeUnit.month,
        expireAt = "2027-02-10",
        remindDays = listOf(7),
        quantity = 1,
        note = "",
        images = emptyList(),
        notifiedKeys = emptyList(),
        archived = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        spaceId = "",
        rev = 3L,
        syncId = syncId,
    )

    @Test
    fun `syncId 随 JSON 往返保持不变`() {
        val json = item("s-unified").toJson()
        assertEquals("s-unified", json.getString("syncId"))
        assertEquals("s-unified", ExpiryItem.fromJson(json)?.syncId)
    }

    @Test
    fun `旧数据缺 syncId 时读为空，由仓库派生补发`() {
        val json = JSONObject(item("").toJson().toString())
        json.remove("syncId")
        val restored = ExpiryItem.fromJson(json)
        assertEquals("", restored?.syncId)
        assertEquals("s_i1", "s_" + restored?.id)
    }

    @Test
    fun `数据版本已升到 v13（含 syncId、分类默认提醒、循环任务标记与定点提醒）`() {
        // v9 起 data.json 带 rev/revision 时钟，v10 加 syncId，
        // 合并上游 v9「分类默认提醒」后用 v11 统一标记，避免与两侧历史版本号混淆；
        // v12 加循环任务标记 repeatEnabled；v13 加循环任务定点提醒 reminderTime
        assertEquals(13, DATA_VERSION)
    }

    @Test
    fun `合并后 revision 时钟与分类默认提醒能同时落盘`() {
        val payload = ExpiryData(
            version = DATA_VERSION,
            items = listOf(item("s-unified")),
            categories = emptyList(),
            categoryColors = emptyMap(),
            revision = 7L,
            categoryRemindDefaults = mapOf("food" to listOf(3, 7)),
        ).toJson()
        assertEquals(7L, payload.getLong("revision"))
        val defaults = payload.getJSONObject("categoryRemindDefaults")
        assertEquals(2, defaults.getJSONArray("food").length())
        assertEquals(3, defaults.getJSONArray("food").getInt(0))
        // 单条记录自身的 rev 也不能被分类默认提醒字段挤掉
        assertEquals(3L, payload.getJSONArray("items").getJSONObject(0).getLong("rev"))
    }
}
