package com.jimmy.expiryreminder.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.CategoryDef
import com.jimmy.expiryreminder.core.CategoryNameError
import com.jimmy.expiryreminder.core.ExpiryStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 每行固定高度（拖动位移换算靠它，各行等高才能按跨行判断）。 */
private val ROW_HEIGHT = 56.dp

/** 行间距也算进一格的步长，否则拖动时上下让位会对不齐。 */
private val ROW_GAP = 8.dp

/** 行圆角。 */
private val ROW_SHAPE = RoundedCornerShape(14.dp)

/**
 * 分类管理页：内置 + 自定义分类。
 * 支持长按拖动排序、点色点换色、删除自定义分类、底部新增。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // store.categoryOptions() 读的是 StateFlow.value，Compose 观察不到这次读取，
    // 所以必须把派生结果挂在「收集到的 state」上重算：新增/改色/删除分类后列表才会立刻刷新
    val options = remember(state) { store.categoryOptions() }
    val listState = rememberLazyListState()

    var newLabel by remember { mutableStateOf("") }
    var newError by remember { mutableStateOf<String?>(null) }
    var colorFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CategoryDef?>(null) }
    var renameFor by remember { mutableStateOf<CategoryDef?>(null) }
    // 新增成功后滚到新行，确认「加进去了」
    var scrollToNew by remember { mutableStateOf(false) }

    LaunchedEffect(options.size) {
        if (scrollToNew && options.isNotEmpty()) {
            scrollToNew = false
            listState.animateScrollToItem(options.lastIndex)
        }
    }

    fun submitNew() {
        if (newLabel.isBlank()) {
            newError = "名称不能为空"
            return
        }
        scope.launch {
            val created = store.addCategory(context, newLabel)
            if (created == null) {
                newError = "分类已存在"
            } else {
                newLabel = ""
                scrollToNew = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Text(
                "长按分类可拖动排序；点整行改名，点左侧圆点换颜色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
            )

            CategoryList(
                items = options,
                listState = listState,
                // 分类下挂着的物品数（含已归档）；>0 就不允许删除
                usageOf = { id -> state.items.count { it.category == id } },
                isBuiltin = { store.isBuiltinCategory(it) },
                defaultLabelOf = { store.defaultLabelOf(it) },
                onPickColor = { colorFor = it },
                onRename = { renameFor = it },
                onDelete = { cat, usage ->
                    if (usage > 0) {
                        Toast.makeText(
                            context,
                            "「${cat.label}」下还有 $usage 件物品，先移走再删除",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        pendingDelete = cat
                    }
                },
                onReorder = { ids -> scope.launch { store.reorderCategories(context, ids) } },
                modifier = Modifier.weight(1f),
            )

            // 新增区钉在底部，键盘弹出时跟着抬起
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it; newError = null },
                        label = { Text("新增分类") },
                        singleLine = true,
                        isError = newError != null,
                        supportingText = newError?.let { msg ->
                            { Text(msg, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = ::submitNew, enabled = newLabel.isNotBlank()) {
                        Text("添加")
                    }
                }
            }
        }
    }

    pendingDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除分类") },
            text = { Text("确定删除「${cat.label}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // 数据层再兜一次：分类下若有物品会返回 false
                        val ok = store.removeCategory(context, cat.id)
                        if (!ok) {
                            Toast.makeText(
                                context,
                                "「${cat.label}」下还有物品，无法删除",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    colorFor?.let { catId ->
        ColorPickerDialog(
            current = store.categoryColorOf(catId),
            onPick = { color ->
                scope.launch { store.setCategoryColor(context, catId, color) }
                colorFor = null
            },
            onDismiss = { colorFor = null },
        )
    }

    renameFor?.let { cat ->
        // 预填原名并全选，直接输入就是新名字
        var field by remember(cat.id) {
            mutableStateOf(TextFieldValue(cat.label, TextRange(0, cat.label.length)))
        }
        var nameError by remember(cat.id) { mutableStateOf<String?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(cat.id) { focus.requestFocus() }
        // 内置分类才有默认名称；改过名才给「恢复默认」入口
        val defaultLabel = store.defaultLabelOf(cat.id)
        val renamed = defaultLabel != null && defaultLabel != cat.label

        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("分类改名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it; nameError = null },
                        label = { Text("分类名称") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { msg ->
                            { Text(msg, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    if (defaultLabel != null && renamed) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    when (store.resetCategoryLabel(context, cat.id)) {
                                        null -> {
                                            renameFor = null
                                            Toast.makeText(
                                                context,
                                                "已恢复为「$defaultLabel」",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        // 默认名被别的分类占用了，只能提示，不能强行改回去
                                        CategoryNameError.duplicate ->
                                            nameError = "「$defaultLabel」已被其它分类占用"
                                        CategoryNameError.empty -> Unit
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("重置默认名称「$defaultLabel」")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (store.renameCategory(context, cat.id, field.text)) {
                            null -> renameFor = null
                            CategoryNameError.empty -> nameError = "名称不能为空"
                            CategoryNameError.duplicate -> nameError = "已有同名分类"
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 可拖动排序的分类列表。
 *
 * 关键点：**拖动过程中不改真实顺序**，只把被拖的行跟着手指平移、把其它行上下让开一格；
 * 抬手时才把新顺序一次性提交。
 * 这样列表在拖动中不会重新排布，也就不会出现「刚拖过去又弹回原位」的抖动
 * （之前每跨一行就改一次顺序 + animateItem 让位，被拖行和让位行动画不同步，看起来像第一个位置被复位）。
 */
@Composable
private fun CategoryList(
    items: List<CategoryDef>,
    listState: LazyListState,
    usageOf: (String) -> Int,
    isBuiltin: (String) -> Boolean,
    defaultLabelOf: (String) -> String?,
    onPickColor: (String) -> Unit,
    onRename: (CategoryDef) -> Unit,
    onDelete: (CategoryDef, Int) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 一格 = 行高 + 行间距：跨过一格就换位，被拖行与让位行的位移都用这个值
    val stepPx = with(density) { (ROW_HEIGHT + ROW_GAP).toPx() }
    // 贴边多少距离开始自动滚、每帧最多滚多远（约 8dp/帧 ≈ 480dp/s）
    val autoScrollEdgePx = with(density) { 64.dp.toPx() }
    val autoScrollStepPx = with(density) { 8.dp.toPx() }
    val movedThresholdPx = with(density) { 8.dp.toPx() }

    // 落盘是异步的：先按新顺序渲染，等 store 的列表追上来再把控制权交还，
    // 否则松手的瞬间会闪一下旧顺序
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableStateOf(-1) }
    // 用 FloatState：拖动中每帧只读它做位移，不必重组整张列表
    val dragDy = remember { mutableFloatStateOf(0f) }
    // 手指确实拖动过才开自动滚动：否则长按一行（尤其靠边的行）列表会莫名自己滚
    val dragMoved = remember { mutableStateOf(false) }
    val moveAccum = remember { mutableFloatStateOf(0f) }

    val rows = pendingOrder
        ?.let { order -> order.mapNotNull { id -> items.firstOrNull { it.id == id } } }
        ?.takeIf { it.size == items.size }
        ?: items
    val lastIndex = rows.lastIndex
    // 手势协程只创建一次，闭包里要读「当前」的列表，避免拿到拖动开始前的旧顺序
    val latestRows by rememberUpdatedState(rows)

    LaunchedEffect(items) {
        if (pendingOrder != null && items.map { it.id } == pendingOrder) pendingOrder = null
    }

    // 拖到列表上下边缘时自动滚动：被拖行贴边就每帧滚一点，滚多少就把拖拽位移补回来，
    // 这样手指不动、行也一直停在边缘，同时它相对内容的位置在变——目标索引自然继续往前走。
    LaunchedEffect(dragId) {
        val id = dragId ?: return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (dragId != id) break
            if (!dragMoved.value) continue
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            // 行的实际位置 = 布局槽位 + 拖拽位移（行在拖动中不重排，槽位是固定的）
            val top = item.offset + dragDy.floatValue
            val bottom = top + item.size
            val topEdge = info.viewportStartOffset + autoScrollEdgePx
            val bottomEdge = info.viewportEndOffset - autoScrollEdgePx
            val delta = when {
                top < topEdge ->
                    -((topEdge - top) / autoScrollEdgePx).coerceIn(0f, 1f) * autoScrollStepPx
                bottom > bottomEdge ->
                    ((bottom - bottomEdge) / autoScrollEdgePx).coerceIn(0f, 1f) * autoScrollStepPx
                else -> 0f
            }
            if (delta == 0f) continue
            // 滚不动（到头了）时 consumed 为 0，位移不用补
            val consumed = listState.scrollBy(delta)
            if (consumed != 0f) dragDy.floatValue += consumed
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        itemsIndexed(rows, key = { _, cat -> cat.id }) { index, cat ->
            val dragging = cat.id == dragId
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .zIndex(if (dragging) 1f else 0f)
                    // graphicsLayer 放在背景前面：位移要连背景一起带走
                    .graphicsLayer {
                        val dy = dragDy.floatValue
                        val from = dragFrom
                        // 目标索引：跨过半格就算换到那一行
                        val to = if (from < 0) from else
                            (from + (dy / stepPx).roundToInt()).coerceIn(0, lastIndex)
                        translationY = when {
                            dragging -> dy
                            from >= 0 && to != from && index in minOf(from, to)..maxOf(from, to) ->
                                if (to > from) -stepPx else stepPx
                            else -> 0f
                        }
                        shape = ROW_SHAPE
                        clip = true
                        if (dragging) {
                            scaleX = 1.02f
                            scaleY = 1.02f
                            shadowElevation = 12.dp.toPx()
                        }
                    }
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        ROW_SHAPE,
                    )
                    .border(
                        width = 1.dp,
                        color = if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        else Color.Transparent,
                        shape = ROW_SHAPE,
                    )
                    // 整行点一下就是改名（长按仍然拖动；拖动中的 consume 会让这次点击失效）
                    .clickable { onRename(cat) }
                    .pointerInput(cat.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val from = latestRows.indexOfFirst { it.id == cat.id }
                                if (from >= 0) {
                                    dragFrom = from
                                    dragId = cat.id
                                    dragDy.floatValue = 0f
                                    dragMoved.value = false
                                    moveAccum.floatValue = 0f
                                }
                            },
                            onDragCancel = {
                                dragId = null
                                dragFrom = -1
                                dragDy.floatValue = 0f
                            },
                            onDragEnd = {
                                val from = dragFrom
                                val to = if (from < 0) from else
                                    (from + (dragDy.floatValue / stepPx).roundToInt())
                                        .coerceIn(0, latestRows.lastIndex)
                                dragId = null
                                dragFrom = -1
                                dragDy.floatValue = 0f
                                if (from >= 0 && to != from) {
                                    val ids = latestRows.map { it.id }.toMutableList()
                                    ids.add(to, ids.removeAt(from))
                                    pendingOrder = ids
                                    onReorder(ids)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDy.floatValue += amount.y
                                // 累计手指位移闯过 8dp 才算「真的在拖」，之后一直保持
                                if (!dragMoved.value) {
                                    moveAccum.floatValue += abs(amount.y)
                                    if (moveAccum.floatValue > movedThresholdPx) dragMoved.value = true
                                }
                            },
                        )
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(hexColor(cat.color))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .clickable { onPickColor(cat.id) },
                )
                Spacer(Modifier.width(12.dp))
                val defaultLabel = defaultLabelOf(cat.id)
                Column(Modifier.weight(1f)) {
                    Text(
                        cat.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 内置分类改过名时标出默认名称，点进去就能一键恢复
                    if (defaultLabel != null && defaultLabel != cat.label) {
                        Text(
                            "默认：$defaultLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val usage = usageOf(cat.id)
                if (usage > 0) {
                    Text(
                        "$usage 件",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (isBuiltin(cat.id)) {
                    // 内置分类不能删：标出来，省得用户找删除按钮
                    Text(
                        "内置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                } else {
                    IconButton(onClick = { onDelete(cat, usage) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription =
                                if (usage > 0) "分类下有物品，不能删除" else "删除分类",
                            // 有物品时置灰表示不可删，点击给原因
                            tint = if (usage > 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // 纯视觉提示：实际长按整行任意位置都能拖
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 12 色取色板。 */
@Composable
fun ColorPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(Categories.PALETTE) { color ->
                    val selected = color.equals(current, ignoreCase = true)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(hexColor(color))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { onPick(color) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
