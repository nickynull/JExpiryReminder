@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.core.remindDaysSummary
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 备注里的换行、制表、连续空格：列表只显示一行，先压成单个空格。 */
private val SINGLE_LINE_WS = Regex("\\s+")

/**
 * 首页筛选状态（搜索词 + 分类）：由导航层持有，进出编辑页后回来仍保留。
 * 用 listSaver 存两颗字符串，进程被回收再恢复也不丢。
 */
@Stable
class HomeFilterState(keyword: String = "", category: String = "all") {
    var keyword by mutableStateOf(keyword)
    var category by mutableStateOf(category)

    companion object {
        const val ALL = "all"

        val Saver = listSaver<HomeFilterState, String>(
            save = { listOf(it.keyword, it.category) },
            restore = { HomeFilterState(it[0], it[1]) },
        )
    }
}

/** 参考稿样式首页：白底标题区 + 统计卡 + 搜索/筛选 + 状态分组列表。
 *
 * [filter] 由调用方持有，保证切页回来筛选不丢；
 * [onAdd] 会带上当前筛选的分类 id（「全部分类」时为 null），新增页据此预选分类。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    filter: HomeFilterState,
    onAdd: (defaultCategory: String?) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onCategories: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val settings = SettingsStore.get()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<ExpiryItem?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    // 多选态下返回 = 退出多选；主页返回 = 两次才退出（防误触）
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = true) {
        if (selectMode) {
            selectMode = false
            selected = emptySet()
            lastBackAt = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt <= 2000L) {
                context.findActivity()?.finish()
            } else {
                lastBackAt = now
                Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 数据就绪后同步一次排程（新增/修改/删除都会经 store，这里兜底）
    LaunchedEffect(state.items, notifyEnabled, notifyTime) {
        if (state.loaded) {
            ReminderService.syncScheduled(context, store, notifyEnabled, notifyTime)
        }
    }

    // 筛选的分类被删掉（或导入的数据里没有）时回落到「全部分类」，避免筛出空列表
    LaunchedEffect(state.categories, state.categoryColors) {
        if (filter.category != HomeFilterState.ALL && store.categoryOf(filter.category) == null) {
            filter.category = HomeFilterState.ALL
        }
    }

    // 先只按搜索词筛一遍：下拉里的分类计数 = 点进去后能看到的条数，与统计卡「全部」同一口径
    val keywordMatched = remember(state, filter.keyword) {
        val kw = filter.keyword.trim().lowercase()
        store.activeItems().filter { item ->
            kw.isEmpty() || item.name.lowercase().contains(kw) || item.note.lowercase().contains(kw)
        }
    }
    val categoryCounts = remember(keywordMatched) {
        keywordMatched.groupingBy { it.category }.eachCount()
    }
    val filtered = remember(keywordMatched, filter.category) {
        if (filter.category == HomeFilterState.ALL) keywordMatched
        else keywordMatched.filter { it.category == filter.category }
    }
    val grouped = remember(filtered) {
        // 按状态固定顺序输出非空分组
        val byStatus = filtered.groupBy { store.statusOf(it) }
        ExpiryStatus.entries.mapNotNull { s -> byStatus[s]?.let { s to it } }
    }
    val categoryLabel = if (filter.category == HomeFilterState.ALL) "全部分类"
    else store.categoryOptions().firstOrNull { it.id == filter.category }?.label ?: "全部分类"

    // 可左右切换的分类环 =「全部分类」+ 至少有一条未归档记录的分类。
    // 空分类不进环，滑动不会切出一片空白；这里刻意不跟搜索词联动，避免搜索时环忽长忽短。
    val switchableIds = remember(state) {
        val counts = store.activeItems().groupingBy { it.category }.eachCount()
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
    // 每换一次分类就新建一个从 ±1 起跳的 Animatable：起始偏移在组合期就位，
    // 不会出现「先按最终位置画一帧、再跳回去滑进来」的闪一下
    val slide = remember(filter.category) {
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

    // 真的换了分类才回顶部；从编辑页返回时保留原来的滚动位置
    val listState = rememberLazyListState()
    var scrolledCategory by remember { mutableStateOf(filter.category) }
    LaunchedEffect(filter.category) {
        if (scrolledCategory != filter.category) {
            scrolledCategory = filter.category
            if (filtered.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!selectMode) {
                FloatingActionButton(
                    onClick = {
                        // 「全部分类」= 无筛选，交给新增页用默认分类；其余带上筛选的分类
                        onAdd(
                            filter.category.takeIf {
                                it != HomeFilterState.ALL && store.categoryOf(it) != null
                            }
                        )
                    },
                    shape = CircleShape,
                    containerColor = Color(0xFFFFC107),
                    contentColor = Color(0xFF303133),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // 整页右滑切上一个分类；左滑不接管，列表行照旧左滑删除
                .swipeRightToSwitchCategory { if (!selectMode) switchCategory(-1) },
        ) {
            // ---------------------------------------------- 头部
            if (selectMode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
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
                        TextButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    store.removeItems(context, selected.toList())
                                    selectMode = false
                                    selected = emptySet()
                                }
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // 与页面底色一致，状态栏、头部、正文连成一片，避免割裂
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "到期提醒",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "食品、药品、证件的有效期管理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                // ------------------------------------------ 统计卡
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(
                        count = filtered.size,
                        label = "全部",
                        accent = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        count = countOf(grouped, ExpiryStatus.soon),
                        label = "7天内",
                        accent = hexColor(ExpiryStatus.soon.color),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        count = countOf(grouped, ExpiryStatus.expired),
                        label = "已过期",
                        accent = hexColor(ExpiryStatus.expired.color),
                        modifier = Modifier.weight(1f),
                    )
                }

                // ------------------------------------------ 搜索框
                OutlinedTextField(
                    value = filter.keyword,
                    onValueChange = { filter.keyword = it },
                    placeholder = {
                        Text("搜索物品名称或备注", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                // ------------------------------------------ 分类切换器 + 分类/多选
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 分类下拉：点开菜单直选。切换分类靠整页右滑，这里不再放左右箭头
                    Box(Modifier.weight(1f)) {
                        Surface(
                            onClick = { categoryMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 容器宽度定死，这里换文字不会引起布局跳动；
                                // 竖向滚动淡入，和列表的横向滑动呼应出「翻页」的方向感
                                AnimatedContent(
                                    targetState = categoryLabel,
                                    transitionSpec = {
                                        val up = slideDir > 0
                                        (slideInVertically(tween(180)) { h -> if (up) h else -h } +
                                            fadeIn(tween(180))) togetherWith
                                            (slideOutVertically(tween(140)) { h -> if (up) -h else h } +
                                                fadeOut(tween(120)))
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = "categoryLabel",
                                ) { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
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
                                text = { Text("全部分类") },
                                // 占位，让文案与下面带色点的分类项左对齐
                                leadingIcon = { Box(Modifier.size(10.dp)) },
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

                    // 分类管理（独立页面）
                    Surface(
                        onClick = onCategories,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF67C23A),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "分类管理",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }

                    // 多选 / 退出多选（同一颗按钮切换）
                    val accent = if (selectMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                    Surface(
                        onClick = {
                            if (selectMode) {
                                selectMode = false
                                selected = emptySet()
                            } else {
                                selectMode = true
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectMode) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selectMode) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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

            // ---------------------------------------------- 列表
            if (filtered.isEmpty()) {
                // 空态与列表共用同一套进场动画，切到没有记录的分类时同样有「翻过去了」的反馈
                Box(
                    Modifier
                        .fillMaxSize()
                        .categorySlide({ slide.value }, slideShiftPx)
                        .padding(bottom = 96.dp),
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
                            if (state.loaded) "暂无物品" else "加载中…",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.loaded) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点右下角 + 添加第一件物品",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .categorySlide({ slide.value }, slideShiftPx),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (status, items) ->
                        item(key = "header_${status.name}") {
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
                            ItemRow(
                                item = item,
                                store = store,
                                selectMode = selectMode,
                                selected = item.id in selected,
                                onClick = {
                                    if (selectMode) {
                                        selected =
                                            if (item.id in selected) selected - item.id
                                            else selected + item.id
                                    } else onEdit(item.id)
                                },
                                onLongClick = {
                                    if (!selectMode) {
                                        selectMode = true
                                        selected = setOf(item.id)
                                    }
                                },
                                onSwipeDelete = { pendingDelete = item },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除物品") },
            text = { Text("确定删除「${item.name}」吗？相关图片也会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingDelete = null
                    scope.launch { store.removeItem(context, target.id) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun countOf(
    grouped: List<Pair<ExpiryStatus, List<ExpiryItem>>>,
    status: ExpiryStatus,
): Int = grouped.firstOrNull { it.first == status }?.second?.size ?: 0

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

/** 参考稿统计卡：白底、左对齐彩色大数字 + 灰色标签。 */
@Composable
private fun StatCard(count: Int, label: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "$count",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: ExpiryItem,
    store: ExpiryStore,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val days = Dates.daysUntil(item.expireAt)
    val status = store.statusOf(item)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !selectMode) {
                onSwipeDelete()
            }
            // 无论确认与否都弹回原位，删除交给对话框
            false
        },
        positionalThreshold = { it * 0.5f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete, contentDescription = "删除",
                    tint = Color.White, modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = if (selected) null else androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline,
            ),
        ) {
            Row(
                Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 分类色条：圆角短条，上下留白、左缘不贴边（与预览稿一致）
                Box(
                    Modifier
                        .fillMaxHeight()
                        .padding(start = 8.dp)
                        .width(4.dp)
                        .padding(vertical = 12.dp)
                        .background(
                            hexColor(store.categoryColorOf(item.category)),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Row(
                    Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
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
                            val meta = buildList {
                                add(store.categoryLabelOf(item.category))
                                if (item.expireAt.isNotEmpty()) add(item.expireAt)
                                if (item.quantity > 1) add("×${item.quantity}")
                                if (item.images.isNotEmpty()) add("${item.images.size} 张图")
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
                    } else if (item.expireAt.isNotEmpty()) {
                        Text(
                            Dates.describeRemaining(item.expireAt),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = hexColor(status.color),
                        )
                    }
                }
            }
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
