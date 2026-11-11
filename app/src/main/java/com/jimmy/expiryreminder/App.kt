package com.jimmy.expiryreminder

import android.app.Application
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ReminderService.createChannel(this)
        SettingsStore.get().load(this)
        appScope.launch {
            ExpiryStore.get().load(this@App)
            // 启动时同步一次排程（补偿开机接收器没跑、数据变更没同步等情况）
            val settings = SettingsStore.get()
            ReminderService.syncScheduled(
                this@App, ExpiryStore.get(),
                settings.notifyEnabled.value, settings.notifyTime.value,
            )
        }
    }
}
