package com.jimmy.expiryreminder.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R

/** 给小组件的 ListView 提供数据；列表滑动由启动器原生处理。 */
class ExpiryWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext)

    /**
     * 单列表混排（无模式概念）：每次 onDataSetChanged 动态读当前数据快照，
     * 所有连接渲染结果一致；不缓存任何按模式拆分的状态。
     */
    private class Factory(
        private val context: Context,
    ) : RemoteViewsService.RemoteViewsFactory {

        private var items: List<WidgetItem> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            items = WidgetData.load(context).rows
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_item)
            val item = items.getOrNull(position) ?: return rv
            val dark = WidgetTheme.isDark(context)
            val palette = WidgetTheme.palette(dark)

            // 类型标注拼进名字文本：紧贴名字尾部（Span 着色 + 缩小），不会飘到行中间；
            // 颜色与头部「循环」标签同源（palette.accent），两处永远一致
            val mark = if (item.repeatEnabled) "循环" else "倒计时"
            val markColor = if (item.repeatEnabled) palette.accent else palette.muted
            val nameText = SpannableString("${item.name}  $mark")
            nameText.setSpan(
                ForegroundColorSpan(markColor),
                item.name.length, nameText.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            nameText.setSpan(
                RelativeSizeSpan(0.72f),
                item.name.length, nameText.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )

            rv.setTextViewText(R.id.widget_item_name, nameText)
            rv.setTextViewText(R.id.widget_item_days, item.daysText)
            rv.setTextViewText(R.id.widget_item_expire, item.expireText)
            rv.setTextViewText(R.id.widget_item_added, item.subText)
            rv.setTextColor(R.id.widget_item_name, palette.name)
            rv.setTextColor(R.id.widget_item_expire, palette.muted)
            rv.setTextColor(R.id.widget_item_added, palette.muted)
            rv.setTextColor(R.id.widget_item_days, item.statusColor)
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
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
