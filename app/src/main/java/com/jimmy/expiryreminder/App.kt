package com.jimmy.expiryreminder

import android.app.Application
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.widget.ExpiryWidgetBase
import com.jimmy.expiryreminder.widget.WidgetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ReminderService.createChannel(this)
        SettingsStore.get().load(this)
        // 旧版本的自定义小组件背景图已不再使用，顺手清理；同时按当前主题刷新小组件配色
        WidgetPrefs.clearLegacyBackground(this)
        ExpiryWidgetBase.refreshAll(this)
        // 语音输入已移除：旧版本把 66MB 的 Vosk 模型解压到 files/vosk-cn，
        // 升级安装不会自动删文件，这里清一次释放空间（目录不存在时是无操作）
        appScope.launch { File(filesDir, "vosk-cn").deleteRecursively() }
        // 数据一变就刷新桌面的三个尺寸小组件（只读本地文件，不联网）
        appScope.launch {
            ExpiryStore.get().state.collect { ExpiryWidgetBase.refreshAll(this@App) }
        }
        appScope.launch {
            val store = ExpiryStore.get()
            store.load(this@App)
            // 启动时同步一次排程（补偿开机接收器没跑、数据变更没同步等情况）
            val settings = SettingsStore.get()
            ReminderService.syncScheduled(
                this@App, store,
                settings.notifyEnabled.value, settings.notifyTime.value,
                settings.useAlarmClock.value, settings.liveUpdateEnabled.value,
            )
        }
    }
}
