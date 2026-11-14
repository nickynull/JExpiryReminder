package com.jimmy.expiryreminder.notify

import android.Manifest
import android.app.AlarmManager
import android.app.AlarmManager.AlarmClockInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 到期提醒的通知能力：原生实现，走 AlarmManager。
 *
 * 可靠性要点（后台不弹通知的根因都在这里）：
 * 1. 精确闹钟权限：targetSdk ≥ 33 的新装应用在 Android 14+ 上默认拿不到
 *    SCHEDULE_EXACT_ALARM，只能退化成非精确闹钟，后台会被 Doze / 待机分桶推迟甚至吞掉。
 *    Manifest 里另声明了安装即授予的 USE_EXACT_ALARM，[canScheduleExact] 正常应为 true。
 * 2. 排程方式：[syncScheduled] 在精确闹钟可用且用户开启「闹钟式提醒」时用 setAlarmClock，
 *    优先级高于 setExactAndAllowWhileIdle，不受 Doze 影响；代价是状态栏常驻一个闹钟图标。
 * 3. 窗口滚动：一次最多排 [MAX_PLANS] 条，闹钟触发后由 [scheduleRefill] 延迟补排，
 *    保证长期不开 App 也能一直有后续提醒。
 */
object ReminderService {

    const val CHANNEL_ID = "expiry"
    const val CHANNEL_NAME = "到期提醒"

    /** 一次最多排入多少条，超出部分留给下次同步（按触发时间从近到远取）。 */
    const val MAX_PLANS = 60

    /** 与当前时间的最小间隔（毫秒）。 */
    const val MIN_LEAD_MS = 60_000L

    /** 开机重排用的占位 requestCode（不属于任何真实提醒）。 */
    const val BOOT_REQUEST_CODE = 1999999999

    /** 链式补排用的 requestCode，独立于提醒 id，cancelAll 不会动它。 */
    const val REFILL_REQUEST_CODE = 1999999998

    /** 触发后延迟多久补排：等同一时刻的其它闹钟都投递完，避免被 cancelAll 一起清掉。 */
    const val REFILL_DELAY_MS = 3 * 60_000L

    data class PlannedReminder(
        val key: String,
        val itemId: String,
        val at: LocalDateTime,
        val title: String,
        val body: String,
        val id: Int,
    )

    /** 设置页「后台可靠性」自检用的快照。 */
    data class ScheduledInfo(val count: Int, val nextAt: LocalDateTime?)

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

    /** 是否已加入电池优化白名单（未加入时国产 ROM 更容易把后台闹钟一起清掉）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    internal fun readNotifyTime(notifyTime: String): LocalTime {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(notifyTime) ?: return LocalTime.of(9, 0)
        val (h, m) = match.destructured
        val hour = h.toInt()
        val minute = m.toInt()
        if (hour > 23 || minute > 59) return LocalTime.of(9, 0)
        return LocalTime.of(hour, minute)
    }

    /** 当天推送时刻是否已过：没过就交给闹钟，过了才允许前台补发。 */
    internal fun isPastNotifyTime(
        notifyTime: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean = !now.toLocalTime().isBefore(readNotifyTime(notifyTime))

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
                if (Duration.between(now, fireAt).toMillis() < MIN_LEAD_MS) continue
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

    /**
     * 重排系统提醒（幂等）：先清后建。
     * - [useAlarmClock] 为 true 且精确闹钟可用时走 `setAlarmClock`，抗 Doze 能力最强；
     * - 否则退化为精确/非精确的 `setWhileIdle`（非精确时可能有几分钟漂移）。
     */
    fun syncScheduled(
        context: Context,
        store: ExpiryStore,
        notifyEnabled: Boolean,
        notifyTime: String,
        useAlarmClock: Boolean = true,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 先清掉全部旧闹钟
        cancelAll(context, am)

        if (!notifyEnabled || !hasNotificationPermission(context)) {
            saveScheduledIds(context, emptySet())
            saveScheduledInfo(context, ScheduledInfo(0, null))
            return
        }

        val plan = buildReminderPlan(store.items, notifyTime)
        val exact = canScheduleExact(context)
        val showIntent = launchAppPendingIntent(context)
        for (entry in plan) {
            val triggerAt = entry.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = alarmPendingIntent(context, entry.id, entry.key, entry.itemId, entry.body)
            try {
                when {
                    exact && useAlarmClock ->
                        am.setAlarmClock(AlarmClockInfo(triggerAt, showIntent), pi)
                    exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
        saveScheduledIds(context, plan.map { it.id }.toSet())
        saveScheduledInfo(context, ScheduledInfo(plan.size, plan.firstOrNull()?.at))
    }

    /**
     * 链式补排：闹钟触发后延迟 [REFILL_DELAY_MS] 再整包重排，让 60 条窗口向前滚动。
     *
     * 不能在 [AlarmReceiver] 里立刻重排——同一时刻往往有多条闹钟，立刻重排时
     * 后面那几条的触发时间已落在「小于最小提前量」的区间，会被 [buildReminderPlan]
     * 过滤掉，却又被 cancelAll 取消，等于把它们静默吃掉。
     */
    fun scheduleRefill(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + REFILL_DELAY_MS
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_REFILL)
        val pi = PendingIntent.getBroadcast(
            context, REFILL_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    // ------------------------------------------------------------ 排程快照

    private fun scheduledPrefs(context: Context) =
        context.getSharedPreferences("reminder", Context.MODE_PRIVATE)

    private fun saveScheduledIds(context: Context, ids: Set<Int>) {
        scheduledPrefs(context).edit()
            .putStringSet(KEY_SCHEDULED_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    private fun saveScheduledInfo(context: Context, info: ScheduledInfo) {
        scheduledPrefs(context).edit()
            .putInt(KEY_SCHEDULED_COUNT, info.count)
            .putLong(
                KEY_SCHEDULED_NEXT,
                info.nextAt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L,
            )
            .apply()
    }

    /** 设置页展示用：已排程条数与下一条的触发时间。 */
    fun scheduledInfo(context: Context): ScheduledInfo {
        val prefs = scheduledPrefs(context)
        val count = prefs.getInt(KEY_SCHEDULED_COUNT, 0)
        val next = prefs.getLong(KEY_SCHEDULED_NEXT, 0L)
        val nextAt = if (next > 0L) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(next), ZoneId.systemDefault())
        } else null
        return ScheduledInfo(count, nextAt)
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
    private const val KEY_SCHEDULED_COUNT = "scheduled_count"
    private const val KEY_SCHEDULED_NEXT = "scheduled_next_at"

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

    /** setAlarmClock 要求一个「点状态栏闹钟图标」的落地页；通知点击也复用它。 */
    private fun launchAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
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
            .setContentIntent(launchAppPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    /**
     * 前台兜底检查：补发「当天推送时刻已过、但该档位还没提醒过」的提醒。
     *
     * 闹钟正常投递时这些 key 已被 markNotified，不会重复；闹钟被系统或 ROM 吞掉时，
     * 打开 App 就能补上——这是后台排程失效时唯一的保底。
     */
    suspend fun runForegroundCheck(
        context: Context,
        store: ExpiryStore,
        notifyEnabled: Boolean,
        notifyTime: String,
    ): Int {
        if (!notifyEnabled || !hasNotificationPermission(context)) return 0
        // 还没到推送时刻：交给闹钟，避免一开 App 就提前弹
        if (!isPastNotifyTime(notifyTime)) return 0

        val today = Dates.todayISO()
        val hits = mutableListOf<String>()
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
            hits.add(key)
        }

        if (hits.isEmpty()) return 0
        val detail =
            if (hits.size == 1) firstBody else "${hits.size} 件物品即将到期，记得处理"
        showNotification(context, firstId, "到期提醒", detail)
        store.markNotified(context, hits)
        return hits.size
    }
}
