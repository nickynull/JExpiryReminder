package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

/** 每行固定高度（拖动位移换算靠它，各行等高才能按跨行判断）。 */
internal val SortRowHeight = 56.dp

/** 行间距也算进一格的步长，否则拖动时上下让位会对不齐。 */
internal val SortRowGap = 8.dp

/** 行圆角。 */
internal val SortRowShape = RoundedCornerShape(14.dp)

/**
 * 可拖动排序的行列表，分类管理与空间管理共用同一套外观与手势。
 *
 * 关键点：**拖动过程中不改真实顺序**，只把被拖的行跟着手指平移、把其它行上下让开一格；
 * 抬手时才把新顺序一次性提交。
 * 这样列表在拖动中不会重新排布，也就不会出现「刚拖过去又弹回原位」的抖动
 * （之前每跨一行就改一次顺序 + animateItem 让位，被拖行和让位行动画不同步，看起来像第一个位置被复位）。
 *
 * @param headerContent 追加在可拖动行之前的固定行（如分类页顶部的「全部分类」），不参与排序。
 * @param extraContent 追加在可拖动行之后的固定行（如空间页的「全部空间」「未分配」），不参与排序。
 */
@Composable
internal fun <T> SortableRowList(
    items: List<T>,
    idOf: (T) -> String,
    listState: LazyListState,
    onReorder: (List<String>) -> Unit,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp,
    ),
    headerContent: (LazyListScope.() -> Unit)? = null,
    extraContent: (LazyListScope.() -> Unit)? = null,
    row: @Composable RowScope.(T) -> Unit,
) {
    val density = LocalDensity.current
    // 一格 = 行高 + 行间距：跨过一格就换位，被拖行与让位行的位移都用这个值
    val stepPx = with(density) { (SortRowHeight + SortRowGap).toPx() }
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
        ?.let { order -> order.mapNotNull { id -> items.firstOrNull { idOf(it) == id } } }
        ?.takeIf { it.size == items.size }
        ?: items
    val lastIndex = rows.lastIndex
    // 手势协程只创建一次，闭包里要读「当前」的列表，避免拿到拖动开始前的旧顺序
    val latestRows by rememberUpdatedState(rows)

    LaunchedEffect(items) {
        if (pendingOrder != null && items.map(idOf) == pendingOrder) pendingOrder = null
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
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(SortRowGap),
    ) {
        headerContent?.invoke(this)
        itemsIndexed(rows, key = { _, item -> idOf(item) }) { index, item ->
            val id = idOf(item)
            val dragging = id == dragId
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SortRowHeight)
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
                        shape = SortRowShape
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
                        SortRowShape,
                    )
                    .border(
                        width = 1.dp,
                        color = if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        else Color.Transparent,
                        shape = SortRowShape,
                    )
                    // 整行点一下就是改名（长按仍然拖动；拖动中的 consume 会让这次点击失效）
                    .clickable { onClick(item) }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val from = latestRows.indexOfFirst { idOf(it) == id }
                                if (from >= 0) {
                                    dragFrom = from
                                    dragId = id
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
                                    val ids = latestRows.map(idOf).toMutableList()
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
                row(item)
            }
        }
        extraContent?.invoke(this)
    }
}
