package com.jimmy.expiryreminder.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 闹钟触发：弹通知 + 记录去重键（同一天同一档位只提醒一次）。 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.jimmy.expiryreminder.ACTION_FIRE"

        /** 链式补排：触发后延迟几分钟整包重排，把后续提醒重新排满。 */
        const val ACTION_REFILL = "com.jimmy.expiryreminder.ACTION_REFILL"

        const val EXTRA_KEY = "key"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_ID = "id"
        const val EXTRA_BODY = "body"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> handleFire(context, intent)
            ACTION_REFILL -> handleRefill(context)
        }
    }

    private fun handleFire(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: ""
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ExpiryStore.get()
                store.load(appContext)
                // 前台补发可能已经推过同一条，这里按去重键拦住，避免重复打扰
                val alreadyNotified = store.items
                    .firstOrNull { it.id == itemId }
                    ?.notifiedKeys?.contains(key) == true
                if (!alreadyNotified) {
                    showNotification(appContext, id, body)
                    store.markNotified(appContext, listOf(key))
                }
                // 链式补排：延迟几分钟再整包重排，别在这里立刻排（会吃掉同一时刻还没投递的闹钟）
                ReminderService.scheduleRefill(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleRefill(context: Context) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleAll(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, id: Int, body: String) {
        ReminderService.createChannel(context)
        ReminderService.showNotification(context, id, "到期提醒", body)
    }
}

/** 按当前数据与设置整包重排提醒（开机 / 授权变化 / 时区变化 / 应用升级共用）。 */
internal suspend fun rescheduleAll(context: Context) {
    val store = ExpiryStore.get()
    store.load(context)
    val settings = SettingsStore.get()
    settings.load(context)
    ReminderService.createChannel(context)
    ReminderService.syncScheduled(
        context, store,
        settings.notifyEnabled.value, settings.notifyTime.value, settings.useAlarmClock.value,
    )
}

/** 开机重排：系统重启后闹钟全部丢失，按当前数据重新排程。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleAll(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 其它会让排程失效的系统事件，统一重排：
 * - 精确闹钟权限被授予或撤销（撤销时系统会取消全部精确闹钟）；
 * - 系统时间或时区变更；
 * - 应用被覆盖安装（升级后闹钟会被系统清掉）。
 */
class ReminderSyncReceiver : BroadcastReceiver() {

    companion object {
        private val ACTIONS = setOf(
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleAll(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
