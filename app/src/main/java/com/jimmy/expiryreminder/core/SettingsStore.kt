package com.jimmy.expiryreminder.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 应用设置：主题、通知开关、推送时刻。存储在 SharedPreferences。 */
class SettingsStore private constructor() {

    enum class ThemeMode { system, light, dark;

        companion object {
            fun fromName(name: String?): ThemeMode =
                entries.firstOrNull { it.name == name } ?: system
        }
    }

    private val _themeMode = MutableStateFlow(ThemeMode.system)
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    private val _notifyEnabled = MutableStateFlow(true)
    val notifyEnabled: StateFlow<Boolean> get() = _notifyEnabled

    private val _notifyTime = MutableStateFlow("09:00")
    val notifyTime: StateFlow<String> get() = _notifyTime

    /** 用 setAlarmClock 排程（状态栏会显示闹钟图标，抗 Doze 最稳）。 */
    private val _useAlarmClock = MutableStateFlow(true)
    val useAlarmClock: StateFlow<Boolean> get() = _useAlarmClock

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return
        try {
            val map = JSONObject(raw)
            _themeMode.value = ThemeMode.fromName(map.optStringOrNull("theme"))
            if (map.has("notifyEnabled") && !map.isNull("notifyEnabled")) {
                _notifyEnabled.value = map.optBoolean("notifyEnabled", true)
            }
            val time = map.optStringOrNull("notifyTime")
            if (time != null && TIME_REGEX.matches(time)) {
                _notifyTime.value = time
            }
            // 旧版本没有这个键：缺省按开启处理（只读，不重写用户已有设置）
            if (map.has("useAlarmClock") && !map.isNull("useAlarmClock")) {
                _useAlarmClock.value = map.optBoolean("useAlarmClock", true)
            }
        } catch (_: Exception) {
            // 坏数据直接用默认值
        }
    }

    fun update(
        context: Context,
        themeMode: ThemeMode? = null,
        notifyEnabled: Boolean? = null,
        notifyTime: String? = null,
        useAlarmClock: Boolean? = null,
    ) {
        if (themeMode != null) _themeMode.value = themeMode
        if (notifyEnabled != null) _notifyEnabled.value = notifyEnabled
        if (notifyTime != null && TIME_REGEX.matches(notifyTime)) _notifyTime.value = notifyTime
        if (useAlarmClock != null) _useAlarmClock.value = useAlarmClock
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_SETTINGS,
                JSONObject()
                    .put("version", 3)
                    .put("theme", _themeMode.value.name)
                    .put("notifyEnabled", _notifyEnabled.value)
                    .put("notifyTime", _notifyTime.value)
                    .put("useAlarmClock", _useAlarmClock.value)
                    .toString(),
            )
            .apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_SETTINGS = "settings"
        private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        @Volatile
        private var instance: SettingsStore? = null

        fun get(): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore().also { instance = it }
        }
    }
}
