package com.jimmy.expiryreminder.widget

import android.content.Context
import java.io.File

/**
 * 小组件外观完全跟随应用主题，本身不再保存任何偏好。
 * 目前只剩一件事：清理旧版本留下的自定义背景图文件。
 * （列表切换方案已废弃：改为单列表混排 + 行内标注 + 头部双计数。）
 */
object WidgetPrefs {

    private const val LEGACY_BG_NAME = "widget_bg.jpg"

    fun clearLegacyBackground(context: Context) {
        runCatching { File(context.filesDir, LEGACY_BG_NAME).delete() }
    }
}
