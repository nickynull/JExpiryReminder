package com.jimmy.expiryreminder.notify

import android.Manifest
import android.app.AlarmManager
import android.app.AlarmManager.AlarmClockInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.lang.reflect.Modifier

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
    const val MAX_PLANS = 200

    /** 与当前时间的最小间隔（毫秒）。小于它就改走「立即补发」，不再直接丢弃。 */
    const val MIN_LEAD_MS = 10_000L

    /** 开机重排用的占位 requestCode（不属于任何真实提醒）。 */
    const val BOOT_REQUEST_CODE = 1999999999

    /** 链式补排用的 requestCode，独立于提醒 id，cancelAll 不会动它。 */
    const val REFILL_REQUEST_CODE = 1999999998

    /** 触发后延迟多久补排：等同一时刻的其它闹钟都投递完，避免被 cancelAll 一起清掉。 */
    const val REFILL_DELAY_MS = 45_000L

    /** 流体云 / Live Update 汇总通知的固定 id。 */
    const val LIVE_SUMMARY_ID = 9100

    /** 主闹钟后 90 秒的备份闹钟：ROM 吞掉主闹钟时兜底，去重键保证不会重复打扰。 */
    const val BACKUP_DELAY_MS = 90_000L

    /** 「通知 id → 物品 id」映射的偏好名（归档/删除时按物品撤销通知用）。 */
    private const val PREFS_POSTED = "reminder_posted"
    private const val KEY_POSTED = "posted"

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

    /** 流体云 / 系统实时动态自检快照：用于解释「为什么只有通知栏提醒」。 */
    data class LiveUpdateStatus(
        val supported: Boolean,
        val canPost: Boolean,
        val active: Boolean,
        val requested: Boolean,
        val promoted: Boolean,
        val promotable: Boolean?,
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
        val prefix = if (item.repeatEnabled) "循环任务「${item.name}」本周期" else item.name
        return "$prefix $when_$count"
    }

    /** 物品的定点提醒时刻；未设置或格式非法返回 null（沿用全局推送时刻）。 */
    internal fun itemFixedTime(item: ExpiryItem): LocalTime? =
        item.reminderTime.takeIf { it.isNotBlank() && item.repeatEnabled }
            ?.let { fixedTimeOrNull(it) }

    /** 定点时刻的合法版本：非法字符串返回 null 而不是回退 09:00。 */
    internal fun fixedTimeOrNull(value: String): LocalTime? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(value.trim()) ?: return null
        val (h, m) = match.destructured
        val hour = h.toInt()
        val minute = m.toInt()
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * 物品实际参与排程的提醒时刻：设了定点用定点，否则用全局推送时刻。
     */
    internal fun itemNotifyTime(item: ExpiryItem, notifyTime: String): LocalTime =
        itemFixedTime(item) ?: readNotifyTime(notifyTime)

    /**
     * 物品实际参与排程的档位：设了定点的循环任务若没勾任何档位，默认按「当天」触发，
     * 否则定点时间永远不会被排程吃到。
     */
    internal fun effectiveRemindDays(item: ExpiryItem): List<Int> {
        val levels = item.remindDays.filter { it >= 0 }.distinct()
        if (levels.isEmpty() && itemFixedTime(item) != null) return listOf(0)
        return levels
    }

    /** 提醒标题：定点任务用「定点提醒」，其余沿用「到期提醒」。 */
    private fun notifyTitle(item: ExpiryItem): String =
        if (itemFixedTime(item) != null) "定点提醒" else "到期提醒"

    /**
     * 提醒正文：定点任务在到期当天用「该{name}啦」这类口语化短句，
     * 提前 N 天的定点档位与普通提醒保持 describe 的信息量（含剩余天数）。
     */
    private fun notifyBody(item: ExpiryItem, days: Int): String =
        if (itemFixedTime(item) != null && days == 0) "该${item.name.trim()}啦"
        else describe(item, days)

    /** 计算未来一段时间内所有需要系统提醒的计划。
     *  已过期、时间点已过的档位都不排。 */
    fun buildReminderPlan(
        items: List<ExpiryItem>,
        notifyTime: String,
        now: LocalDateTime = LocalDateTime.now(),
        /** 是否把「今天已过推送时刻、但还没提醒过」的档位转成 5 秒后的立即提醒。 */
        includeMissed: Boolean = false,
    ): List<PlannedReminder> {
        val drafts = mutableListOf<PlannedReminder>()

        for (item in items) {
            if (item.archived || !Dates.isValidISODate(item.expireAt)) continue
            val remaining = Dates.daysUntil(item.expireAt, now.toLocalDate()) ?: continue
            if (remaining < 0) continue

            val levels = effectiveRemindDays(item)
            // 定点提醒物品用自己的时刻，普通物品沿用全局推送时刻
            val fireTime = itemNotifyTime(item, notifyTime)
            val title = notifyTitle(item)
            for (days in levels) {
                val fireDate = Dates.addDays(item.expireAt, -days)
                val fireAt = Dates.parseISO(fireDate)?.atTime(fireTime) ?: continue
                val key = "$fireDate:${item.id}:$days"
                if (Duration.between(now, fireAt).toMillis() < MIN_LEAD_MS) {
                    // 今天已过推送时刻但这一档还没提醒过：5 秒后立即补发。
                    // 以前这里直接 continue，下午新增的当天到期物品会整天收不到提醒。
                    if (includeMissed && fireDate == now.toLocalDate().toString() &&
                        key !in item.notifiedKeys
                    ) {
                        drafts.add(
                            PlannedReminder(
                                key = key,
                                itemId = item.id,
                                at = now.plusSeconds(5),
                                title = title,
                                body = notifyBody(item, days),
                                id = 0,
                            ),
                        )
                    }
                    continue
                }
                drafts.add(
                    PlannedReminder(
                        key = key,
                        itemId = item.id,
                        at = fireAt,
                        title = title,
                        body = notifyBody(item, days),
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
        liveUpdateEnabled: Boolean = false,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 先清掉全部旧闹钟
        cancelAll(context, am)

        if (!notifyEnabled || !hasNotificationPermission(context)) {
            saveScheduledIds(context, emptySet())
            saveScheduledInfo(context, ScheduledInfo(0, null))
            cancelLiveSummary(context)
            return
        }

        // includeMissed：把今天已过点但还没提醒的档位补成 5 秒后的即时提醒，
        // 解决「下午新增当天到期物品，当天收不到通知」的问题。
        val plan = buildReminderPlan(store.items, notifyTime, includeMissed = true)
        val exact = canScheduleExact(context)
        val showIntent = launchAppPendingIntent(context)
        for (entry in plan) {
            val triggerAt = entry.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = alarmPendingIntent(context, entry.id, entry.key, entry.itemId, entry.title, entry.body)
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
            // 48 小时内再排一条 +90s 的备份闹钟：主闹钟被 ROM 省电策略吞掉时兜底。
            // requestCode 用负数，和真实提醒 id（正数）天然隔离；重复触发由去重键拦掉。
            if (triggerAt - System.currentTimeMillis() <= 48 * 3600_000L) {
                val backup = alarmPendingIntent(context, -entry.id, entry.key, entry.itemId, entry.title, entry.body)
                val backupAt = triggerAt + BACKUP_DELAY_MS
                try {
                    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupAt, backup)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupAt, backup)
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupAt, backup)
                }
            }
        }
        saveScheduledIds(context, plan.map { it.id }.toSet())
        saveScheduledInfo(context, ScheduledInfo(plan.size, plan.firstOrNull()?.at))

        // 流体云 / Live Update：7 天内的到期汇总，Android 16+ 交给系统提升为胶囊卡片
        repostLiveSummary(context, store, liveUpdateEnabled)
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
            am.cancel(alarmPendingIntent(context, id, "", "", "", ""))
            // 备份闹钟用负 requestCode，别漏取消，否则重排后旧备份会在错误时间弹
            am.cancel(alarmPendingIntent(context, -id, "", "", "", ""))
        }
        am.cancel(alarmPendingIntent(context, BOOT_REQUEST_CODE, "", "", "", ""))
    }

    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_SCHEDULED_COUNT = "scheduled_count"
    private const val KEY_SCHEDULED_NEXT = "scheduled_next_at"

    private fun alarmPendingIntent(
        context: Context, id: Int, key: String, itemId: String, title: String, body: String,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_KEY, key)
            .putExtra(AlarmReceiver.EXTRA_ITEM_ID, itemId)
            .putExtra(AlarmReceiver.EXTRA_ID, id)
            .putExtra(AlarmReceiver.EXTRA_TITLE, title)
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

    /**
     * 通知 id（同时是闹钟 requestCode）。
     *
     * [itemId] 非空时把「通知 id → 物品」记进偏好，归档/删除后可以按物品撤销通知栏里
     * 已经发出去的那条（id 是 hash，反查不回来，只能落映射）。
     */
    fun showNotification(
        context: Context,
        id: Int,
        title: String,
        body: String,
        itemId: String? = null,
    ) {
        if (!hasNotificationPermission(context)) return
        rememberPosted(context, id, itemId)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.jimmy.expiryreminder.R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(launchAppPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        // 锁屏可见性：默认公开（解锁桌面之外也能一眼看到哪件快过期）；
        // 开启「锁屏隐藏详情」后只给系统一条脱敏公开版，具体物品名留在解锁后
        if (SettingsStore.get().hideNotificationDetail.value) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(redactedNotification(context))
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        manager.notify(id, builder.build())
    }

    /** 锁屏上代替真实内容的那条：不含物品名、数量、备注与任何可推断信息。 */
    private fun redactedNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("到期提醒")
            .setContentText("点击查看详情")
            .setContentIntent(launchAppPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** 记录「通知 id → 物品 id」，供归档/删除后撤销通知。一个 id 只保留最新一条映射。 */
    private fun rememberPosted(context: Context, id: Int, itemId: String?) {
        if (itemId.isNullOrEmpty()) return
        val sp = context.getSharedPreferences(PREFS_POSTED, Context.MODE_PRIVATE)
        val entries = sp.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { it.substringBefore('|') == id.toString() }
        entries.add("$id|$itemId")
        sp.edit().putStringSet(KEY_POSTED, entries).apply()
    }

    /**
     * 撤销这些物品在通知栏里还挂着的提醒。
     * 归档物品不该再提醒，删除物品更不该留着一条指向已删数据的通知——
     * 两者都走这里（闹钟本身由随后的 [syncScheduled] 整包重排清掉）。
     */
    fun cancelNotificationsFor(context: Context, itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        val sp = context.getSharedPreferences(PREFS_POSTED, Context.MODE_PRIVATE)
        val entries = sp.getStringSet(KEY_POSTED, emptySet()).orEmpty()
        if (entries.isEmpty()) return
        val targets = itemIds.toSet()
        val hit = entries.filter { it.substringAfter('|') in targets }
        if (hit.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (entry in hit) {
            entry.substringBefore('|').toIntOrNull()?.let { runCatching { manager.cancel(it) } }
        }
        sp.edit().putStringSet(KEY_POSTED, (entries - hit.toSet())).apply()
    }

    /** 只重发流体云汇总，不重排闹钟；设置页「重新发布」用这个，避免无谓地清建全部闹钟。 */
    fun repostLiveSummary(context: Context, store: ExpiryStore, enabled: Boolean) {
        if (!enabled) {
            cancelLiveSummary(context)
            return
        }
        val soon = liveSummaryCandidates(store.items)
        if (soon.isEmpty()) {
            cancelLiveSummary(context)
            return
        }
        val content = liveSummaryContent(soon)
        showLiveSummary(context, content)
    }

    /**
     * 流体云候选项筛选（纯函数，无副作用，可单测）。
     *
     * 倒计时物品维持原规则：到期日落在 [0, LIVE_WINDOW_DAYS] 天内即上云。
     *
     * 循环任务走独立规则：除窗口限制外，还必须命中用户设置的提醒档位
     * ([ExpiryItem.remindDays])才上云。原因是循环任务的到期日恒为「完成日 + 周期」，
     * 周期 ≤7 天时到期日永远落在 0..7 窗口内，若套用倒计时规则会长期常驻流体云；
     * 要求命中提醒档位后，上云时点与闹钟提醒保持一致——没设提醒天数的循环任务完全不上云，
     * 设了档位的只在对应天数当天上云。
     */
    internal fun liveSummaryCandidates(
        items: List<ExpiryItem>,
        now: LocalDate = LocalDate.now(),
    ): List<Pair<ExpiryItem, Int>> = items.mapNotNull { item ->
        if (item.archived || !Dates.isValidISODate(item.expireAt)) null
        else Dates.daysUntil(item.expireAt, now)?.takeIf { days ->
            days in 0..LIVE_WINDOW_DAYS &&
                (!item.repeatEnabled || days in item.remindDays)
        }?.let { item to it }
    }.sortedBy { it.second }

    /**
     * 流体云卡片的内容编排。
     *
     * 注意：不能再用自定义 RemoteViews 摆三栏——平台 `hasPromotableCharacteristics()`
     * 要求 `!containsCustomViews()`（见 Notification.java:3419），一旦设了
     * setCustomContentView 就永久失去 FLAG_PROMOTED_ONGOING，流体云直接退化成普通通知。
     * 所以布局交给系统模板，动效只能用系统支持的样式（这里用 ProgressStyle）。
     *
     * - [count]       7 天内到期件数；
     * - [days]        最近一件的剩余天数，同时驱动进度条与胶囊文案；
     * - [title]       主标题 = 物品名 + 到期状态（主体）；
     * - [subtitle]    副标题 = 其余物品摘要，单行省略；
     * - [capsuleText] 流体云胶囊文本。
     */
    data class LiveSummary(
        val count: Int,
        val days: Int,
        val title: String,
        val subtitle: String,
        val capsuleText: String,
    )

    /** 剩余天数的最简表述：今天 / 明天 / N 天。 */
    internal fun daysLabel(days: Int): String = when (days) {
        0 -> "今天"
        1 -> "明天"
        else -> "$days 天"
    }

    internal fun liveSummaryContent(soon: List<Pair<ExpiryItem, Int>>): LiveSummary {
        val (nearest, days) = soon.first()
        val next = soon.getOrNull(1)
        val subtitle = when {
            next == null -> "共 1 件物品，请及时处理"
            soon.size == 2 -> "另有 1 件：${next.first.name} ${daysLabel(next.second)}"
            else -> "另有 ${soon.size - 1} 件 · ${next.first.name} ${daysLabel(next.second)}"
        }
        return LiveSummary(
            count = soon.size,
            days = days,
            title = describe(nearest, days),
            subtitle = subtitle,
            // 胶囊宽度由这段文本决定：越短胶囊越窄，用最简表述
            capsuleText = daysLabel(days),
        )
    }

    /** 打开 Android 16+ 的「应用实时动态」授权页；不支持时退回应用通知设置。 */
    fun openLiveUpdateSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 读取系统侧实时动态能力，解释「已请求提升但没有被系统提升」的原因。 */
    fun liveUpdateStatus(context: Context): LiveUpdateStatus {
        val supported = Build.VERSION.SDK_INT >= 36
        val canPost = if (supported) {
            runCatching { NotificationManagerCompat.from(context).canPostPromotedNotifications() }
                .getOrDefault(false)
        } else false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = manager.activeNotifications.firstOrNull { it.id == LIVE_SUMMARY_ID }
        val notification = active?.notification
        val requested = if (supported && notification != null) {
            // compileSdk 36 尚未暴露 isRequestPromotedOngoing()，直接读平台 extras 键
            runCatching {
                notification.extras?.getBoolean("android.requestPromotedOngoing", false) ?: false
            }.getOrDefault(false)
        } else false
        val promoted = if (supported && notification != null) {
            (notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        } else false
        val promotable = if (notification == null) null else {
            runCatching {
                val method = Notification::class.java.getDeclaredMethod("hasPromotableCharacteristics")
                method.isAccessible = true
                val receiver = if (Modifier.isStatic(method.modifiers)) null else notification
                method.invoke(receiver) as? Boolean
            }.getOrNull()
        }
        return LiveUpdateStatus(supported, canPost, active != null, requested, promoted, promotable)
    }

    /**
     * 流体云 / Live Update 汇总：7 天内的到期物品合成一条常驻通知。
     *
     * **禁止使用自定义 RemoteViews**：平台 `hasPromotableCharacteristics()`
     * 要求 `!containsCustomViews()`（Notification.java:3419），只要设了
     * setCustomContentView / setCustomBigContentView 就永久失去 FLAG_PROMOTED_ONGOING，
     * 流体云退化为普通通知。DecoratedCustomViewStyle 也救不回来（不在
     * hasPromotableStyle 白名单内，且 containsCustomViews 仍为 true）。
     *
     * 布局交给系统模板，动效交给系统：[ProgressStyle] 是官方可提升样式，
     * 以 7 天窗口的「已消耗进度」呈现紧迫度，系统在数值变化时做平滑动画。
     *
     * 不放操作按钮：卡片本身单击即进应用，多一个按钮只会挤占内容宽度。
     *
     * ColorOS 16 起流体云对接原生 Android 16 Live Updates API。真机排查发现：
     * CATEGORY_REMINDER + 默认 PRIVATE 可见性只会被记录为「请求提升」，不会得到
     * FLAG_PROMOTED_ONGOING；改用 CATEGORY_STATUS + VISIBILITY_PUBLIC + 非彩色化后，
     * 真机已出现 PROMOTED_ONGOING。低版本或用户关闭实时动态时自动降级为普通通知。
     */
    fun showLiveSummary(context: Context, summary: LiveSummary) {
        if (!hasNotificationPermission(context)) return
        if (summary.count <= 0) {
            cancelLiveSummary(context)
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 流体云会展示在锁屏与状态栏：开启隐藏详情时只留「N 件即将到期」，
        // 物品名不外露；胶囊文本本身只有天数，可以保留
        val hideDetail = SettingsStore.get().hideNotificationDetail.value
        val title = if (hideDetail) "${summary.count} 件物品即将到期" else summary.title
        val subtitle = if (hideDetail) "点击查看清单" else summary.subtitle
        if (Build.VERSION.SDK_INT >= 36) {
            // androidx.core 1.17 已封装 36.1 的 promoted ongoing：内部写的就是平台 extras，
            // 比直接调平台类型化 setter 更稳（compileSdk 36 上平台方法不可见）。
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(buildProgressStyle(summary))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                // Live Update 规范偏向「正在进行的状态」，不用 REMINDER 类别；
                // 显式公开可见、关闭彩色化，避免被系统判定为不可提升
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColorized(false)
                .setContentIntent(launchAppPendingIntent(context))
                .setShortCriticalText(summary.capsuleText)
                .setRequestPromotedOngoing(true)
                .build()
            runCatching { manager.notify(LIVE_SUMMARY_ID, notification) }
                .onFailure { showNotification(context, LIVE_SUMMARY_ID, title, subtitle) }
        } else {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(launchAppPendingIntent(context))
                .build()
            runCatching { manager.notify(LIVE_SUMMARY_ID, notification) }
                .onFailure { showNotification(context, LIVE_SUMMARY_ID, title, subtitle) }
        }
    }

    /** 进度窗口：流体云只统计 7 天内到期的物品，进度条也按这个窗口算。 */
    private const val LIVE_WINDOW_DAYS = 7

    /** 进度单位：1 天 = 10 个单位。 */
    private const val UNITS_PER_DAY = 10

    /** 紧迫度锚点配色（与 App 内红/琥珀语义一致）。 */
    private const val COLOR_SAFE = 0xFF67C23A.toInt()   // 7 天：安全
    private const val COLOR_SOON = 0xFFE6A23C.toInt()   // 3 天：临近
    private const val COLOR_URGENT = 0xFFF56C6C.toInt() // 0 天：紧急

    /**
     * 系统进度样式：单段连续条，按 7 天窗口已消耗比例填充，颜色随剩余天数连续插值。
     *
     * **为什么不用多段做渐变**：系统把每个 [NotificationCompat.ProgressStyle.Segment]
     * 画成独立的圆角短条，段之间实测有 8px 硬断口（1440 宽屏像素测量），
     * 切成 10 段只会得到 10 个断开的色块。所以走单段：线是完整一条，
     * 颜色按 绿 → 琥珀 → 红 线性插值，随剩余天数平滑过渡。
     *
     * `setStyledByProgress(true)`（官方默认）让系统把已走过部分渲染成实色、
     * 未到部分淡化；ProgressStyle 属于 hasPromotableStyle 白名单，不影响流体云提升。
     */
    private fun buildProgressStyle(summary: LiveSummary): NotificationCompat.ProgressStyle {
        val elapsed = (LIVE_WINDOW_DAYS - summary.days).coerceIn(0, LIVE_WINDOW_DAYS)
        return NotificationCompat.ProgressStyle()
            .addProgressSegment(
                NotificationCompat.ProgressStyle.Segment(LIVE_WINDOW_DAYS * UNITS_PER_DAY)
                    .setColor(urgencyColor(summary.days))
            )
            .setProgress(elapsed * UNITS_PER_DAY)
            .setStyledByProgress(true)
    }

    /**
     * 剩余天数 → 进度条颜色，线性插值：
     * 7 天绿 → 3 天琥珀 → 0 天红。中间值连续过渡，不会出现跳色。
     */
    internal fun urgencyColor(days: Int): Int {
        val d = days.coerceIn(0, LIVE_WINDOW_DAYS)
        return when {
            d >= SOON_ANCHOR_DAYS -> {
                val f = (LIVE_WINDOW_DAYS - d).toFloat() / (LIVE_WINDOW_DAYS - SOON_ANCHOR_DAYS)
                lerpColor(COLOR_SAFE, COLOR_SOON, f)
            }
            else -> {
                val f = (SOON_ANCHOR_DAYS - d).toFloat() / SOON_ANCHOR_DAYS
                lerpColor(COLOR_SOON, COLOR_URGENT, f)
            }
        }
    }

    /** 琥珀锚点：剩 3 天开始从安全绿转向警示色。 */
    private const val SOON_ANCHOR_DAYS = 3

    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun cancelLiveSummary(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LIVE_SUMMARY_ID)
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

        val now = LocalDateTime.now()
        val today = Dates.todayISO()
        val hits = mutableListOf<String>()
        var firstId = 0
        var firstTitle = ""
        var firstBody = ""
        var firstItemId: String? = null

        for (item in store.activeItems()) {
            val days = Dates.daysUntil(item.expireAt, now.toLocalDate()) ?: continue
            if (days < 0 || days !in effectiveRemindDays(item)) continue
            // 定点物品用自己的时刻判断，未到的先不弹，交给闹钟
            if (now.toLocalTime().isBefore(itemNotifyTime(item, notifyTime))) continue
            val key = store.reminderKey(item, days, today)
            if (key in item.notifiedKeys) continue
            val id = ExpiryCalc.hashKey(key)
            if (hits.isEmpty()) {
                firstId = id
                firstTitle = notifyTitle(item)
                firstBody = notifyBody(item, days)
                firstItemId = item.id
            }
            hits.add(key)
        }

        if (hits.isEmpty()) return 0
        val detail = if (hits.size == 1) firstBody else "${hits.size} 件物品需要处理，记得查看"
        showNotification(context, firstId, firstTitle, detail, firstItemId)
        store.markNotified(context, hits)
        return hits.size
    }
}
