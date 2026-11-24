package com.jimmy.expiryreminder.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页顶部统计卡的可选项。key 存进设置，label 是界面文案；
 * status 为空表示「全部」（不按状态过滤）。
 *
 * **枚举顺序就是卡片顺序**（读取时 `normalize` 按 entries 重排）：
 * 全部 → 正常 → 30天内 → 7天内 → 已过期，从最宽松排到最紧迫。
 */
enum class StatCard(val key: String, val label: String, val status: ExpiryStatus?) {
    All("all", "全部", null),
    Normal("normal", "正常", ExpiryStatus.normal),
    Month("month", "30天内", ExpiryStatus.month),
    Soon("soon", "7天内", ExpiryStatus.soon),
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

    /** 同一 Wi-Fi 下自动发现设备并合并数据；默认关闭，老用户升级后不受影响。 */

    /** 流体云 / Live Update 提醒：仅 Android 16+ 且有系统支持时生效，否则自动降级。默认关闭（它会产生一条用户滑不掉的常驻通知）。 */
    private val _liveUpdateEnabled = MutableStateFlow(false)
    val liveUpdateEnabled: StateFlow<Boolean> get() = _liveUpdateEnabled

    /** 开机后是否恢复 30 分钟一次的自动同步排程。 */

    /**
     * 锁屏 / 通知栏隐藏物品详情。开启后锁屏只显示「到期提醒 · 点击查看」，
     * 解锁后才看得到具体物品名——避免手机放在桌上时旁人一眼扫到家里买了什么。
     */
    private val _hideNotificationDetail = MutableStateFlow(false)
    val hideNotificationDetail: StateFlow<Boolean> get() = _hideNotificationDetail

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SETTINGS, null) ?: return
        try {
            val map = JSONObject(raw)
            // 旧版本（<8）流体云默认开启，现改为默认关闭：老存档里已写入 true 的一律按关闭处理，
            // 只在用户升级后再主动打开时才持久化 true
            val settingsVersion = map.optInt("version", 0)
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
            // 自动同步默认关闭；旧版本没有这个键时保持默认，不写回
            when {
                settingsVersion < 8 -> _liveUpdateEnabled.value = false
                map.has("liveUpdateEnabled") && !map.isNull("liveUpdateEnabled") ->
                    _liveUpdateEnabled.value = map.optBoolean("liveUpdateEnabled", false)
            }
            if (map.has("hideNotificationDetail") && !map.isNull("hideNotificationDetail")) {
                _hideNotificationDetail.value = map.optBoolean("hideNotificationDetail", false)
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
        liveUpdateEnabled: Boolean? = null,
        hideNotificationDetail: Boolean? = null,
    ) {
        if (themeMode != null) _themeMode.value = themeMode
        if (notifyEnabled != null) _notifyEnabled.value = notifyEnabled
        if (notifyTime != null && TIME_REGEX.matches(notifyTime)) _notifyTime.value = notifyTime
        if (useAlarmClock != null) _useAlarmClock.value = useAlarmClock
        if (statCards != null) _statCards.value = StatCard.normalize(statCards.map { it.key })
        if (liveUpdateEnabled != null) _liveUpdateEnabled.value = liveUpdateEnabled
        if (hideNotificationDetail != null) _hideNotificationDetail.value = hideNotificationDetail
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_SETTINGS,
                JSONObject()
                    .put("version", 8)
                    .put("theme", _themeMode.value.name)
                    .put("notifyEnabled", _notifyEnabled.value)
                    .put("notifyTime", _notifyTime.value)
                    .put("useAlarmClock", _useAlarmClock.value)
                    .put("statCards", JSONArray(_statCards.value.map { it.key }))
                    .put("liveUpdateEnabled", _liveUpdateEnabled.value)
                    .put("hideNotificationDetail", _hideNotificationDetail.value)
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
