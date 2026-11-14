package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.notify.ReminderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReminderPlanTest {

    private fun item(
        id: String,
        expireAt: String,
        remindDays: List<Int> = listOf(30, 7, 1, 0),
        archived: Boolean = false,
    ) = ExpiryItem(
        id = id, name = "物品$id", category = "food",
        productionDate = "", shelfLife = 0, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = expireAt, remindDays = remindDays, quantity = 1, note = "",
        images = emptyList<ExpiryImage>(), notifiedKeys = emptyList(), archived = archived,
        createdAt = "2026-01-01T00:00:00", updatedAt = "2026-01-01T00:00:00",
    )

    @Test
    fun `按档位生成计划并按时间排序`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-12-11"), item("b", "2026-11-12")),
            "09:00",
            now,
        )
        // a: 12-11 的 30/7/1/0 档 → 11-11(已过 09:00)、12-04、12-10、12-11
        // b: 11-12 的 30/7/1/0 档 → 10-13(过去)、11-05(过去)、11-11(过去)、11-12
        // 可用的应是 b 的 0 档 + a 的 7/1/0 档
        assertEquals(
            listOf("2026-11-12:b:0", "2026-12-04:a:7", "2026-12-10:a:1", "2026-12-11:a:0"),
            plan.map { it.key },
        )
        assertTrue(plan.zipWithNext().all { (x, y) -> x.at <= y.at })
    }

    @Test
    fun `已过期与归档物品不排`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("expired", "2026-11-01"), item("arch", "2026-12-01", archived = true)),
            "09:00", now,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `自定义天数照常排程`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2027-01-11", remindDays = listOf(45))), "09:00", now,
        )
        // 2027-01-11 往前 45 天 = 2026-11-27
        assertEquals(listOf("2026-11-27:a:45"), plan.map { it.key })
    }

    @Test
    fun `重复档位去重`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-12-11", remindDays = listOf(7, 7, 1))),
            "09:00", now,
        )
        assertEquals(2, plan.size)
    }

    @Test
    fun `通知 id 不冲突`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val items = (1..20).map { item("id$it", "2026-12-01") }
        val plan = ReminderService.buildReminderPlan(items, "09:00", now)
        assertEquals(plan.size, plan.map { it.id }.distinct().size)
    }

    @Test
    fun `notifyTime 非法时回退 0900`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-11-12")), "25:99", now,
        )
        // 09:00 的 0 天档 = 11-12 09:00
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), plan.first().at)
    }

    @Test
    fun `前台补发只在推送时刻之后生效`() {
        // 早于推送时刻：交给闹钟，别一开 App 就提前弹
        assertFalse(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 8, 59)))
        // 到点及以后：允许补发
        assertTrue(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 9, 0)))
        assertTrue(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 23, 30)))
        // 非法时刻按 09:00 处理
        assertTrue(ReminderService.isPastNotifyTime("25:99", LocalDateTime.of(2026, 11, 11, 10, 0)))
        assertFalse(ReminderService.isPastNotifyTime("25:99", LocalDateTime.of(2026, 11, 11, 8, 0)))
    }
}
