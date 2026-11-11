package com.jimmy.expiryreminder.notify

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
        const val EXTRA_KEY = "key"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_ID = "id"
        const val EXTRA_BODY = "body"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                showNotification(appContext, id, body)
                ExpiryStore.get().markNotified(appContext, listOf(key))
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

/** 开机重排：系统重启后闹钟全部丢失，按当前数据重新排程。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ExpiryStore.get()
                store.load(appContext)
                val settings = SettingsStore.get()
                settings.load(appContext)
                ReminderService.createChannel(appContext)
                ReminderService.syncScheduled(
                    appContext, store,
                    settings.notifyEnabled.value, settings.notifyTime.value,
                )
            } finally {
                pending.finish()
            }
        }
    }
}
