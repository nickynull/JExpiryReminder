package com.jimmy.expiryreminder.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页顶部统计卡的可选项。key 存进设置，label 是界面文案；
 * status 为空表示「全部」（不按状态过滤）。
 */
enum class StatCard(val key: String, val label: String, val status: ExpiryStatus?) {
    All("all", "全部", null),
    Soon("soon", "7天内", ExpiryStatus.soon),
    Month("month", "30天内", ExpiryStatus.month),
    Normal("normal", "正常", ExpiryStatus.normal),
    Expired("expired", "已过期", ExpiryStatus.expired);

    companion object {
        /** 默认显示的三张，保持老版本观感。 */
        val DEFAULT: List<StatCard> = listOf(All, Soon, Expired)

        fun fromKey(key: String?): StatCard? = entries.firstOrNull { it.key == key }

        /** 读取时归一：未知 key 丢掉、重复去掉、顺序按枚举固定；结果为空回退默认。 */
        fun normalize(keys: List<String>): List<StatCard> {
            val picked = keys.mapNotNull { fromKey(it) }.distinct()
            return if (picked.isEmpty()) DEFAULT
            else entries.filter { it in picked }
        }
    }
}

/** 应用设置：主题、通知开关、推送时刻、主页统计卡。存储在 SharedPreferences。 */
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

    /** 主页顶部显示的统计卡，顺序固定为枚举顺序。 */
    private val _statCards = MutableStateFlow(StatCard.DEFAULT)
    val statCards: StateFlow<List<StatCard>> get() = _statCards

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
            // 旧版本没有统计卡配置：保持默认，不写回
            val cards = map.optJSONArray("statCards")
            if (cards != null) {
                val keys = ArrayList<String>(cards.length())
                for (i in 0 until cards.length()) {
                    cards.optString(i).takeIf { it.isNotEmpty() }?.let { keys.add(it) }
                }
                _statCards.value = StatCard.normalize(keys)
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
        statCards: List<StatCard>? = null,
    ) {
        if (themeMode != null) _themeMode.value = themeMode
        if (notifyEnabled != null) _notifyEnabled.value = notifyEnabled
        if (notifyTime != null && TIME_REGEX.matches(notifyTime)) _notifyTime.value = notifyTime
        if (useAlarmClock != null) _useAlarmClock.value = useAlarmClock
        if (statCards != null) _statCards.value = StatCard.normalize(statCards.map { it.key })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_SETTINGS,
                JSONObject()
                    .put("version", 4)
                    .put("theme", _themeMode.value.name)
                    .put("notifyEnabled", _notifyEnabled.value)
                    .put("notifyTime", _notifyTime.value)
                    .put("useAlarmClock", _useAlarmClock.value)
                    .put("statCards", JSONArray(_statCards.value.map { it.key }))
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
