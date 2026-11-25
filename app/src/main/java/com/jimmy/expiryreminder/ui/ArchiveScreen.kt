@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.Spaces
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.launch

/**
 * 归档页：独立的二级页面（不再借用 HomeScreen 的模式开关）。
 *
 * 与主页共享的东西只有列表行 [ItemRow] 与下拉里的 [CountBadge] 这两个视觉单元；
 * 页面结构自己持有：
 * - 头部：返回键 + 标题 + 空间筛选（只筛不分享、不含空间管理入口）
 * - 筛选：分类下拉一颗（归档物品不参与搜索、多选、批量分享）
 * - 列表：不按到期状态分组——物品已不再提醒，再套「已过期 / 7 天内到期」会让人误以为还在催
 * - 行：左滑只有「删除」一段，右侧「调回」按钮，点击进编辑页
 *
 * [filter] 由导航层持有（与主页那份独立），进出编辑页回来筛选与滚动位置都还在。
 */
@Composable
fun ArchiveScreen(
    filter: HomeFilterState,
    onBack: () -> Unit,
    /** 点行进编辑页（归档物品仍可改内容，保存后留在归档状态）。 */
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var spaceMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    // 多选：长按进入，批量「调回 / 删除」
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var batchDeleteDialog by remember { mutableStateOf(false) }
    // 全屏图片查看器：与主页同一套（缩略图位置作为展开/缩回起点）
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    // 左滑确认（调回 / 删除）：行只上报停在哪个确认位，框在页面层弹，行被回收也不会丢
    var swipeAsk by remember { mutableStateOf<SwipeAsk?>(null) }
    var swipeResetToken by remember { mutableIntStateOf(0) }
    val closeSwipeAsk = {
        swipeAsk = null
        swipeResetToken += 1
    }

    // 多选态拦返回：先退出多选，不退页面（与主页同口径）
    BackHandler(enabled = selectMode) {
        selectMode = false
        selected = emptySet()
    }

    // 当前空间/分类被删掉时回落，避免筛出一片空白（与主页同一口径）
    LaunchedEffect(state.spaces) {
        if (filter.space != Spaces.ALL &&
            filter.space.isNotEmpty() &&
            store.spaceOf(filter.space) == null
        ) {
            filter.space = Spaces.ALL
            filter.category = HomeFilterState.ALL
        }
    }
    LaunchedEffect(state.categories, state.categoryColors) {
        if (filter.category != HomeFilterState.ALL && store.categoryOf(filter.category) == null) {
            filter.category = HomeFilterState.ALL
        }
    }

    // 只统计归档物品：下拉里的条数必须与点进去能看到的条数一致
    val spaceItems = remember(state, filter.space) { store.archivedItemsInSpace(filter.space) }
    val spaceCount: (String) -> Int = store::archivedCountInSpace
    val categoryCounts = remember(spaceItems) { spaceItems.groupingBy { it.category }.eachCount() }
    val filtered = remember(spaceItems, filter.category) {
        if (filter.category == HomeFilterState.ALL) spaceItems
        else spaceItems.filter { it.category == filter.category }
    }

    val selectedCategory = if (filter.category == HomeFilterState.ALL) null
    else store.categoryOptions().firstOrNull { it.id == filter.category }
    val categoryLabel = selectedCategory?.label ?: Categories.ALL_LABEL
    val categoryDotColor = store.categoryColorOf(filter.category)

    // ------------------------------- 顶部固定块实测高度 / 遮罩
    val listState = rememberLazyListState()
    // 换分类才回顶部；从编辑页返回保留原滚动位置
    var scrolledCategory by remember { mutableStateOf(filter.category) }
    LaunchedEffect(filter.category) {
        if (scrolledCategory != filter.category) {
            scrolledCategory = filter.category
            listState.scrollToItem(0)
        }
    }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    // 分类行顶边：遮罩实心部分只铺到这里，分类行整条保持全透
    var catRowTopPx by remember { mutableIntStateOf(0) }
    val frostSpanPx = with(density) { 28.dp.toPx() }
    val frostAlpha: () -> Float = {
        if (listState.firstVisibleItemIndex > 0) 1f
        else (listState.firstVisibleItemScrollOffset / frostSpanPx).coerceIn(0f, 1f)
    }
    val frostColor = MaterialTheme.colorScheme.background
    val maskTail = 16.dp
    val topContentPadding = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() } + maskTail
    } else {
        // 首帧实测值还没回来时用这个兼底：头部（返回键行 48 + 副标题 + 内边距）
        // 加分类行（14 + 44），与实际高度差不多，避免首帧卡片位置跳一下
        statusTop + 148.dp + maskTail
    }
    // 归档页没有底部浮动条：只留导航栏 + 少量余量；多选态底部换批量操作条，不叠浮动条
    val bottomContentPadding = if (selectMode) 16.dp else navBottom + 24.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 顶部遮罩要压在内容上，内容能滚到状态栏/导航栏底下，系统栏内边距自己接管
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 多选态：批量「调回 / 删除」。调回是恢复性操作，直接执行给 Toast；
            // 删除不可撤销，先确认
            if (selectMode) {
                SelectionBar {
                    SelectionAction("调回", selected.isNotEmpty(), onClick = {
                        val ids = selected.toList()
                        val count = ids.size
                        scope.launch {
                            store.setItemsArchived(context, ids, false)
                            selectMode = false
                            selected = emptySet()
                            Toast.makeText(
                                context, "已调回 $count 件", Toast.LENGTH_SHORT,
                            ).show()
                        }
                    })
                    SelectionAction("删除", selected.isNotEmpty(), onClick = {
                        batchDeleteDialog = true
                    }, danger = true)
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 换分类时整块列表重建：否则旧行会在 animateItem 的消失动画里继续被绘制，
            // 在卡片缝隙里露出上一屏的行（与主页同一条经验，勿简化）
            key(filter.category) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topContentPadding,
                        bottom = bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (filtered.isEmpty()) {
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
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                CircleShape,
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
                                        if (!state.loaded) "加载中…" else "暂无归档物品",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (state.loaded) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "在首页左滑列表项到「归档」即可归档",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(filtered.size, key = { filtered[it].id }) { index ->
                            val item = filtered[index]
                            Box(Modifier.animateItem(fadeOutSpec = null)) {
                                ItemRow(
                                    item = item,
                                    store = store,
                                    archivedMode = true,
                                    // 左滑两段与主页同一套节奏：第一段 = 调回（落在主页「归档」的
                                    // 同一个锚点上，用「正常」状态的绿，避开暗色主题下
                                    // primary 偏亮 + 白字看不清的问题），滑满 = 删除（红底）
                                    firstSwipeLabel = "调回",
                                    firstSwipeColor = hexColor(ExpiryStatus.normal.color),
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
                                        if (!selectMode) {
                                            selectMode = true
                                            selected = setOf(item.id)
                                        }
                                    },
                                    onAskSwipe = { delete -> swipeAsk = SwipeAsk(item, delete) },
                                    swipeResetToken = swipeResetToken,
                                    onViewImages = { origin ->
                                        viewer = ViewerState(item.images.map { it.path }, 0, origin)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------- 顶部固定遮罩（渐变淡出）
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { topBarHeightPx = it.size.height }
                    .drawBehind {
                        val a = frostAlpha()
                        if (a <= 0f) return@drawBehind
                        val body = if (catRowTopPx > 0) catRowTopPx.toFloat() else size.height
                        val total = size.height.coerceAtLeast(1f)
                        val solidEnd = (body / total).coerceIn(0.02f, 1f)
                        val tailFrac = 1f - solidEnd
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to frostColor.copy(alpha = a),
                                solidEnd to frostColor.copy(alpha = a),
                                (solidEnd + tailFrac * 0.5f) to frostColor.copy(alpha = a * 0.30f),
                                1f to frostColor.copy(alpha = 0f),
                            ),
                            size = Size(size.width, total),
                        )
                    },
            ) {
                // ---------------------------------- 头部：返回 + 标题 + 空间筛选
                // 返回键与「设置 / 分类管理 / 空间管理 / 跨设备传输」的 TopAppBar
                // navigationIcon 同一造型：裸 IconButton + 24dp ArrowBack。
                // start=4dp 让图标的视觉左边缘落在 16dp，与其它二级页对位。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                ) {
                    if (selectMode) {
                        // 多选头部：与主页同一套（退出 + 已选数 + 全选/取消全选）
                        Row(
                            Modifier.fillMaxWidth(),
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
                            TextButton(onClick = {
                                val all = filtered.map { it.id }.toSet()
                                selected = if (selected.containsAll(all) && all.isNotEmpty()) {
                                    emptySet()
                                } else all
                            }) {
                                val all = filtered.map { it.id }.toSet()
                                Text(
                                    if (selected.containsAll(all) && all.isNotEmpty()) "取消全选"
                                    else "全选"
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                            Text(
                                "归档",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            // 空间筛选：只看某个空间下的归档物品。归档页不提供分享与空间管理入口。
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
                                                .size(10.dp)
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
                                    HorizontalDivider()
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
                                            trailingIcon = { CountBadge(spaceCount(space.id)) },
                                            onClick = {
                                                if (filter.space != space.id) {
                                                    filter.space = space.id
                                                    // 分类计数跟着空间变，切空间后回到「全部分类」
                                                    filter.category = HomeFilterState.ALL
                                                }
                                                spaceMenuOpen = false
                                            },
                                        )
                                    }
                                    HorizontalDivider()
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
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // 头部 Column 整体 start=4dp（返回图标对位用），副标题补回 12dp
                        // 让文字左边缘落在 16dp，与分类行、列表卡对齐
                        Text(
                            "已归档的物品不再提醒，可调回或删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                // ---------------------------------- 分类筛选 + 多选（与主页同一套：分类下拉 + 多选钮）
                // 容器整体透明：遮罩淡出带只允许出现在它上方，行内零底色
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { catRowTopPx = it.positionInParent().y.toInt() }
                        .padding(start = 16.dp, end = 16.dp, top = ArchiveCategoryRowTopPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                AnimatedContent(
                                    targetState = categoryLabel to categoryDotColor,
                                    transitionSpec = {
                                        (slideInVertically(tween(180)) { h -> h } +
                                            fadeIn(tween(180))) togetherWith
                                            (slideOutVertically(tween(140)) { h -> -h } +
                                                fadeOut(tween(120)))
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = "archiveCategoryLabel",
                                ) { (label, dotColor) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            DropdownMenuItem(
                                text = { Text(Categories.ALL_LABEL) },
                                leadingIcon = {
                                    Box(
                                        Modifier.size(10.dp).background(
                                            hexColor(store.categoryColorOf(HomeFilterState.ALL)),
                                            RoundedCornerShape(5.dp),
                                        )
                                    )
                                },
                                trailingIcon = { CountBadge(spaceItems.size) },
                                onClick = {
                                    filter.category = HomeFilterState.ALL
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
                                        filter.category = cat.id
                                        categoryMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    // 多选 / 退出多选：与主页分类行右侧同款磨砂钮（同一颗按钮切换）。
                    // 长按行也能进多选，但这颗钮是显式入口——不然只能靠发现手势
                    val accent = if (selectMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                    Spacer(Modifier.width(8.dp))
                    Box(
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
        }
    }

    // 左滑确认：第一段（调回）与删除段各弹各的框，确认前不落数据
    swipeAsk?.let { ask ->
        val target = ask.item
        AlertDialog(
            onDismissRequest = { closeSwipeAsk() },
            title = { Text(if (ask.delete) "删除物品" else "调回物品") },
            text = {
                Text(
                    if (ask.delete) {
                        "确定删除「${target.name}」吗？相关图片也会一并删除。"
                    } else {
                        "确定将「${target.name}」调回吗？调回后会重新出现在列表与提醒中。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    swipeAsk = null
                    scope.launch {
                        if (ask.delete) {
                            store.removeItem(context, target.id)
                            // 归档物品本来不再提醒，但历史通知可能还挂在通知栏里
                            ReminderService.cancelNotificationsFor(context, listOf(target.id))
                        } else {
                            store.setItemsArchived(context, listOf(target.id), false)
                            Toast.makeText(
                                context, "已调回「${target.name}」", Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) {
                    Text(
                        if (ask.delete) "删除" else "调回",
                        color = if (ask.delete) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { closeSwipeAsk() }) { Text("取消") }
            },
        )
    }

    // 多选批量删除：不可撤销，先确认一次
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

    viewer?.let { v ->
        ImageViewerOverlay(
            paths = v.paths,
            initialIndex = v.index,
            origin = v.origin,
            onDismiss = { viewer = null },
        )
    }
}

/**
 * 归档页分类行顶边距。
 *
 * 与主页的 `CategoryRowTopPadding` 取同一个值（14dp）：归档页头部没有统计卡与列表模式切换，
 * 两者的分类行都紧贴副标题下方，遮罩淡出带高度因此一致。
 */
private val ArchiveCategoryRowTopPadding = 14.dp
