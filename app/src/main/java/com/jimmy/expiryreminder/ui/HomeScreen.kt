@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.Spaces
import com.jimmy.expiryreminder.core.remindDaysSummary
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 备注里的换行、制表、连续空格：列表只显示一行，先压成单个空格。 */
private val SINGLE_LINE_WS = Regex("\\s+")

/** 列表行左滑的三个沉降点：原位 / 中段（归档）/ 滑满（删除）。 */
private enum class RowSwipeStage { Settled, Archive, Delete }

/** 左滑沉降后的待确认动作：[delete] 为真表示停在删除段，否则是归档段。 */
private data class SwipeAsk(val item: ExpiryItem, val delete: Boolean)

/** 归档段底色：与「7 天内到期」状态色同源的黄，保持现配色。 */
private val ArchiveSwipeColor = Color(0xFFE6A23C)

/**
 * 左滑的两个沉降锚点（都是行宽的比例，向右为负）。
 *
 * 早期取 0.45 / 0.94，删除要拖到将近屏幕边缘，手感又远又晚，故收紧到下面这组。
 * 归档页模式只有「原位 / 删除」两个锚点，删除落在 0.58 上更顺手。
 */
private const val SwipeArchiveFrac = 0.28f
private const val SwipeDeleteFrac = 0.58f

/**
 * 左滑进度达到这个比例后，底色与文案从「归档」切换为「删除」。
 *
 * 必须与两个锚点的沉降分界一致：Archive = -SwipeArchiveFrac、Delete = -SwipeDeleteFrac，
 * AnchoredDraggable 默认在相邻锚点中点判定，即 (0.28 + 0.58) / 2 = 0.43。
 * 取中点让「看到红色 = 松手就删」成立；否则刚变红的那一段却会落回归档，视觉与结果对不上。
 */
private const val SwipeDeleteSwitchFrac = (SwipeArchiveFrac + SwipeDeleteFrac) / 2f

/**
 * 首页筛选状态（搜索词 + 分类 + 空间 + 列表模式）：由导航层持有，进出编辑页后回来仍保留。
 * 用 listSaver 存字符串，进程被回收再恢复也不丢。
 */
@Stable
class HomeFilterState(
    keyword: String = "",
    category: String = "all",
    space: String = Spaces.ALL,
    listMode: String = MODE_COUNTDOWN,
) {
    var keyword by mutableStateOf(keyword)
    var category by mutableStateOf(category)
    /** 当前空间：列表、统计卡、分类计数都按它过滤；默认「全部空间」，含未分配。 */
    var space by mutableStateOf(space)
    /** 当前列表：倒计时（普通物品）/ 循环任务，按 repeatEnabled 区分；默认倒计时。 */
    var listMode by mutableStateOf(listMode)

    companion object {
        /** 与「全部分类」的虚拟 id 同值：筛选值直接当作颜色覆盖表的键用。 */
        const val ALL = Categories.ALL
        const val MODE_COUNTDOWN = "countdown"
        const val MODE_CYCLE = "cycle"

        val Saver = listSaver<HomeFilterState, String>(
            save = { listOf(it.keyword, it.category, it.space, it.listMode) },
            restore = {
                HomeFilterState(
                    keyword = it.getOrElse(0) { "" },
                    category = it.getOrElse(1) { ALL },
                    space = it.getOrElse(2) { Spaces.ALL },
                    // 旧存档没有第 4 项，回落到倒计时，保证升级后不空白
                    listMode = it.getOrElse(3) { MODE_COUNTDOWN },
                )
            },
        )
    }
}

/** 首页图片查看器的入参：[origin] 是发起点击的缩略图在屏幕上的位置，用于展开/缩回转场。 */
private data class ViewerState(
    val paths: List<String>,
    val index: Int,
    val origin: ImageOrigin?,
)

/** 参考稿样式首页：白底标题区 + 统计卡 + 搜索/筛选 + 状态分组列表。
 *
 * [filter] 由调用方持有，保证切页回来筛选不丢；
 * [onAdd] / [onOcr] 会带上当前筛选的分类与空间（「全部分类」「全部空间」「未分配」时为 null），
 * 新增页据此预选，避免在某个空间下新增还得再手动选一次空间。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    filter: HomeFilterState,
    /** 归档模式：复用整页结构，列表换成已归档物品（统计卡/列表切换/空间筛选/新增入口不适用，一律隐藏）。 */
    archivedMode: Boolean = false,
    /** 非空时标题左侧显示返回入口（归档页作为二级页用）；主页传 null。 */
    onBack: (() -> Unit)? = null,
    /** [defaultCategory] / [defaultSpace] 为当前筛选的分类与空间，无筛选时传 null。 */
    onAdd: (defaultCategory: String?, defaultSpace: String?) -> Unit,
    /** 图像识别入口：选图 → 识别 → 带结果进添加页。 */
    onOcr: (defaultCategory: String?, defaultSpace: String?) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onCategories: () -> Unit,
    /** 空间管理页。 */
    onSpaces: () -> Unit,
    /** 归档页：查看已归档物品，可调回。 */
    onArchive: () -> Unit,
    /** 多选「分享」：把选中的物品带到跨设备传输页。 */
    onShare: (List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val settings = SettingsStore.get()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val useAlarmClock by settings.useAlarmClock.collectAsStateWithLifecycle()
    val liveUpdateEnabled by settings.liveUpdateEnabled.collectAsStateWithLifecycle()
    val statCards by settings.statCards.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // 多选批量操作弹窗
    var batchSpaceDialog by remember { mutableStateOf(false) }
    var batchCategoryDialog by remember { mutableStateOf(false) }
    var batchDeleteDialog by remember { mutableStateOf(false) }
    // 按空间分享
    var shareSpaceDialog by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var spaceMenuOpen by remember { mutableStateOf(false) }
    // 全屏图片查看器：图片路径 + 起始下标 + 发起点击的缩略图位置，null = 未打开
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    // 左滑确认：行只上报「停在归档段/删除段」，确认框在页面层弹（行被回收也不会丢）
    var swipeAsk by remember { mutableStateOf<SwipeAsk?>(null) }
    // 每次关框自增一次，驱动停在确认位的那一行回弹
    var swipeResetToken by remember { mutableIntStateOf(0) }
    val closeSwipeAsk = {
        swipeAsk = null
        swipeResetToken += 1
    }

    // 只在多选态拦截返回；主页本身不拦截，交给系统处理，
    // 走系统默认的退出动画（预测性返回已关闭，不再有「缩小到桌面图标」的预览）。
    BackHandler(enabled = selectMode) {
        selectMode = false
        selected = emptySet()
    }

    // 数据就绪后同步一次排程（新增/修改/删除都会经 store，这里兜底）
    LaunchedEffect(state.items, notifyEnabled, notifyTime, useAlarmClock) {
        if (state.loaded) {
            ReminderService.syncScheduled(
                context, store, notifyEnabled, notifyTime, useAlarmClock, liveUpdateEnabled,
            )
        }
    }

    // 筛选的分类被删掉（或导入的数据里没有）时回落到「全部分类」，避免筛出空列表
    LaunchedEffect(state.categories, state.categoryColors) {
        if (filter.category != HomeFilterState.ALL && store.categoryOf(filter.category) == null) {
            filter.category = HomeFilterState.ALL
        }
    }

    // 当前空间被删掉时回落到「全部空间」（未分配是合法状态，不回落）
    LaunchedEffect(state.spaces) {
        if (filter.space != Spaces.ALL &&
            filter.space.isNotEmpty() &&
            store.spaceOf(filter.space) == null
        ) {
            filter.space = Spaces.ALL
            filter.category = HomeFilterState.ALL
        }
    }

    // 先只按当前空间 + 搜索词筛一遍：下拉里的分类计数 = 点进去后能看到的条数，与统计卡「全部」同一口径。
    // 归档模式换成该空间下的已归档物品；搜索入口在归档模式下不显示，关键字忽略。
    val spaceItems = remember(state, filter.space, archivedMode) {
        if (archivedMode) store.archivedItemsInSpace(filter.space)
        else store.itemsInSpace(filter.space)
    }
    // 空间下拉里的条数：与列表同口径（排除归档）；归档页按归档物品计数
    val spaceCount: (String) -> Int =
        if (archivedMode) store::archivedCountInSpace else store::activeCountInSpace
    val effectiveKeyword = if (archivedMode) "" else filter.keyword
    val keywordMatched = remember(spaceItems, effectiveKeyword) {
        val kw = effectiveKeyword.trim().lowercase()
        spaceItems.filter { item ->
            kw.isEmpty() || item.name.lowercase().contains(kw) || item.note.lowercase().contains(kw)
        }
    }
    // 列表模式分流：倒计时 = 非循环物品，循环任务 = repeatEnabled。
    // 列表、统计卡、分类计数、全选都以 modeMatched 为同一口径——切到哪个列表就只看哪一类。
    // 归档模式不分倒计时/循环（切换器不显示），全部一起看。
    val isCycleMode = !archivedMode && filter.listMode == HomeFilterState.MODE_CYCLE
    val modeMatched = remember(keywordMatched, isCycleMode, archivedMode) {
        // 归档模式不切列表模式，循环/倒计时物品一起看
        if (archivedMode) keywordMatched
        else keywordMatched.filter { it.repeatEnabled == isCycleMode }
    }
    val cycleCount = remember(spaceItems) { spaceItems.count { it.repeatEnabled } }
    val countdownCount = remember(spaceItems) { spaceItems.count { !it.repeatEnabled } }
    val categoryCounts = remember(modeMatched) {
        modeMatched.groupingBy { it.category }.eachCount()
    }
    val filtered = remember(modeMatched, filter.category) {
        if (filter.category == HomeFilterState.ALL) modeMatched
        else modeMatched.filter { it.category == filter.category }
    }
    val grouped = remember(filtered) {
        // 按状态固定顺序输出非空分组
        val byStatus = filtered.groupBy { store.statusOf(it) }
        ExpiryStatus.entries.mapNotNull { s -> byStatus[s]?.let { s to it } }
    }
    // 列表分区：主页按到期状态分组；归档页不分组——物品已不再提醒，
    // 再套「已过期 / 7 天内到期」的标题会让人误以为还在催，按到期日单列表更直白
    val sections: List<Pair<ExpiryStatus?, List<ExpiryItem>>> = remember(grouped, archivedMode) {
        if (archivedMode) listOf(null to filtered) else grouped.map { it.first to it.second }
    }
    // 标签左侧的色点跟候选菜单同源（同一分类定义 / 「全部分类」的颜色覆盖），且始终显示
    val selectedCategory = if (filter.category == HomeFilterState.ALL) null
    else store.categoryOptions().firstOrNull { it.id == filter.category }
    val categoryLabel = selectedCategory?.label ?: Categories.ALL_LABEL
    val categoryDotColor = store.categoryColorOf(filter.category)

    // 可左右切换的分类环 =「全部分类」+ 当前列表下至少有一条记录的分类。
    // 空分类不进环，滑动不会切出一片空白；这里刻意不跟搜索词联动，避免搜索时环忽长忽短，
    // 但跟随列表模式——切到循环任务后，环里只保留循环任务实际用到的分类。
    val switchableIds = remember(modeMatched) {
        val counts = modeMatched.groupingBy { it.category }.eachCount()
        buildList {
            add(HomeFilterState.ALL)
            store.categoryOptions().forEach { if ((counts[it.id] ?: 0) > 0) add(it.id) }
        }
    }
    val switchIndex = switchableIds.indexOf(filter.category).coerceAtLeast(0)
    val canSwitch = switchableIds.size > 1

    // 切换方向：1 = 往后翻（新内容从右侧滑进来），-1 = 往前翻（从左侧滑进来）。
    // 方向决定动画从哪一侧进场，用户滑动时才知道自己翻到了哪边。
    var slideDir by remember { mutableStateOf(1) }
    val slideShiftPx = with(LocalDensity.current) { 64.dp.toPx() }
    // 首次进页面不播动画（否则一进来列表就滑一下）
    var firstComposition by remember { mutableStateOf(true) }
    // 每换一次分类或列表模式就新建一个从 ±1 起跳的 Animatable：起始偏移在组合期就位，
    // 不会出现「先按最终位置画一帧、再跳回去滑进来」的闪一下
    val slide = remember(filter.category, filter.listMode) {
        Animatable(if (firstComposition) 0f else slideDir.toFloat())
    }
    LaunchedEffect(slide) {
        if (slide.value != 0f) {
            slide.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) { firstComposition = false }

    // step = 1 下一个 / -1 上一个，走到头绕回另一端
    val switchCategory: (Int) -> Unit = { step ->
        if (canSwitch) {
            val size = switchableIds.size
            val next = ((switchIndex + step) % size + size) % size
            if (next != switchIndex) {
                slideDir = if (step >= 0) 1 else -1
                filter.category = switchableIds[next]
            }
        }
    }

    // 下拉里直接点某个分类：按环上索引先后推方向，不在环里的（空分类）按往后翻处理
    val selectCategory: (String) -> Unit = { id ->
        val from = switchableIds.indexOf(filter.category)
        val to = switchableIds.indexOf(id)
        slideDir = if (from >= 0 && to >= 0 && to < from) -1 else 1
        filter.category = id
    }

    // 列表模式切换：倒计时 → 循环任务从右滑进，反之从左滑进。
    // 切过去后当前分类如果在新列表里没有记录，回落到「全部分类」，避免切出一片空白；
    // 同时退出多选，防止把另一类物品带进批量操作。
    val switchListMode: (String) -> Unit = { mode ->
        if (mode != filter.listMode) {
            slideDir = if (mode == HomeFilterState.MODE_CYCLE) 1 else -1
            filter.listMode = mode
            if (selectMode) {
                selectMode = false
                selected = emptySet()
            }
            // 这里 listMode 刚更新、modeMatched 还是旧组合的缓存，
            // 故直接按新模式从 spaceItems 判断当前分类是否还有记录
            val nextIsCycle = mode == HomeFilterState.MODE_CYCLE
            if (filter.category != HomeFilterState.ALL &&
                spaceItems.none { it.repeatEnabled == nextIsCycle && it.category == filter.category }
            ) {
                filter.category = HomeFilterState.ALL
            }
        }
    }

    // 真的换了分类或列表模式才回顶部；从编辑页返回时保留原来的滚动位置
    val listState = rememberLazyListState()
    var scrolledKey by remember { mutableStateOf(filter.category + "|" + filter.listMode) }
    val currentScrollKey = filter.category + "|" + filter.listMode
    LaunchedEffect(currentScrollKey) {
        if (scrolledKey != currentScrollKey) {
            scrolledKey = currentScrollKey
            if (filtered.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    // 新增/识别带上的分类：「全部分类」= 无筛选，交给新增页用默认分类；其余带上筛选的分类
    val targetCategory = filter.category.takeIf {
        it != HomeFilterState.ALL && store.categoryOf(it) != null
    }
    // 新增/识别带上的空间：同上，「全部空间」/「未分配」/ 已被删掉的空间都传 null
    val targetSpace = filter.space.takeIf {
        it != Spaces.ALL && it.isNotEmpty() && store.spaceOf(it) != null
    }

    // ------------------------------- 顶部磨砂 / 底部浮动：高度测量与滚动进度
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    // 分类行顶边：遮罩只铺到这里，分类行整条保持全透，底下滚过的卡片能透出来
    var catRowTopPx by remember { mutableIntStateOf(0) }
    // 遮罩强度只跟滚动距离有关：滚到顶 0（完全透明，露出干净底色），往下 28dp 内就升满。
    // 上升区间必须短——内容刚进入遮罩时如果遮罩还是半透明，标题就会和背后的列表文字重影；
    // 一旦升满，真正的淡出交给遮罩自身的竖渐变（底部全透明）去完成。
    // 不做补间动画，值在 draw 阶段才读——滚动时只重绘、不重组，跟手且不掉帧。
    val frostSpanPx = with(density) { 28.dp.toPx() }
    val frostAlpha: () -> Float = {
        when {
            selectMode -> 1f
            listState.firstVisibleItemIndex > 0 -> 1f
            else -> (listState.firstVisibleItemScrollOffset / frostSpanPx).coerceIn(0f, 1f)
        }
    }
    val frostColor = MaterialTheme.colorScheme.background
    // 列表内容让位的余量：静止时第一条卡片离分类行底边留 16dp，不会贴着按钮。
    val maskTail = 16.dp
    // 内容让位：固定块 + 余量；底部留出浮动条高度（都以实测为准，换字号也不会错位）
    val topContentPadding = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() } + maskTail
    } else {
        statusTop + 92.dp + maskTail
    }
    // 底部浮动条高度是固定 dp（28dp 上内边距（含 16dp 渐隐带）+ 56dp 按钮 + 12dp 下内边距 = 96dp），
    // 不随字号变化，所以直接算常量，省一次测量回调；再留 16dp 余量，最后一条不会贴到按钮上
    // 归档模式没有底部浮动条，只留导航栏 + 少量余量
    val bottomContentPadding = when {
        selectMode -> 16.dp
        archivedMode -> navBottom + 24.dp
        else -> navBottom + 112.dp
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 顶部磨砂头与底部浮动条都要「压」在内容上，内容必须能滚到状态栏/导航栏底下，
        // 所以系统栏内边距自己接管，不让 Scaffold 先把内容区裁开
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 多选态的批量操作条仍然占位，不做浮动
            if (selectMode) {
                SelectionActionBar(
                    canAct = selected.isNotEmpty(),
                    onSpace = { batchSpaceDialog = true },
                    onCategory = { batchCategoryDialog = true },
                    onShare = {
                        val ids = selected.toList()
                        selectMode = false
                        selected = emptySet()
                        onShare(ids)
                    },
                    onDelete = { batchDeleteDialog = true },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ---------------------------------------------- 整页一个滚动容器
            // 只有列表在这条 LazyColumn 里滚；标题、统计卡、分类行都是上方固定块。
            // 这块列表同时是毛玻璃的「取景源」：标题带和下面每颗玻璃按钮都从这里抓像素做模糊。
            //
            // 外层按分类 + 列表模式 key：切换时整块列表节点连同它内部的 item 动画器一起重建。
            // 不这样做的话，旧行会在 animateItem 的消失动画里被动画器继续持有并绘制，
            // 新分类的卡片只盖住大部分，卡片之间的空隙里会露出上一屏的行（表现为「残留」）。
            key(archivedMode, filter.category, filter.listMode) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .categorySlide({ slide.value }, slideShiftPx)
                    // 整页右滑切上一个分类；左滑不接管，列表行照旧左滑删除
                    .swipeRightToSwitchCategory { if (!selectMode) switchCategory(-1) },
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topContentPadding,
                    bottom = bottomContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------------------------------------- 列表 / 空态
                if (filtered.isEmpty()) {
                    // 空态与列表共用同一套进场动画，切到没有记录的分类时同样有「翻过去了」的反馈
                    item(key = "empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.66f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.DateRange,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    when {
                                        !state.loaded -> "加载中…"
                                        archivedMode -> "暂无归档物品"
                                        else -> if (isCycleMode) "暂无循环任务" else "暂无倒计时物品"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (state.loaded) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        when {
                                            archivedMode -> "在首页左滑列表项到「归档」即可归档"
                                            isCycleMode -> "点底部 + 添加，并在编辑页打开「循环任务」"
                                            else -> "点底部 + 添加第一件物品"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    sections.forEach { (status, items) ->
                        // 归档页 status 为 null：不画分组标题，直接铺卡片
                        if (status != null) item(key = "header_${status.name}") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(hexColor(status.color), CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    status.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${items.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(items.size, key = { items[it].id }) { index ->
                            val item = items[index]
                            // 新增/删除/移动时条目自带位移动画，接近流体动效的连续感。
                            // 但不要淡出：切分类时整批行会同时消失，消失动画会让旧行在
                            // 卡片之间的空隙里多留一会儿（表现为上一屏的行「残留」）
                            Box(Modifier.animateItem(fadeOutSpec = null)) {
                                ItemRow(
                                    item = item,
                                    store = store,
                                    archivedMode = archivedMode,
                                    selectMode = selectMode,
                                    selected = item.id in selected,
                                    // 「全部空间」下显示归属空间，选定某个空间后行内不再重复
                                    showSpace = filter.space == Spaces.ALL,
                                    onClick = {
                                        if (selectMode) {
                                            selected =
                                                if (item.id in selected) selected - item.id
                                                else selected + item.id
                                        } else onEdit(item.id)
                                    },
                                    onLongClick = {
                                        if (!selectMode && !archivedMode) {
                                            selectMode = true
                                            selected = setOf(item.id)
                                        }
                                    },
                                    // 沉降到确认位只上报，确认框由页面层统一弹
                                    onAskSwipe = { delete -> swipeAsk = SwipeAsk(item, delete) },
                                    swipeResetToken = swipeResetToken,
                                    onRestore = {
                                        scope.launch {
                                            store.setItemsArchived(context, listOf(item.id), false)
                                            Toast.makeText(
                                                context, "已调回「${item.name}」", Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                    onCompleteCycle = {
                                        scope.launch {
                                            val next = store.completeCycle(context, item.id)
                                            if (next != null) {
                                                Toast.makeText(
                                                    context, "已完成本次，下次到期 $next",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                // 当天重复点击或无需推进：给出反馈而不是静默
                                                Toast.makeText(
                                                    context, "今天已经完成过本次了",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                    onViewImages = { origin ->
                                        viewer = ViewerState(item.images.map { it.path }, 0, origin)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            } // key(archivedMode, filter.category, filter.listMode)

            // ---------------------------------------------- 顶部固定遮罩（渐变淡出）
            // 固定在页面顶部：实心部分盖住标题与统计卡（挡住滚上来的卡片）。
            // 淡出带只允许出现在「统计卡底 → 分类行顶」那条空隙里——
            // 分类行这一整条容器必须完全透明（用户要求，勿回退）：
            // 按钮背后不许有任何遮罩/底色，透出来的是清晰的卡片，
            // 边界只靠按钮自己的立体描边 + 投影区分。
            // 不用第三方模糊库——稳定和兼容优先，卡片在这里被实色「吃掉」而不是被模糊。
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { topBarHeightPx = it.size.height }
                    .drawBehind {
                        val a = frostAlpha()
                        if (a <= 0f) return@drawBehind
                        // 统计栏下边界的透明过渡：从统计卡底边开始一路淡到固定块底边（分类行底边）归零。
                        // 注意区分两件事：分类行 Row 自身没有任何底色/不透明度（容器是透明的），
                        // 这里画的是父层遮罩的渐隐——滚上来的卡片在分类行区域里被逐渐吃掉，
                        // 而不是整条清晰穿过、和按钮文字互相叠字。
                        val body = if (catRowTopPx > 0) catRowTopPx.toFloat() else size.height
                        val total = size.height.coerceAtLeast(1f)
                        val solidEnd = (body / total).coerceIn(0.02f, 1f)
                        val tailFrac = 1f - solidEnd
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to frostColor.copy(alpha = a),
                                solidEnd to frostColor.copy(alpha = a),
                                // 按钮区上半段就先掉到三成：文字不会再和按钮标题叠在一起；
                                // 到分类行底边归零，过渡是连续的
                                (solidEnd + tailFrac * 0.5f) to frostColor.copy(alpha = a * 0.30f),
                                1f to frostColor.copy(alpha = 0f),
                            ),
                            size = Size(size.width, total),
                        )
                    },
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (selectMode) {
                        // ---------------------------------- 多选态头部
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "退出多选")
                            }
                            Text(
                                "已选 ${selected.size} 项",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            // 全选/取消全选：列表当前筛选结果里的全部物品
                            TextButton(
                                onClick = {
                                    val all = filtered.map { it.id }.toSet()
                                    selected = if (selected.containsAll(all) && all.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        all
                                    }
                                },
                            ) {
                                val all = filtered.map { it.id }.toSet()
                                Text(
                                    if (selected.containsAll(all) && all.isNotEmpty()) "取消全选"
                                    else "全选"
                                )
                            }
                        }
                    } else {
                        // ---------------------------------- 常规头部：标题 + 空间 + 设置
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 归档页是二级页：左上给一颗与其它胶囊同款的返回键，
                                // 不再只依赖系统返回/左缘手势（那是唯一没有可见返回入口的页面）
                                if (onBack != null) {
                                    Box(
                                        Modifier
                                            .height(40.dp)
                                            .frostedPanel()
                                            .clickable(onClick = onBack),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxHeight()
                                                .padding(horizontal = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "返回",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    if (archivedMode) "归档" else "到期提醒",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                // 标题块与右侧按钮组之间留固定呼吸位，标题长短都不挤在一起
                                Spacer(Modifier.width(6.dp))
                                // 空间切换：当前空间名 + 下拉（同时是空间管理的入口）。
                                // 与分类行三颗按钮同款毛玻璃：同一屏里只保留一种按钮语言。
                                // 归档模式同样支持按空间筛选（只统计已归档物品）。
                                Box {
                                    Box(
                                        Modifier
                                            .height(40.dp)
                                            .frostedPanel()
                                            .clickable { spaceMenuOpen = true },
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxHeight()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        hexColor(store.spaceColorOf(filter.space)),
                                                        CircleShape,
                                                    ),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                store.spaceLabelOf(filter.space),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 64.dp),
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                Icons.Filled.KeyboardArrowDown,
                                                contentDescription = "切换空间",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = spaceMenuOpen,
                                        onDismissRequest = { spaceMenuOpen = false },
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        // 全部空间（默认）：所有空间 + 未分配的物品一起看
                                        DropdownMenuItem(
                                            text = { Text(Spaces.ALL_LABEL) },
                                            leadingIcon = {
                                                Box(
                                                    Modifier.size(10.dp).background(
                                                        hexColor(store.spaceColorOf(Spaces.ALL)),
                                                        RoundedCornerShape(5.dp),
                                                    )
                                                )
                                            },
                                            trailingIcon = { CountBadge(spaceCount(Spaces.ALL)) },
                                            onClick = {
                                                if (filter.space != Spaces.ALL) {
                                                    filter.space = Spaces.ALL
                                                    filter.category = HomeFilterState.ALL
                                                }
                                                spaceMenuOpen = false
                                            },
                                        )
                                        androidx.compose.material3.HorizontalDivider()
                                        for (space in store.spaceOptions()) {
                                            DropdownMenuItem(
                                                text = { Text(space.label) },
                                                leadingIcon = {
                                                    Box(
                                                        Modifier.size(10.dp).background(
                                                            hexColor(space.color), RoundedCornerShape(5.dp),
                                                        )
                                                    )
                                                },
                                                trailingIcon = {
                                                    CountBadge(spaceCount(space.id))
                                                },
                                                onClick = {
                                                    if (filter.space != space.id) {
                                                        filter.space = space.id
                                                        // 分类计数跟着空间变，切空间后回到「全部分类」最不容易迷惑
                                                        filter.category = HomeFilterState.ALL
                                                    }
                                                    spaceMenuOpen = false
                                                },
                                            )
                                        }
                                        androidx.compose.material3.HorizontalDivider()
                                        // 未分配：物品可以不属于任何空间
                                        DropdownMenuItem(
                                            text = { Text(Spaces.UNASSIGNED_LABEL) },
                                            leadingIcon = {
                                                Box(
                                                    Modifier.size(10.dp).background(
                                                        hexColor(store.spaceColorOf("")),
                                                        RoundedCornerShape(5.dp),
                                                    )
                                                )
                                            },
                                            trailingIcon = { CountBadge(spaceCount("")) },
                                            onClick = {
                                                if (filter.space.isNotEmpty()) {
                                                    filter.space = ""
                                                    filter.category = HomeFilterState.ALL
                                                }
                                                spaceMenuOpen = false
                                            },
                                        )
                                        // 按空间分享只在主页提供：归档物品不参与批量分享
                                        if (!archivedMode) {
                                            androidx.compose.material3.HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("按空间分享…") },
                                                onClick = {
                                                    spaceMenuOpen = false
                                                    shareSpaceDialog = true
                                                },
                                            )
                                        }
                                        // 空间管理是主页入口：归档页的 HomeScreen 拿不到 onSpaces，
                                        // 显示出来只会是一个点了没反应的死项
                                        if (!archivedMode) DropdownMenuItem(
                                            text = { Text("管理空间…") },
                                            onClick = {
                                                spaceMenuOpen = false
                                                onSpaces()
                                            },
                                        )
                                    }
                                }
                                // 归档入口：在「全部空间」右边，同款磨砂浮起（40dp 高）；
                                // 归档模式已在本页，不再显示入口。
                                Spacer(Modifier.width(6.dp))
                                if (!archivedMode) {
                                    Box(
                                        Modifier
                                            .height(40.dp)
                                            .frostedPanel()
                                            .clickable(onClick = onArchive),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxHeight()
                                                .padding(horizontal = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "归档",
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    // 与设置圆钮之间留 6dp
                                    Spacer(Modifier.width(6.dp))
                                }
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .frostedPanel(shape = CircleShape)
                                        .clickable(onClick = onSettings),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "设置",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (archivedMode) "已归档的物品不再提醒，可调回或删除"
                                else "食品、药品、证件的有效期管理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // ------------------------------------------ 统计卡（数量与种类由设置决定）
                // 归档模式不适用（「7 天内/已过期」等口径只对未归档列表有意义），隐藏
                if (!archivedMode) Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 尺寸固定成一套紧凑值：张数增减时整行高度不变，
                    // 不要按张数切换大小，否则整条统计栏会跟着长高/变矮。
                    for (card in statCards) {
                        StatCardView(
                            count = card.status?.let { countOf(grouped, it) } ?: filtered.size,
                            label = card.label,
                            accent = card.status?.let { hexColor(it.color) }
                                ?: MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ------------------------------------------ 列表模式切换：倒计时 / 循环任务
                // 归档模式不分列表模式，隐藏
                if (!archivedMode) ListModeSwitcher(
                    mode = filter.listMode,
                    countdownCount = countdownCount,
                    cycleCount = cycleCount,
                    onChange = switchListMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )

                // ------------------------------------------ 分类切换器 + 分类/多选
                // 这一整行容器完全透明（用户要求，勿回退）：顶部遮罩的淡出带
                // 只允许出现在它上方那条空隙里，容器内不许有任何遮罩/底色。
                // 三颗按钮各自是半透明面板，边界靠描边与投影区分。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            // 相对外层固定块的位置：遮罩实心部分到这一行顶边为止，
                            // 这一行容器内部零遮罩（透明由按钮自身面板负责）
                            catRowTopPx = it.positionInParent().y.toInt()
                        }
                        .padding(start = 16.dp, end = 16.dp, top = CategoryRowTopPadding),
                    // 这一行常规模式放四个控件（分类下拉 / 分类管理 / 多选 + 头部归档入口），
                    // 间距与内边距都压到 8/10dp，保证窄屏上分类名不会被挤成省略号；
                    // 归档模式只留分类下拉一颗
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 分类下拉：点开菜单直选。切换分类靠整页右滑，这里不再放左右箭头
                    Box(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .frostedPanel()
                                .clickable { categoryMenuOpen = true },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 容器宽度定死，这里换文字不会引起布局跳动；
                                // 竖向滚动淡入，和列表的横向滑动呼应出「翻页」的方向感
                                AnimatedContent(
                                    // 用「文字+色点颜色」做 key，色点随标签一起滚动切换
                                    targetState = categoryLabel to categoryDotColor,
                                    transitionSpec = {
                                        val up = slideDir > 0
                                        (slideInVertically(tween(180)) { h -> if (up) h else -h } +
                                            fadeIn(tween(180))) togetherWith
                                            (slideOutVertically(tween(140)) { h -> if (up) -h else h } +
                                                fadeOut(tween(120)))
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = "categoryLabel",
                                ) { (label, dotColor) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 与下拉候选项同款：10dp 圆角方块色点；「全部分类」用它的颜色覆盖
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .background(
                                                    hexColor(dotColor),
                                                    RoundedCornerShape(5.dp),
                                                )
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.KeyboardArrowDown, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = categoryMenuOpen,
                            onDismissRequest = { categoryMenuOpen = false },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            // 每一项右侧显示该分类的记录数（未归档，随搜索词联动）
                            DropdownMenuItem(
                                text = { Text(Categories.ALL_LABEL) },
                                // 与其它分类同一位置、同尺寸的色点（颜色在分类管理里可改）
                                leadingIcon = {
                                    Box(
                                        Modifier.size(10.dp).background(
                                            hexColor(store.categoryColorOf(HomeFilterState.ALL)),
                                            RoundedCornerShape(5.dp),
                                        )
                                    )
                                },
                                trailingIcon = { CountBadge(keywordMatched.size) },
                                onClick = {
                                    selectCategory(HomeFilterState.ALL)
                                    categoryMenuOpen = false
                                },
                            )
                            for (cat in store.categoryOptions()) {
                                DropdownMenuItem(
                                    text = { Text(cat.label) },
                                    leadingIcon = {
                                        Box(
                                            Modifier.size(10.dp).background(
                                                hexColor(cat.color), RoundedCornerShape(5.dp),
                                            )
                                        )
                                    },
                                    trailingIcon = { CountBadge(categoryCounts[cat.id] ?: 0) },
                                    onClick = {
                                        selectCategory(cat.id)
                                        categoryMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    // 分类管理（独立页面）：与搜索框同一套磨砂浮起效果（归档模式不适用）
                    if (!archivedMode) Box(
                        Modifier
                            .frostedPanel()
                            .clickable(onClick = onCategories),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "分类管理",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }

                    // 多选 / 退出多选（同一颗按钮切换；归档模式不提供多选）
                    val accent = if (selectMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                    if (!archivedMode) Box(
                        Modifier
                            .frostedPanel(
                                // 多选态用红色描边提示「再点一次退出」；平时走默认立体渐变边
                                borderColor = if (selectMode) MaterialTheme.colorScheme.error else null,
                            )
                            .clickable {
                                if (selectMode) {
                                    selectMode = false
                                    selected = emptySet()
                                } else {
                                    selectMode = true
                                }
                            },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selectMode) Icons.Filled.Close else Icons.Filled.Check,
                                contentDescription = null,
                                tint = accent, modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (selectMode) "退出多选" else "多选",
                                color = accent,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------- 底部浮动操作区
            // 归档模式没有新增/拍照/搜索，不显示
            if (!selectMode && !archivedMode) {
                HomeFloatingBar(
                    keyword = filter.keyword,
                    onKeywordChange = { filter.keyword = it },
                    onOcr = { onOcr(targetCategory, targetSpace) },
                    onAdd = { onAdd(targetCategory, targetSpace) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // 多选：批量切换空间
    if (batchSpaceDialog) {
        BatchSpaceDialog(
            count = selected.size,
            onPick = { spaceId ->
                batchSpaceDialog = false
                val ids = selected.toList()
                scope.launch {
                    val moved = store.moveItemsToSpace(context, ids, spaceId)
                    selectMode = false
                    selected = emptySet()
                    Toast.makeText(
                        context,
                        "已把 $moved 件移到「${store.spaceLabelOf(spaceId)}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { batchSpaceDialog = false },
        )
    }

    // 多选：批量切换分类
    if (batchCategoryDialog) {
        BatchCategoryDialog(
            count = selected.size,
            onPick = { categoryId ->
                batchCategoryDialog = false
                val ids = selected.toList()
                scope.launch {
                    val changed = store.setItemsCategory(context, ids, categoryId)
                    selectMode = false
                    selected = emptySet()
                    Toast.makeText(
                        context,
                        "已把 $changed 件改为「${store.categoryLabelOf(categoryId)}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { batchCategoryDialog = false },
        )
    }

    // 多选：批量删除，先确认一次
    if (batchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { batchDeleteDialog = false },
            title = { Text("删除 ${selected.size} 件物品") },
            text = { Text("相关图片也会一并删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    batchDeleteDialog = false
                    val ids = selected.toList()
                    scope.launch {
                        store.removeItems(context, ids)
                        selectMode = false
                        selected = emptySet()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    // 按空间分享：把一个空间下的物品整体发到对方设备
    if (shareSpaceDialog) {
        ShareSpaceDialog(
            onPick = { spaceId ->
                shareSpaceDialog = false
                val ids = store.itemsInSpace(spaceId).map { it.id }
                if (ids.isEmpty()) {
                    Toast.makeText(context, "该空间下没有可分享的物品", Toast.LENGTH_SHORT).show()
                } else {
                    onShare(ids)
                }
            },
            onDismiss = { shareSpaceDialog = false },
        )
    }

    // 左滑确认（归档 / 删除）：弹框在页面层，列表滚动把行回收掉也不会把框带走
    swipeAsk?.let { ask ->
        val target = ask.item
        AlertDialog(
            onDismissRequest = { closeSwipeAsk() },
            title = { Text(if (ask.delete) "删除物品" else "归档物品") },
            text = {
                Text(
                    if (ask.delete) {
                        "确定删除「${target.name}」吗？相关图片也会一并删除。"
                    } else {
                        "确定将「${target.name}」归档吗？归档后不再出现在列表与提醒中，可在归档中调回。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    swipeAsk = null
                    scope.launch {
                        if (ask.delete) {
                            store.removeItem(context, target.id)
                        } else {
                            store.setItemsArchived(context, listOf(target.id), true)
                            Toast.makeText(
                                context, "已归档「${target.name}」", Toast.LENGTH_SHORT,
                            ).show()
                        }
                        // 删除/归档都不该再留通知栏里那条提醒
                        ReminderService.cancelNotificationsFor(context, listOf(target.id))
                    }
                }) {
                    Text(
                        if (ask.delete) "删除" else "归档",
                        color = if (ask.delete) MaterialTheme.colorScheme.error
                        else Color.Unspecified,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { closeSwipeAsk() }) { Text("取消") }
            },
        )
    }

    viewer?.let { state ->
        ImageViewerOverlay(
            paths = state.paths,
            initialIndex = state.index,
            origin = state.origin,
            onDismiss = { viewer = null },
        )
    }
}

// frostedPanel 已提取到 Common.kt 作为全站公共 modifier，首页/编辑页/分类页/空间页共用同一套

/**
 * 分类行 Row 的顶部内边距，同时是顶部遮罩淡出带的高度：
 * 淡出必须完全发生在「统计卡底 → 分类行顶」这条空隙里，一点不许侵进分类行容器。
 * 两处共用一个常量，改一处必须同步另一处。
 */
private val CategoryRowTopPadding = 14.dp

private fun countOf(
    grouped: List<Pair<ExpiryStatus, List<ExpiryItem>>>,
    status: ExpiryStatus,
): Int = grouped.firstOrNull { it.first == status }?.second?.size ?: 0

/**
 * 列表模式切换器：一条磨砂胶囊里两等分「倒计时 / 循环任务」，各自带当前空间的条数。
 * 选中段用主色淡底 + 主色粗字，未选中段透明；文字在各自半区内水平、垂直双居中，与分类行同一套 frostedPanel 风格。
 */
@Composable
private fun ListModeSwitcher(
    mode: String,
    countdownCount: Int,
    cycleCount: Int,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .frostedPanel()
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val tabs = listOf(
            Triple(HomeFilterState.MODE_COUNTDOWN, "倒计时", countdownCount),
            Triple(HomeFilterState.MODE_CYCLE, "循环任务", cycleCount),
        )
        tabs.forEach { (modeKey, label, count) ->
            val active = mode == modeKey
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else Color.Transparent,
                    )
                    .clickable { onChange(modeKey) }
                    .padding(vertical = 11.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 底部浮动操作区：搜索胶囊 + 独立拍照圆钮 + 绿色新增圆钮。
 *
 * 三者各自成块浮在内容之上（不共用一条底板），高度统一 56dp、间距统一 10dp，
 * 固定吸底不随列表滚动；搜索占满左侧剩余宽度，拍照夹在中间。
 * 整条背后压一层「透明 → 背景色」的渐隐，列表滚到底时自然化开，不会撞到按钮上。
 * 键盘弹出时被顶到键盘上方，边输边看结果。
 */
@Composable
private fun HomeFloatingBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onOcr: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            // 底板彻底透明：只有三个控件本身浮在内容上，列表内容从它们之间和下面穿过
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 搜索胶囊：半透明磨砂白 + 亮边 + 柔和投影，没有底板也不会糊在内容里
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .frostedPanel(RoundedCornerShape(28.dp)),
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (keyword.isEmpty()) {
                            Text(
                                "搜索名称或备注",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = keyword,
                            onValueChange = onKeywordChange,
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (keyword.isNotEmpty()) {
                        IconButton(
                            onClick = { onKeywordChange("") },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清空搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            // 拍照：从搜索框里独立出来的浮动圆钮，夹在搜索和新增中间
            FloatCircleButton(
                size = 56.dp,
                fill = MaterialTheme.colorScheme.surface,
                elevation = 12.dp,
                description = "拍照识别",
                onClick = onOcr,
            ) {
                CameraGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    holeColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(24.dp),
                )
            }
            // 新增：与搜索框同一套磨砂浮起效果，+ 号用主色保持主操作辨识度
            FloatCircleButton(
                size = 56.dp,
                fill = MaterialTheme.colorScheme.surface,
                elevation = 12.dp,
                description = "新增",
                onClick = onAdd,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** 浮动圆钮：磨砂底 + 阴影 + 立体描边 + 圆形裁切，尺寸、阴影由调用方定。 */
@Composable
private fun FloatCircleButton(
    size: Dp,
    fill: Color,
    elevation: Dp,
    description: String,
    onClick: () -> Unit,
    borderColor: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = description }
            .frostedPanel(
                shape = CircleShape,
                elevation = elevation,
                fill = fill,
                borderColor = borderColor,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 切分类的进场效果：整块内容沿滑动方向滑入并淡入（progress 为 0 时完全归位）。
 * 读的是 [Animatable] 的值，在 draw 阶段才订阅，不会每帧触发重组。
 */
private fun Modifier.categorySlide(progress: () -> Float, shiftPx: Float): Modifier =
    graphicsLayer {
        val p = progress()
        translationX = p * shiftPx
        alpha = 1f - 0.6f * abs(p)
    }

/**
 * 整页右滑 = 切到上一个有记录的分类；左滑不接管，交给列表行自己的左滑删除。
 *
 * 为什么要在 Initial 阶段手工判定：列表行的横向手势归 [SwipeToDismissBox]（anchoredDraggable，
 * 两个方向都会被它吃掉），普通 drag 检测挂在父亲上抢不过子节点。这里先在 Initial 阶段
 * 看一眼方向——向右才 consume（行拿到的位移已被消费，不会跟着动），向左/上下直接放手，
 * 删除和滚动完全不受影响。
 */
@Composable
private fun Modifier.swipeRightToSwitchCategory(onPrevious: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPrevious)
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var decided = false
            var capturing = false
            var done = false
            while (!done) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.positionChangeIgnoreConsumed()
                dx += delta.x
                dy += delta.y
                if (!decided && (abs(dx) > slop || abs(dy) > slop)) {
                    decided = true
                    // 只有明确「向右且比上下位移更大」才接管，其余一律放手
                    capturing = dx > 0 && dx > abs(dy)
                    if (!capturing) done = true
                }
                if (capturing) change.consume()
                if (change.changedToUpIgnoreConsumed()) done = true
            }
            if (capturing && dx >= threshold) latest()
        }
    }
}

/** 分类下拉里的记录数：右对齐的灰色小字，0 条时更淡。 */
@Composable
private fun CountBadge(count: Int) {
    Text(
        "$count",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (count == 0) 0.45f else 1f,
        ),
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = 16.dp).widthIn(min = 14.dp),
    )
}

/**
 * 参考稿统计卡：白底、居中彩色数字 + 灰色标签。
 * 尺寸固定成一套紧凑值（原来 5 张时的规格）——张数变化时行高、字号都不变，
 * 不要再按张数切换大小，否则选卡数量一变整条统计栏就跟着长高/变矮。
 */
@Composable
private fun StatCardView(
    count: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // 与搜索框、分类行同一套磨砂浮起效果：半透明面板 + 立体描边 + 柔投影。
    // 统计卡要放大数字，透太多会掉可读性，所以面板压得更实（0.92）
    Box(modifier.frostedPanel(fillAlpha = 0.92f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ItemRow(
    item: ExpiryItem,
    store: ExpiryStore,
    /** 归档页模式：左滑只剩「删除」一段，右侧显示「调回」按钮，不显示「完成本次」。 */
    archivedMode: Boolean = false,
    selectMode: Boolean,
    selected: Boolean,
    /** 「全部空间」筛选下显示归属空间，否则列表里分不清哪件属于哪个空间。 */
    showSpace: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /**
     * 左滑沉降到确认位时上报：true = 删除段，false = 归档段。
     * 确认框由页面层弹（不挂在行内），列表滚动把行回收掉也不会丢框；
     * 页面层取消/处理完把 [swipeResetToken] +1，行据此回弹。
     */
    onAskSwipe: (delete: Boolean) -> Unit = {},
    /** 每次自增表示「确认框已关闭」，行收到变化就回弹到原位。 */
    swipeResetToken: Int = 0,
    /** 归档页模式：点「调回」恢复到正常列表。 */
    onRestore: () -> Unit = {},
    /** 循环物品：完成本次维护，推进到下一周期。 */
    onCompleteCycle: () -> Unit = { },
    /** 点击缩略图看大图，带回缩略图的屏幕位置供查看器做展开/缩回转场。 */
    onViewImages: (ImageOrigin?) -> Unit,
) {
    val days = Dates.daysUntil(item.expireAt)
    val status = store.statusOf(item)
    // 缩略图在屏幕上的位置：布局后记录，点击看大图时交给查看器作为动画起点/终点。
    // 圆角必须与缩略图一致（8dp），否则缩回终点形状不符会闪一下。
    val thumbRadiusPx = with(LocalDensity.current) { 8.dp.toPx() }
    var thumbOrigin by remember { mutableStateOf<ImageOrigin?>(null) }

    // ---------------------------------------------------------------- 左滑两段式
    // 中段 = 归档（黄底「归档」），滑满 = 删除（红底「删除」）。松手后沉降到哪个锚点，
    // 就弹哪个确认框；取消则回弹到原位。整体替换掉 SwipeToDismissBox：它只有一个确认锚点，
    // 撑不起「滑一半归档、继续滑删除」的两段语义。
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val swipeState = remember(item.id) {
        AnchoredDraggableState(initialValue = RowSwipeStage.Settled)
    }
    // 锚点依赖行宽（首次布局后才有），归档页模式少一个「归档」锚点
    LaunchedEffect(rowWidthPx, archivedMode) {
        if (rowWidthPx > 0) {
            swipeState.updateAnchors(
                DraggableAnchors {
                    RowSwipeStage.Settled at 0f
                    if (!archivedMode) RowSwipeStage.Archive at -rowWidthPx * SwipeArchiveFrac
                    RowSwipeStage.Delete at -rowWidthPx * SwipeDeleteFrac
                },
            )
        }
    }
    // 沉降结果 → 上报页面层弹确认框；取消后由 swipeResetToken 驱动回弹。
    // 用 rememberUpdatedState 取最新回调：collect 的闭包只在 swipeState 变化时重建，
    // 否则会一直拿着首次组合时的 onAskSwipe（以及它捕获的旧 item）
    val latestAskSwipe by rememberUpdatedState(onAskSwipe)
    LaunchedEffect(swipeState) {
        snapshotFlow { swipeState.settledValue }.collect { stage ->
            when (stage) {
                RowSwipeStage.Archive -> latestAskSwipe(false)
                RowSwipeStage.Delete -> latestAskSwipe(true)
                RowSwipeStage.Settled -> {}
            }
        }
    }
    // 页面层关掉确认框（取消 / 已执行）都会自增 token；行若停在确认位就回弹
    LaunchedEffect(swipeResetToken) {
        if (swipeResetToken > 0 && swipeState.settledValue != RowSwipeStage.Settled) {
            swipeState.animateTo(RowSwipeStage.Settled)
        }
    }
    // 左滑进度（0..1）：底色与文案切换、显隐全部在绘制阶段读，拖动时不触发重组
    val swipeFrac: () -> Float = {
        val w = rowWidthPx.toFloat()
        val off = swipeState.offset
        if (w <= 0f || off.isNaN()) 0f else (-off / w).coerceIn(0f, 1f)
    }
    // 底色显隐：offset 归零（含锚点未就绪的 NaN）时完全不画，避免抗锯齿圆角渗色边
    val bgAlpha: () -> Float = {
        val off = swipeState.offset
        if (off.isNaN() || off == 0f) 0f else 1f
    }

    // 卡片圆角：色条内缩量、底色裁切都用它，避免三处各写一个数字后对不齐
    val cardShape = RoundedCornerShape(24.dp)

    Box(Modifier.onSizeChanged { rowWidthPx = it.width }) {
        // ------------------------------------------ 左滑底色（黄=归档段 / 红=删除段）
        // 底色比卡片内缩 2dp：卡片圆角是抗锯齿边缘，两者严丝合缝对齐时
        // 下层总会渗出 1px 色边，内缩后底色的圆角完全落在卡片圆角以内。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = bgAlpha() }
                .padding(2.dp)
                .clip(cardShape),
        ) {
            // 归档段（黄底）：首页模式才有；删除段盖在它上面，切换处用透明度硬切
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (!archivedMode && swipeFrac() < SwipeDeleteSwitchFrac) 1f else 0f
                    }
                    .background(ArchiveSwipeColor),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "归档",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
            // 删除段（红底）：保留原来的 error 色。
            // 归档页没有「归档」段，红底必须从 0 就跟手出现，否则拖到切换点之前是一片空白。
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (archivedMode || swipeFrac() >= SwipeDeleteSwitchFrac) 1f else 0f
                    }
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 20.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "删除",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ------------------------------------------ 卡片本体（跟手位移）
        Box(
            Modifier
                // 锚点还没就绪时 offset 是 NaN，roundToInt 会抛 IllegalArgumentException，先兜成 0
                .offset {
                    val off = swipeState.offset
                    IntOffset(if (off.isNaN()) 0 else off.roundToInt(), 0)
                }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    // 多选态下整行都是「点选」区域，禁止左滑，避免露出底色
                    enabled = !selectMode,
                )
                .fillMaxWidth()
                .shadow(
                    elevation = 10.dp,
                    shape = cardShape,
                    clip = false,
                    ambientColor = Color(0x12000000),
                    spotColor = Color(0x26000000),
                )
                .background(
                    // 选中色要和底色混成不透明色，否则会透出后面的内容
                    if (selected) {
                        androidx.compose.ui.graphics.lerp(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary,
                            0.10f,
                        )
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    cardShape,
                )
                .clip(cardShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(
                Modifier
                    // 分类色条：长度对齐卡片左边框的「直线段」——上下各缩一个圆角半径，
                    // 正好落在左右两段圆弧的起止点之间；卡片特别矮时保底 24dp，色条不会被挤没。
                    // 直接 drawBehind 画，不参与布局：行高由内容自然决定，不再需要
                    // IntrinsicSize.Min + fillMaxHeight 那一套（省掉一次内建测量）。
                    .drawBehind {
                        val barColor = hexColor(store.categoryColorOf(item.category))
                        val minBar = 24.dp.toPx()
                        val inset = ((size.height - minBar) / 2f)
                            .coerceIn(0f, 24.dp.toPx())
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(8.dp.toPx(), inset),
                            size = Size(4.dp.toPx(), (size.height - inset * 2).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 原来「8dp 内缩 + 4dp 色条」占的 12dp：色条改由 drawBehind 画，这里只留空位
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // 名称 + 数量：名称溢出时换行（最多 2 行，再长才省略），
                        // 数量是不带 weight 的先测量子项，永远拿到自己的宽度，不会被名称挤掉；
                        // 名称用 fill=false，短名称只占自身宽度，数量紧跟其后而不是被推到最右。
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (item.quantity > 1) {
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "×${item.quantity}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    // 与名称首行基线大致对齐（titleMedium 16sp vs bodyMedium 14sp）
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        // 备注：紧贴名称下方当副标题，只占一行，多行的数据压成单行后超出用省略号
                        val noteLine = item.note.trim().replace(SINGLE_LINE_WS, " ")
                        if (noteLine.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                noteLine,
                                style = MaterialTheme.typography.bodySmall,
                                // 比元信息略深一档，读起来是「内容」而不是「标签」
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 空间：只在归属了空间时显示，名称带空间色；未分配不占位置
                            val spaceDef = if (showSpace) store.spaceOf(item.spaceId) else null
                            if (spaceDef != null) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(hexColor(spaceDef.color)),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    spaceDef.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = hexColor(spaceDef.color),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "·",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            // 「N 张图」放到右侧剩余天数下方；数量已挪到名称右边，这里都不再重复
                            val meta = buildList {
                                add(store.categoryLabelOf(item.category))
                                if (item.repeatEnabled) {
                                    val cycleUnit = when (item.shelfLifeUnit) {
                                        ShelfLifeUnit.day -> "天"
                                        ShelfLifeUnit.month -> "月"
                                        ShelfLifeUnit.year -> "年"
                                    }
                                    add("循环 · 每${item.shelfLife}${cycleUnit}")
                                }
                                if (item.expireAt.isNotEmpty()) add(item.expireAt)
                            }
                            Text(
                                meta.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 提前提醒档位：如「提前 30/7/1 天」，未设置时不占位
                        remindDaysSummary(item.remindDays)?.let { summary ->
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    if (selectMode) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                )
                                .border(
                                    1.5.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (item.expireAt.isNotEmpty() || item.images.isNotEmpty()) {
                        // 右列（自上而下）：剩余天数 → 「张数 + 缩略图」同一行
                        Column(
                            Modifier.padding(start = 8.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            if (item.expireAt.isNotEmpty()) {
                                Text(
                                    Dates.describeRemaining(item.expireAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = hexColor(status.color),
                                )
                            }
                            if (item.repeatEnabled && !selectMode && !archivedMode) {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    onClick = onCompleteCycle,
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Check, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            "完成本次",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            if (archivedMode) {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    onClick = onRestore,
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Refresh, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            "调回",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            if (item.images.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                // 张数与缩略图同一行：张数在左、缩略图在右，保证缩略图与上方天数同一右边界
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${item.images.size} 张图",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // 缩略图：点击直接看大图（多张时在查看器里翻页）
                                    Surface(
                                        onClick = { onViewImages(thumbOrigin) },
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            val pos = coords.positionInWindow()
                                            thumbOrigin = ImageOrigin(
                                                x = pos.x,
                                                y = pos.y,
                                                width = coords.size.width.toFloat(),
                                                height = coords.size.height.toFloat(),
                                                cornerRadius = thumbRadiusPx,
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outline,
                                        ),
                                    ) {
                                        DataImage(item.images.first().path, 44.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 多选态底部批量操作条：空间 / 分类 / 分享 / 删除。 */
@Composable
private fun SelectionActionBar(
    canAct: Boolean,
    onSpace: () -> Unit,
    onCategory: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction("空间", canAct, onSpace)
            SelectionAction("分类", canAct, onCategory)
            SelectionAction("分享", canAct, onShare)
            SelectionAction("删除", canAct, onDelete, danger = true)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Text(
            text,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * 多选批量改空间。
 * 列表只给「已有空间 + 未分配」，与单据的空间语义一致；要新建空间去「管理空间」。
 */
@Composable
private fun BatchSpaceDialog(count: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.spaceOptions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动 $count 件到") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (space in options) {
                    PickRow(space.label, space.color) { onPick(space.id) }
                }
                PickRow(
                    Spaces.UNASSIGNED_LABEL,
                    Spaces.UNASSIGNED_COLOR,
                ) { onPick("") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 多选批量改分类。 */
@Composable
private fun BatchCategoryDialog(count: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.categoryOptions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改为分类（$count 件）") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (option in options) {
                    PickRow(option.label, option.color) { onPick(option.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 按空间分享：选空间 → 把该空间的物品整体带给传输页。 */
@Composable
private fun ShareSpaceDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.spaceOptions() }
    // 只算未归档的：与「发送」实际带走的条数一致
    val total = store.itemsInSpace(Spaces.ALL).size
    val unassigned = store.itemsInSpace("").size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按空间分享") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "选一个空间，把它下面的物品整体发到对方设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (total == 0) {
                    Text(
                        "还没有任何物品可以分享",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (space in options) {
                    val count = store.itemsInSpace(space.id).size
                    if (count > 0) {
                        PickRow(space.label, space.color, trailing = "$count 件") { onPick(space.id) }
                    }
                }
                if (unassigned > 0) {
                    PickRow(
                        Spaces.UNASSIGNED_LABEL,
                        Spaces.UNASSIGNED_COLOR,
                        trailing = "$unassigned 件",
                    ) { onPick("") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 弹窗里的可点选行：颜色点 + 名称 + 可选右侧计数。 */
@Composable
private fun PickRow(
    label: String,
    color: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(hexColor(color), RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 从 Compose 的 Context 向上找 Activity（可能被 ContextThemeWrapper 包裹）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
