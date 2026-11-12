package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.SpaceDef
import com.jimmy.expiryreminder.core.SpaceInfo
import com.jimmy.expiryreminder.core.SpaceSync
import com.jimmy.expiryreminder.core.Spaces
import com.jimmy.expiryreminder.core.TransferPayload
import com.jimmy.expiryreminder.core.TransferWire
import com.jimmy.expiryreminder.core.hasSpaceNameConflict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpacesTest {

    private fun item(spaceId: String = Spaces.DEFAULT) = ExpiryItem(
        id = "i1", name = "牛奶", category = "food",
        productionDate = "2026-10-01", shelfLife = 60, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = "2026-11-30", remindDays = listOf(7, 1), quantity = 1, note = "",
        images = listOf(ExpiryImage("img_1", "images/img_1.jpg", "2026-10-01T00:00:00")),
        notifiedKeys = emptyList(), archived = false,
        createdAt = "2026-10-01T00:00:00", updatedAt = "2026-10-01T00:00:00",
        spaceId = spaceId,
    )

    @Test
    fun `spaceId 往返无损`() {
        for (id in listOf(Spaces.DEFAULT, "", "sp_abc")) {
            val parsed = ExpiryItem.fromJson(item(id).toJson())!!
            assertEquals(id, parsed.spaceId)
        }
    }

    @Test
    fun `缺 spaceId 的旧数据归默认空间`() {
        val raw = JSONObject().put("id", "i2").put("name", "鸡蛋")
        assertEquals(Spaces.DEFAULT, ExpiryItem.fromJson(raw)!!.spaceId)
    }

    @Test
    fun `显式空串表示不归属空间`() {
        val raw = JSONObject().put("id", "i3").put("name", "钥匙").put("spaceId", "")
        assertEquals("", ExpiryItem.fromJson(raw)!!.spaceId)
    }

    @Test
    fun `自定义空间解析与脏数据`() {
        val ok = SpaceInfo.fromJson(
            JSONObject().put("id", "sp_1").put("label", "爸妈家").put("color", "#67C23A"),
        )!!
        assertEquals("爸妈家", ok.label)
        assertEquals("#67C23A", ok.color)

        // 非法颜色回落灰色；空名字、占用内置 id 的空间直接丢弃
        assertEquals("#409EFF", SpaceInfo.fromJson(
            JSONObject().put("id", "sp_2").put("label", "公司").put("color", "red"),
        )!!.color)
        assertNull(SpaceInfo.fromJson(JSONObject().put("id", "sp_3").put("label", "  ")))
        assertNull(SpaceInfo.fromJson(JSONObject().put("id", Spaces.DEFAULT).put("label", "重复")))
        assertNull(SpaceInfo.fromJson("not a json"))
    }

    @Test
    fun `空间重名判断忽略大小写与空格`() {
        val list = listOf(SpaceDef(Spaces.DEFAULT, "我的空间", "#409EFF"), SpaceDef("sp_1", "Office", "#67C23A"))
        assertTrue(list.hasSpaceNameConflict(" office "))
        assertTrue(list.hasSpaceNameConflict("我的空间"))
        assertFalse(list.hasSpaceNameConflict("我的空间", exceptId = Spaces.DEFAULT))
        assertFalse(list.hasSpaceNameConflict("  "))
        // 「未分配」「全部空间」都是保留字
        assertTrue(list.hasSpaceNameConflict("未分配"))
        assertTrue(list.hasSpaceNameConflict("全部空间"))
    }

    @Test
    fun `全部空间是虚拟筛选 id，不会被解析成自定义空间`() {
        // 落盘数据里混进 __all__ 时必须丢掉，否则首页「全部空间」会与它混淆
        assertNull(SpaceInfo.fromJson(JSONObject().put("id", Spaces.ALL).put("label", "全部空间")))
        assertEquals("全部空间", Spaces.ALL_LABEL)
    }
}

class TransferTest {
    private fun item(name: String = "牛奶") = ExpiryItem(
        id = "i_$name", name = name, category = "food",
        productionDate = "2026-10-01", shelfLife = 60, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = "2026-11-30", remindDays = listOf(7), quantity = 2, note = "冷藏",
        images = listOf(ExpiryImage("img_1", "images/img_1.jpg", "2026-10-01T00:00:00")),
        notifiedKeys = listOf("k1"), archived = false,
        createdAt = "2026-10-01T00:00:00", updatedAt = "2026-10-01T00:00:00",
    )

    private fun zipWithManifest(json: JSONObject): File {
        val file = File.createTempFile("transfer-test", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `行协议按换行切分并忽略回车`() {
        val input = ByteArrayInputStream("{\"a\":1}\r\n{\"b\":2}\n".toByteArray())
        assertEquals("{\"a\":1}", TransferWire.readLine(input))
        assertEquals("{\"b\":2}", TransferWire.readLine(input))
        assertNull(TransferWire.readLine(input))
    }

    @Test
    fun `manifest 读取保留物品字段与发送方`() {
        val manifest = JSONObject()
            .put("app", TransferPayload.APP)
            .put("schema", TransferPayload.SCHEMA)
            .put("sender", "Jimmy 的手机")
            .put("sentAt", "2026-11-12T20:00:00+08:00")
            .put("items", JSONArray(listOf(item().toJson())))
        val batch = TransferPayload.readManifest(zipWithManifest(manifest))!!
        assertEquals("Jimmy 的手机", batch.sender)
        assertEquals(1, batch.items.size)
        assertEquals("牛奶", batch.items[0].name)
        assertEquals(listOf(7), batch.items[0].remindDays)
    }

    @Test
    fun `非本应用的包被拒绝`() {
        val manifest = JSONObject()
            .put("app", "OtherApp")
            .put("items", JSONArray(listOf(item().toJson())))
        assertNull(TransferPayload.readManifest(zipWithManifest(manifest)))
        assertNull(
            TransferPayload.readManifest(
                zipWithManifest(JSONObject().put("app", TransferPayload.APP)),
            ),
        )
    }

    @Test
    fun `预览文案：超过三件折叠`() {
        assertEquals("0 件物品", TransferPayload.previewText(emptyList()))
        assertEquals("牛奶（1 件）", TransferPayload.previewText(listOf(item("牛奶"))))
        val many = listOf("牛奶", "鸡蛋", "护照", "钥匙").map { item(it) }
        assertEquals("牛奶、鸡蛋、护照 等 4 件", TransferPayload.previewText(many))
    }
}

class SpaceSyncTest {

    private fun item(
        name: String,
        expireAt: String = "2026-12-01",
        updatedAt: String = "2026-11-10T10:00:00",
        spaceId: String = "sp_a",
        id: String = "i_${name}_$updatedAt",
    ) = ExpiryItem(
        id = id, name = name, category = "food",
        productionDate = "2026-10-01", shelfLife = 60, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = expireAt, remindDays = listOf(7), quantity = 1, note = "",
        images = emptyList(), notifiedKeys = emptyList(), archived = false,
        createdAt = "2026-10-01T00:00:00", updatedAt = updatedAt,
        spaceId = spaceId,
    )

    @Test
    fun `指纹忽略大小写与首尾空格，但区分到期日`() {
        assertEquals(
            SpaceSync.fingerprint(item("牛奶")),
            SpaceSync.fingerprint(item("  牛奶 ")),
        )
        assertEquals(
            SpaceSync.fingerprint(item("Milk")),
            SpaceSync.fingerprint(item("milk")),
        )
        assertNotEquals(
            SpaceSync.fingerprint(item("牛奶")),
            SpaceSync.fingerprint(item("牛奶", expireAt = "2026-12-02")),
        )
    }

    @Test
    fun `对方独有的物品按新增处理`() {
        val plan = SpaceSync.plan(
            localItems = emptyList(),
            incoming = listOf(item("鸡蛋")),
            spaceId = "sp_a",
        )
        assertEquals(1, plan.added)
        assertEquals(0, plan.updated)
        assertTrue(plan.decisions.isEmpty())
    }

    @Test
    fun `两边都有时较新的覆盖，较旧的跳过`() {
        val local = listOf(item("牛奶", updatedAt = "2026-11-10T10:00:00", id = "local_milk"))
        val newer = item("牛奶", updatedAt = "2026-11-11T09:00:00", id = "remote_new")
        val older = item("牛奶", updatedAt = "2026-11-09T09:00:00", id = "remote_old")

        val up = SpaceSync.plan(local, listOf(newer), "sp_a")
        assertEquals(1, up.updated)
        assertEquals("local_milk", up.decisions[newer.id]?.localId)
        assertEquals(TransferPayload.ImportAction.overwrite, up.decisions[newer.id]?.action)

        val down = SpaceSync.plan(local, listOf(older), "sp_a")
        assertEquals(0, down.updated)
        assertEquals(TransferPayload.ImportAction.skip, down.decisions[older.id]?.action)
    }

    @Test
    fun `只跟目标空间里的物品比对`() {
        // 本地同名物品在别的空间，不算冲突（同步是按空间进行的）
        val local = listOf(item("牛奶", spaceId = "sp_other", id = "other_milk"))
        val plan = SpaceSync.plan(local, listOf(item("牛奶", id = "remote_milk")), "sp_a")
        assertEquals(1, plan.added)
        assertTrue(plan.decisions.isEmpty())
    }
}
