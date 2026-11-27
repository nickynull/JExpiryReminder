package com.jimmy.expiryreminder.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R

/** 给小组件的 ListView 提供数据；列表滑动由启动器原生处理。 */
class ExpiryWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent)

    /**
     * 单列表混排（无模式概念）：每次 onDataSetChanged 动态读当前数据快照，
     * 所有连接渲染结果一致；不缓存任何按模式拆分的状态。
     */
    private class Factory(
        private val context: Context,
        private val intent: Intent,
    ) : RemoteViewsService.RemoteViewsFactory {

        private var items: List<WidgetItem> = emptyList()

        /** 紧凑模式：小组件被拖小后换用 widget_item_compact 行布局。 */
        private var compact = false

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            items = WidgetData.load(context).rows
            // 连接复用时工厂不重建，尺寸变化靠每次 onDataSetChanged 重读 AppWidgetOptions
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val options = if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            } else {
                null
            }
            compact = ExpiryWidgetBase.isCompact(options)
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val layout = if (compact) R.layout.widget_item_compact else R.layout.widget_item
            val rv = RemoteViews(context.packageName, layout)
            val item = items.getOrNull(position) ?: return rv

            // 单行紧凑式：物品名 + 剩余天数；紧凑行（两格宽）没有天数视图，不显示剩余天数
            val palette = WidgetTheme.palette(WidgetTheme.isDark(context))
            rv.setTextViewText(R.id.widget_item_name, item.name)
            rv.setTextColor(R.id.widget_item_name, palette.name)
            if (!compact) {
                rv.setTextViewText(R.id.widget_item_days, item.daysText)
                rv.setTextColor(R.id.widget_item_days, item.statusColor)
            }
            // 左侧色条用分类色（与软件内列表一致）；整行底色按到期状态取浅色版本
            rv.setInt(R.id.widget_item_bar, "setColorFilter", item.barColor)
            rv.setInt(R.id.widget_item_root, "setBackgroundResource", item.rowBg)
            rv.setOnClickFillInIntent(
                R.id.widget_item_root,
                Intent().putExtra(MainActivity.EXTRA_OPEN_ITEM_ID, item.id),
            )
            return rv
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 2
        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
