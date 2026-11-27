package com.jimmy.expiryreminder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.CustomCategory
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ImageStore
import org.json.JSONObject

/** 小组件列表的一行数据（只读本地 data.json，不联网、不申请权限）。 */
data class WidgetItem(
    val id: String,
    val name: String,
    val daysText: String,
    /** 左侧色条：分类色，与软件内列表卡片的色条同一个来源。 */
    val barColor: Int,
    /** 剩余天数字：到期状态色。 */
    val statusColor: Int,
    val rowBg: Int,
)

/** 一次读取 data.json 后的完整快照：混排行数据 + 物品总数。 */
data class WidgetSnapshot(
    /** 全部未归档物品，按到期日升序（混排，不按模式拆分）。 */
    val rows: List<WidgetItem>,
    val itemCount: Int,
)

object WidgetData {
    private const val MAX_ROWS = 60

    /**
     * 循环任务入选上限：防止短期循环任务长期霸占列表顶部。
     *
     * 背景：循环任务的 expireAt 永远贴着当前周期，按到期日升序时必然排在
     * 一次性倒计时任务之前，数量一多就把其他任务挤出可视区。因此倒计时
     * 任务优先占位（最多 MAX_ROWS 条），循环任务最多补入 REPEAT_MAX_ROWS 条；
     * 合并后再按到期日统一重排，展示顺序仍是紧迫度优先，只是循环任务的
     * 入选条数有上限。若列表里只有循环任务，仍最多展示 MAX_ROWS 条。
     */
    private const val REPEAT_MAX_ROWS = 12

    /**
     * 解析结果缓存：data.json 的 (mtime, size) 与深浅色都没变时直接复用。
     * RemoteViewsFactory.onDataSetChanged 会在每次列表刷新/滚动时被调用，
     * 不缓存的话每条都要重新解析整份 data.json（几千条时明显卡顿）。
     */
    private var cacheStamp: String? = null
    private var cache: WidgetSnapshot? = null

    @Synchronized
    fun load(context: Context): WidgetSnapshot {
        val file = ImageStore.dataFile(context)
        if (!file.exists()) return WidgetSnapshot(emptyList(), 0)
        val dark = WidgetTheme.isDark(context)
        val stamp = "${file.lastModified()}|${file.length()}|$dark"
        cache?.let { if (stamp == cacheStamp) return it }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return WidgetSnapshot(emptyList(), 0)
        val arr = root.optJSONArray("items") ?: return WidgetSnapshot(emptyList(), 0)

        // 分类色条必须和软件内部一致：内置分类颜色 + 用户改色覆盖 + 自定义分类颜色。
        // 不能用到期状态色代替，状态色只用于行底色与剩余天数。
        val overrides = HashMap<String, String>()
        root.optJSONObject("categoryColors")?.let { obj ->
            for (key in obj.keys()) overrides[key] = obj.optString(key)
        }
        val categoryColors = Categories.BUILTIN
            .associate { it.id to (overrides[it.id] ?: it.color) }
            .toMutableMap()
        root.optJSONArray("categories")?.let { cats ->
            for (i in 0 until cats.length()) {
                CustomCategory.fromJson(cats.opt(i))?.let { categoryColors[it.id] = it.color }
            }
        }

        // 空间信息不再展示在小组件行内（2026-11-26 用户要求去掉「空间 · 添加日期」副标题）

        val items = ArrayList<ExpiryItem>()
        for (i in 0 until arr.length()) {
            ExpiryItem.fromJson(arr.opt(i))?.let { if (!it.archived) items += it }
        }
        // 选取：倒计时优先占位，循环任务限量补入（见 REPEAT_MAX_ROWS 注释），
        // 合并后按到期日重排，行序仍按紧迫度展示
        val sorted = items.sortedWith(compareBy({ it.expireAt.ifBlank { "9999-12-31" } }, { it.name }))
        val countdownRows = sorted.filter { !it.repeatEnabled }.take(MAX_ROWS)
        val repeatRows = sorted.filter { it.repeatEnabled }
            .take(minOf(REPEAT_MAX_ROWS, MAX_ROWS - countdownRows.size))
        val selected = (countdownRows + repeatRows)
            .sortedWith(compareBy({ it.expireAt.ifBlank { "9999-12-31" } }, { it.name }))
        val rows = ArrayList<WidgetItem>(selected.size)
        for (item in selected) {
            val days = if (item.expireAt.isBlank()) Int.MAX_VALUE
                else (runCatching { Dates.daysUntil(item.expireAt) }.getOrNull() ?: Int.MAX_VALUE)
            val status = when {
                days == Int.MAX_VALUE -> ExpiryStatus.normal
                days < 0 -> ExpiryStatus.expired
                days <= 7 -> ExpiryStatus.soon
                days <= 30 -> ExpiryStatus.month
                else -> ExpiryStatus.normal
            }
            rows += WidgetItem(
                id = item.id,
                name = item.name,
                daysText = if (item.expireAt.isBlank()) "未设置"
                else Dates.describeRemaining(item.expireAt),
                barColor = runCatching {
                    Color.parseColor(categoryColors[item.category] ?: "#909399")
                }.getOrDefault(Color.GRAY),
                statusColor = runCatching {
                    Color.parseColor(WidgetTheme.statusHex(status, dark))
                }.getOrDefault(Color.GRAY),
                rowBg = WidgetTheme.rowBg(status, dark),
            )
        }
        val snapshot = WidgetSnapshot(
            rows = rows,
            itemCount = items.size,
        )
        cacheStamp = stamp
        cache = snapshot
        return snapshot
    }
}

/**
 * 桌面小组件基类：标准 Android AppWidget（ColorOS 万能卡片/原子组件按此协议适配）。
 *
 * 列表用可滚动的 ListView（RemoteViewsService 提供）。历史教训（勿回退）：
 * adapter intent 的 data URI 必须固定为实例级（不按内容/模式拆分），否则部分启动器
 * （ColorOS）不重建连接，会留下残留工厂互相覆盖列表；工厂每次 onDataSetChanged
 * 动态读数据快照，连接复用也总能拿到最新内容。
 *
 * 交互能力边界（勿再尝试）：RemoteViews 只支持点击类交互
 * （setOnClickPendingIntent / setPendingIntentTemplate / 行内 fillInIntent），
 * 没有 setOnLongClickListener 之类的长按 API；且长按手势由启动器保留用于弹出
 * 小组件管理菜单（调整大小/移除等），启动器窗口层级更高，应用收不到该事件。
 * ColorOS 万能卡片同样基于 AppWidget 协议，结论一致：小组件内无法实现
 * 「长按卡片添加任务」，只能用卡片内按钮/区域点击替代。
 */
abstract class ExpiryWidgetBase : AppWidgetProvider() {

    /** 正常尺寸布局。 */
    protected abstract val layoutRes: Int

    /** 拖小后的紧凑布局（三个尺寸共用 widget_compact）。 */
    protected abstract val compactLayoutRes: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateOne(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // ColorOS 17 允许自由拖动改尺寸：重渲染以适配新高度，拖小后切换紧凑布局
        updateOne(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: mgr.getAppWidgetIds(ComponentName(context, this::class.java))
            for (id in ids) updateOne(context, mgr, id)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
        val compact = isCompact(mgr.getAppWidgetOptions(id))
        val views = RemoteViews(context.packageName, if (compact) compactLayoutRes else layoutRes)
        val snapshot = WidgetData.load(context)
        val dark = WidgetTheme.isDark(context)
        val palette = WidgetTheme.palette(dark)

        // 头部单一总数：N 提醒（倒计时 + 循环合并计数），标签用主色对齐 ColorOS 待办。
        // 四个布局（含紧凑卡）都有统计视图，直接赋值
        views.setTextViewText(R.id.widget_count_total, snapshot.itemCount.toString())
        views.setTextColor(R.id.widget_count_total, palette.title)
        views.setTextColor(R.id.widget_label_total, palette.accent)

        views.setTextColor(R.id.widget_empty, palette.muted)
        views.setOnClickPendingIntent(R.id.widget_add, addPendingIntent(context))
        // 长按卡片被启动器保留为小组件管理手势（见 ExpiryWidgetBase 类注释），
        // 无法用它触发新增；空态区域整块可点，作为无物品时最直接的添加入口
        views.setOnClickPendingIntent(R.id.widget_empty, addPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_camera, cameraPendingIntent(context))
        // 点头部空白区（计数/标签周围）直接进应用主页；相机键与「+」的子控件点击优先于父容器
        views.setOnClickPendingIntent(R.id.widget_header, mainPendingIntent(context))
        // 相机键：圆底与应用图标底色一致（#FFC107），图标白色（colorFilter 着色，不影响底形）
        views.setInt(R.id.widget_camera, "setBackgroundResource", WidgetTheme.camBg(dark))
        views.setInt(R.id.widget_camera, "setColorFilter", WidgetTheme.camIcon(dark))

        // 卡片颜色跟随应用浅色/深色主题
        views.setInt(R.id.widget_scrim, "setBackgroundColor", palette.card)
        views.setViewVisibility(R.id.widget_bg_image, android.view.View.GONE)

        views.setViewVisibility(
            R.id.widget_empty,
            if (snapshot.rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE,
        )

        // adapter URI 固定为实例级（不按内容拆分），工厂动态读快照，杜绝残留工厂竞态
        val adapter = Intent(context, ExpiryWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("jexpiry://widget/$id")
        }
        views.setRemoteAdapter(R.id.widget_list, adapter)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_list, listTemplate(context))

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    /** 行点击模板：fillInIntent 由 Factory 带物品 id，进主页并滚动定位该物品。 */
    private fun listTemplate(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** 右上角「+」：清空任务栈重开，直接落到新增页。 */
    private fun addPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7001,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ADD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 「+」左侧相机键：落到与主页相机按钮相同的选图（拍照）页。 */
    private fun cameraPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7002,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 点头部进应用主页：不开新任务栈顶行为，回到正常主页。 */
    private fun mainPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7003,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** 应用内数据变化时发的私有刷新广播，不对外暴露。 */
        const val ACTION_REFRESH = "com.jimmy.expiryreminder.action.WIDGET_REFRESH"

        /**
         * 紧凑判定：只按宽度判（当前宽 < 250dp，两格宽）。
         * 2026-11-26 教训：原实现还有「MIN_HEIGHT < 100dp」分支，但
         * OPTION_APPWIDGET_MIN_HEIGHT 是横屏下的最小高度（启动器上报的最小边），
         * 竖屏很高的卡片横屏高度可能不足 100dp，导致 Provider 误判紧凑、
         * 套上没有统计视图的 widget_compact 头部——而 Factory 在另一次
         * onDataSetChanged 时判为非紧凑、出正常行，形成「头部无统计数、
         * 行有剩余天数」的混合状态（用户报过「统计数量莫名消失」）。
         * MIN_WIDTH 是竖屏宽度，可靠；高度不再参与判定。
         */
        fun isCompact(options: Bundle?): Boolean {
            if (options == null) return false
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, Int.MAX_VALUE)
            return w in 1..249
        }

        /** App 数据变化时调用：让三个尺寸的小组件一起刷新。 */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            for (clazz in listOf(
                ExpiryWidgetSmall::class.java,
                ExpiryWidgetMedium::class.java,
                ExpiryWidgetLarge::class.java,
            )) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, clazz))
                if (ids.isEmpty()) continue
                context.sendBroadcast(
                    Intent(context, clazz)
                        .setAction(ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}

/** 2×2 紧凑：只显示最紧急的几条，列表可滚动。 */
class ExpiryWidgetSmall : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_small
    override val compactLayoutRes: Int = R.layout.widget_compact
}

/** 4×2 中等：一屏能看更多条目。 */
class ExpiryWidgetMedium : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_medium
    override val compactLayoutRes: Int = R.layout.widget_compact
}

/** 4×4 大：完整清单 + 状态说明。 */
class ExpiryWidgetLarge : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_large
    override val compactLayoutRes: Int = R.layout.widget_compact
}
