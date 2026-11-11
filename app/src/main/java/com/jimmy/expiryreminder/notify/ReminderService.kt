package com.jimmy.expiryreminder.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 到期提醒的通知能力：原生实现，直接走 AlarmManager 精确闹钟。
 */
object ReminderService {

    const val CHANNEL_ID = "expiry"
    const val CHANNEL_NAME = "到期提醒"

    /** 一次最多排入多少条，超出部分留给下次同步（按触发时间从近到远取）。 */
    const val MAX_PLANS = 60

    /** 与当前时间的最小间隔（毫秒）。 */
    const val MIN_LEAD_MS = 60_000L

    data class PlannedReminder(
        val key: String,
        val itemId: String,
        val at: LocalDateTime,
        val title: String,
        val body: String,
        val id: Int,
    )

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "物品临近到期时的提醒" }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    private fun readNotifyTime(notifyTime: String): LocalTime {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(notifyTime) ?: return LocalTime.of(9, 0)
        val (h, m) = match.destructured
        val hour = h.toInt()
        val minute = m.toInt()
        if (hour > 23 || minute > 59) return LocalTime.of(9, 0)
        return LocalTime.of(hour, minute)
    }

    private fun describe(item: ExpiryItem, days: Int): String {
        val when_ = when (days) {
            0 -> "今天到期"
            1 -> "明天到期"
            else -> "还有 $days 天到期"
        }
        val count = if (item.quantity > 1) "（${item.quantity} 件）" else ""
        return "${item.name} $when_$count"
    }

    /** 计算未来一段时间内所有需要系统提醒的计划。
     *  已过期、时间点已过的档位都不排。 */
    fun buildReminderPlan(
        items: List<ExpiryItem>,
        notifyTime: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<PlannedReminder> {
        val time = readNotifyTime(notifyTime)
        val drafts = mutableListOf<PlannedReminder>()

        for (item in items) {
            if (item.archived || !Dates.isValidISODate(item.expireAt)) continue
            val remaining = Dates.daysUntil(item.expireAt, now.toLocalDate()) ?: continue
            if (remaining < 0) continue

            val levels = item.remindDays.filter { it >= 0 }.distinct()
            for (days in levels) {
                val fireDate = Dates.addDays(item.expireAt, -days)
                val fireAt = Dates.parseISO(fireDate)?.atTime(time) ?: continue
                if (java.time.Duration.between(now, fireAt).toMillis() < MIN_LEAD_MS) continue
                drafts.add(
                    PlannedReminder(
                        key = "$fireDate:${item.id}:$days",
                        itemId = item.id,
                        at = fireAt,
                        title = "到期提醒",
                        body = describe(item, days),
                        id = 0,
                    )
                )
            }
        }

        drafts.sortWith(compareBy({ it.at }, { it.key }))

        val used = mutableSetOf<Int>()
        return drafts.take(MAX_PLANS).map { draft ->
            var id = ExpiryCalc.hashKey(draft.key)
            while (id in used) {
                id = if (id >= 2000000000) 1 else id + 1
            }
            used.add(id)
            draft.copy(id = id)
        }
    }

    /** 重排系统提醒（幂等）：先清后建。全部走精确闹钟；
     *  精确闹钟权限缺失时退化为非精确（仍能触发，只是可能有几分钟漂移）。 */
    fun syncScheduled(context: Context, store: ExpiryStore, notifyEnabled: Boolean, notifyTime: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 先清掉全部旧闹钟
        cancelAll(context, am)

        if (!notifyEnabled || !hasNotificationPermission(context)) {
            saveScheduledIds(context, emptySet())
            return
        }

        val plan = buildReminderPlan(store.items, notifyTime)
        val exact = canScheduleExact(context)
        for (entry in plan) {
            val triggerAt = entry.at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = alarmPendingIntent(context, entry.id, entry.key, entry.itemId, entry.body)
            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
        saveScheduledIds(context, plan.map { it.id }.toSet())
    }

    private fun scheduledPrefs(context: Context) =
        context.getSharedPreferences("reminder", Context.MODE_PRIVATE)

    private fun saveScheduledIds(context: Context, ids: Set<Int>) {
        scheduledPrefs(context).edit()
            .putStringSet(KEY_SCHEDULED_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    private fun cancelAll(context: Context, am: AlarmManager) {
        // 闹钟按通知 id 排程；重排前把上次排过的 id 全部取消（含已被删除物品的孤儿闹钟）
        val saved = scheduledPrefs(context).getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
        for (raw in saved) {
            val id = raw.toIntOrNull() ?: continue
            am.cancel(alarmPendingIntent(context, id, "", "", ""))
        }
        am.cancel(alarmPendingIntent(context, BOOT_REQUEST_CODE, "", "", ""))
    }

    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    const val BOOT_REQUEST_CODE = 1999999999

    private fun alarmPendingIntent(
        context: Context, id: Int, key: String, itemId: String, body: String,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_KEY, key)
            .putExtra(AlarmReceiver.EXTRA_ITEM_ID, itemId)
            .putExtra(AlarmReceiver.EXTRA_ID, id)
            .putExtra(AlarmReceiver.EXTRA_BODY, body)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知 id（同时是闹钟 requestCode）。 */
    fun showNotification(context: Context, id: Int, title: String, body: String) {
        if (!hasNotificationPermission(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.jimmy.expiryreminder.R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    /** 前台兜底检查：扫描一次到期提醒，命中档位且当天未提醒过才推。
     *  排程正常时让位（避免重复推送），仅在精确闹钟不可用时作为兜底。 */
    suspend fun runForegroundCheck(
        context: Context,
        store: ExpiryStore,
        notifyEnabled: Boolean,
    ): Int {
        if (!notifyEnabled || !hasNotificationPermission(context)) return 0
        if (canScheduleExact(context)) return 0

        val today = Dates.todayISO()
        val hits = mutableListOf<Pair<String, Int>>() // key to notifyId
        var firstId = 0
        var firstBody = ""

        for (item in store.activeItems()) {
            val days = Dates.daysUntil(item.expireAt) ?: continue
            if (days < 0 || days !in item.remindDays) continue
            val key = store.reminderKey(item, days, today)
            if (key in item.notifiedKeys) continue
            val id = ExpiryCalc.hashKey(key)
            if (hits.isEmpty()) {
                firstId = id
                firstBody = describe(item, days)
            }
            hits.add(key to id)
        }

        if (hits.isEmpty()) return 0
        val detail =
            if (hits.size == 1) firstBody else "${hits.size} 件物品即将到期，记得处理"
        showNotification(context, firstId, "到期提醒", detail)
        store.markNotified(context, hits.map { it.first })
        return hits.size
    }

    fun todayKeySuffix(): String = Dates.todayISO()
}
