package com.jimmy.expiryreminder.widget

import android.content.Context
import android.content.res.Configuration
import com.jimmy.expiryreminder.R
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.SettingsStore

/** 小组件配色：完全跟随应用主题，不提供独立的外观开关。 */
data class WidgetPalette(
    val card: Int,
    val title: Int,
    val name: Int,
    val muted: Int,
    val accent: Int,
)

object WidgetTheme {

    fun isDark(context: Context): Boolean = when (SettingsStore.get().themeMode.value) {
        SettingsStore.ThemeMode.light -> false
        SettingsStore.ThemeMode.dark -> true
        else -> {
            val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mask == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun palette(dark: Boolean): WidgetPalette = if (dark) {
        WidgetPalette(
            card = 0xFF1A1D21.toInt(),
            title = 0xFFE3E6EA.toInt(),
            name = 0xFFE3E6EA.toInt(),
            muted = 0xFFA2A9B5.toInt(),
            accent = 0xFFEBB563.toInt(),
        )
    } else {
        WidgetPalette(
            card = 0xFFFFFFFF.toInt(),
            title = 0xFF1F2D3D.toInt(),
            name = 0xFF1F2D3D.toInt(),
            muted = 0xFF8A94A6.toInt(),
            accent = 0xFFE6A23C.toInt(),
        )
    }

    /** 相机键圆形底：浅色主题淡主色，深色主题低亮度主色。 */
    fun camBg(dark: Boolean): Int =
        if (dark) R.drawable.widget_cam_bg_dark else R.drawable.widget_cam_bg_light

    /**
     * 相机键图标色：白色（2026-11-26 用户要求参考待办组件用白图标；
     * 底色保持品牌黄 #FFC107）。加号文字色在布局里同样为白。
     */
    fun camIcon(dark: Boolean): Int = 0xFFFFFFFF.toInt()

    /** 行底色按到期状态取；深色模式用低亮度版本，避免白块刺眼。 */
    fun rowBg(status: ExpiryStatus, dark: Boolean): Int = if (dark) {
        when (status) {
            ExpiryStatus.expired -> R.drawable.widget_row_expired_dark
            ExpiryStatus.soon -> R.drawable.widget_row_soon_dark
            ExpiryStatus.month -> R.drawable.widget_row_month_dark
            else -> R.drawable.widget_row_normal_dark
        }
    } else {
        when (status) {
            ExpiryStatus.expired -> R.drawable.widget_row_expired
            ExpiryStatus.soon -> R.drawable.widget_row_soon
            ExpiryStatus.month -> R.drawable.widget_row_month
            else -> R.drawable.widget_row_normal
        }
    }

    /** 状态文字色：深色模式适当提亮，保证对比度。 */
    fun statusHex(status: ExpiryStatus, dark: Boolean): String = if (!dark) status.color else {
        when (status) {
            ExpiryStatus.expired -> "#F78989"
            ExpiryStatus.soon -> "#EBB563"
            ExpiryStatus.month -> "#66B1FF"
            else -> "#85CE61"
        }
    }
}
