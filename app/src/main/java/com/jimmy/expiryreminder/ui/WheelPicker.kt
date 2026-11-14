package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** 单行高度 */
private val WheelItemHeight = 44.dp

/** 可见行数，奇数才能有正中一行 */
private const val WheelVisibleCount = 5

/** 中心行上下各露出几行 */
private const val WheelCenterCount = (WheelVisibleCount - 1) / 2

/** 下方输入框高度 */
private val InputHeight = 44.dp

/**
 * 滚轮 → 输入框的同步延时。
 * 手指滚动时选中值会一路变化，等它稳定这么久再回填文字，
 * 免得滚轮还在滑、输入框里的数字已经跳了好几次。
 */
private const val WHEEL_TO_INPUT_DELAY_MS = 260L

/**
 * 输入框 → 滚轮的同步延时。
 * 等用户停手这么久再让滚轮滚过去，否则每敲一位滚轮就动一次，观感很吵。
 */
private const val INPUT_TO_WHEEL_DELAY_MS = 480L

/** 程序滚动超过这么多项就瞬移（动画滚几十项既慢又会一路重组） */
private const val LONG_SCROLL_THRESHOLD = 6

/**
 * 滚轮选择器：固定行高的 LazyColumn + 停靠吸附，中心那一行即选中值。
 *
 * 选中项用 layoutInfo 里「离视口中心最近」的可见项算出，与 contentPadding 的具体数值无关；
 * 只在整数下标变化时才回调 onSelected，避免滚动过程中反复触发外部重排。
 *
 * [forcedIndex] 供外部驱动（例如输入框输入、日期列被月份夹取）：
 * 只在滚轮没被手指拖动时接管，且滚动期间不回传中间值。
 */
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    forcedIndex: Int? = null,
) {
    if (items.isEmpty()) return
    val start = initialIndex.coerceIn(0, items.lastIndex)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { WheelItemHeight.roundToPx() }
    val currentOnSelected by rememberUpdatedState(onSelected)

    // 外部驱动滚动的过程中，选中的是「过程值」而不是用户意图，不能回传
    var programmatic by remember { mutableStateOf(false) }

    val selectedIndex by remember(items) {
        derivedStateOf {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                start
            } else {
                val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2f
                visible
                    .minByOrNull { abs(it.offset + it.size / 2f - center) }
                    ?.index
                    ?.coerceIn(0, items.lastIndex)
                    ?: start
            }
        }
    }

    LaunchedEffect(state, items.size) {
        snapshotFlow { selectedIndex }.distinctUntilChanged().collect {
            if (!programmatic) currentOnSelected(it)
        }
    }

    // 外部指定目标（输入框输入值、日期列被夹到月末）：只在滚轮没在滚动时接管，
    // 否则会和手指的滚动打架
    LaunchedEffect(forcedIndex, items.size) {
        val target = forcedIndex?.coerceIn(0, items.lastIndex) ?: return@LaunchedEffect
        if (state.isScrollInProgress || target == selectedIndex) return@LaunchedEffect
        programmatic = true
        try {
            if (abs(target - selectedIndex) > LONG_SCROLL_THRESHOLD) {
                state.scrollToItem(target)
            } else {
                state.animateScrollToItem(target)
            }
            // 等吸附完全停稳再放开，避免最后一帧的中间值漏出去
            delay(60)
        } finally {
            programmatic = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WheelItemHeight * WheelVisibleCount),
        contentAlignment = Alignment.Center,
    ) {
        // 中心高亮条：画在数字下面当底衬
        Box(
            Modifier
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = WheelItemHeight * WheelCenterCount),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(items.size) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight)
                        // 圆柱透视：以视口中心为轴，行离中心越远，绕 X 轴转得越多、越淡，
                        // 视觉上就像数字贴在圆柱面上滚动（iPhone 滚轮的感觉）。
                        // 旋转值在 graphicsLayer 的 lambda 里算，滚动时只重绘不重组。
                        .graphicsLayer {
                            val layout = state.layoutInfo
                            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                            val info = layout.visibleItemsInfo.firstOrNull { it.index == index }
                            val distanceRows = if (info == null) {
                                0f
                            } else {
                                (info.offset + info.size / 2f - center) / itemPx
                            }
                            val rows = distanceRows.coerceIn(-2.5f, 2.5f)
                            rotationX = rows * 16f
                            // 镜头拉远一点，避免大角度旋转时数字被拉爆
                            cameraDistance = 12f * density
                            alpha = (1f - abs(rows) * 0.3f).coerceIn(0.2f, 1f)
                            val scale = 1f - abs(rows) * 0.04f
                            scaleX = scale
                            scaleY = scale
                        }
                        // 点任意一行就把它滚到正中
                        .clickable {
                            scope.launch {
                                if (abs(index - selectedIndex) > LONG_SCROLL_THRESHOLD) {
                                    state.scrollToItem(index)
                                } else {
                                    state.animateScrollToItem(index)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = items[index],
                        fontSize = 20.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 滚轮 + 下方独立输入框。
 *
 * [value] 与滚轮项「显示的数字」同一量纲：年就是 2022、分就是 7——
 * 内部负责换算成滚轮下标。绝不能拿输入的数字直接当下标：
 * 年份列 items[86] 是 "2056"，把 2022 当下标夹到 86 就成了 2056。
 *
 * 两套操作彻底分离、不共享焦点，只靠「延时同步」保持数值一致：
 * - 滚轮停稳 [WHEEL_TO_INPUT_DELAY_MS] 后，把选中值回填到输入框；
 * - 输入框停手 [INPUT_TO_WHEEL_DELAY_MS] 后（或失焦 / 按完成），把数字交给滚轮滚过去。
 * 两边的延时都起到「等控件值落实」的作用，避免滚动和输入互相拽。
 */
@Composable
private fun WheelWithInput(
    items: List<String>,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxDigits: Int = 2,
    inputWidth: Dp = 64.dp,
) {
    if (items.isEmpty()) return
    // 全部项都是数字串（时/分/月/日/年），取合法区间用于输入夹取
    val minNum = items.first().toIntOrNull() ?: return
    val maxNum = items.last().toIntOrNull() ?: return

    fun indexOfValue(v: Int): Int {
        for (i in items.indices) if (items[i].toIntOrNull() == v) return i
        // 找不到（值刚被外部夹取、列表还没跟上）就近取端
        return if (v <= minNum) 0 else items.lastIndex
    }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var text by remember { mutableStateOf(TextFieldValue(items[indexOfValue(value)])) }
    // 输入框是否正在被编辑：聚焦期间不拿滚轮的值去覆盖用户正在敲的内容
    var inputFocused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    // 聚焦即全选：聚焦在手指按下时就发生，抬手后输入框还会按落点把选区收成一个光标，
    // 所以不能在 onFocusChanged 里选一次就算完（那一刻会被随后写入的光标覆盖，
    // 表现为「时而有、时而无」）。这里改成状态判定：
    // 只要原文没被改动、选区又退化成单个光标，就重新全选；
    // 一旦用户真的敲进了新内容，立刻把控制权交还。
    var selectAllPending by remember { mutableStateOf(false) }
    var focusedBaseText by remember { mutableStateOf("") }

    LaunchedEffect(text, selectAllPending) {
        if (!selectAllPending) return@LaunchedEffect
        // 用户已开始输入（内容变了），不再干预选区
        if (text.text != focusedBaseText || !text.selection.collapsed) {
            selectAllPending = false
            return@LaunchedEffect
        }
        text = text.copy(selection = TextRange(0, text.text.length))
    }

    fun commit(raw: String) {
        pending?.cancel()
        pending = null
        val parsed = raw.toIntOrNull() ?: return
        // 夹取用「显示数字」的区间，不是下标区间
        val target = parsed.coerceIn(minNum, maxNum)
        if (target != value) onValueChange(target)
    }

    // 滚轮 → 输入框：等选中值稳定一会儿再回填。
    // 值每变一次就重启这个效应，所以滚动途中不会生效，只有停稳后才会落字。
    LaunchedEffect(value, items, inputFocused) {
        if (inputFocused) return@LaunchedEffect
        delay(WHEEL_TO_INPUT_DELAY_MS)
        if (text.text.toIntOrNull() != value) text = TextFieldValue(items[indexOfValue(value)])
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        WheelPicker(
            items = items,
            initialIndex = indexOfValue(value),
            onSelected = { index -> items[index].toIntOrNull()?.let(onValueChange) },
            forcedIndex = indexOfValue(value),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(inputWidth)
                .height(InputHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val digits = new.text.filter { it.isDigit() }.take(maxDigits)
                    // 光标放到末尾：全选后直接敲数字即覆盖，继续敲则追加
                    text = TextFieldValue(digits, selection = TextRange(digits.length))
                    pending?.cancel()
                    // 停手一小会儿再回写滚轮：让输入先落定，别每敲一位就滚一次
                    if (digits.isNotEmpty()) {
                        pending = scope.launch {
                            delay(INPUT_TO_WHEEL_DELAY_MS)
                            commit(digits)
                        }
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    commit(text.text)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .onFocusChanged { state ->
                        val focused = state.isFocused
                        if (focused == inputFocused) return@onFocusChanged
                        inputFocused = focused
                        if (focused) {
                            // 先记下原文，等选区被点击落点收成光标后再全选（见 selectAllPending 的效应）
                            focusedBaseText = text.text
                            selectAllPending = true
                        } else {
                            selectAllPending = false
                            // 失焦即落定：空串恢复原值，否则立即提交（点「确定」时靠这一步拿到最新值）
                            if (text.text.isBlank()) {
                                text = TextFieldValue(items[indexOfValue(value)])
                            } else {
                                commit(text.text)
                            }
                        }
                    },
            )
        }
    }
}

/**
 * 「时 : 分」双列滚轮 + 下方两个输入框。
 * 滚轮滑动选择，或在下方输入框直接敲数字，两边延时同步。
 */
@Composable
fun TimeWheelPicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    val unitStyle = MaterialTheme.typography.titleMedium
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        WheelWithInput(
            items = hours,
            value = hour,
            onValueChange = onHourChange,
            modifier = Modifier.weight(1f),
            inputWidth = 64.dp,
        )
        // 时/分列 items[i] 恰好等于 i，value 传原值即可
        // 单位字跟滚轮中心对齐：下方的输入框占掉 8 + 44，用等量下内边距把它抬回去
        Text(
            "时",
            style = unitStyle,
            color = unitColor,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = InputHeight + 8.dp),
        )
        WheelWithInput(
            items = minutes,
            value = minute,
            onValueChange = onMinuteChange,
            modifier = Modifier.weight(1f),
            inputWidth = 64.dp,
        )
        Text(
            "分",
            style = unitStyle,
            color = unitColor,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = InputHeight + 8.dp),
        )
    }
}

/** 日期滚轮默认年份下限：覆盖 70 年代的老物件 */
private const val DEFAULT_MIN_YEAR = 1970

/** 日期滚轮默认年份上限：当前年份往后多少年，够放长保质期的东西 */
private const val DEFAULT_YEARS_AHEAD = 30

/**
 * 「年 / 月 / 日」三列滚轮 + 下方三个输入框。
 *
 * 日的项数按年月动态生成（28~31，含闰年）；年月一变就把当前日夹到月末，
 * 再用 forcedIndex 把那列滚过去，不会出现「2 月 31 日」这种非法组合。
 */
@Composable
fun DateWheelPicker(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    minYear: Int = DEFAULT_MIN_YEAR,
    maxYear: Int = LocalDate.now().year + DEFAULT_YEARS_AHEAD,
) {
    val years = remember(minYear, maxYear) { (minYear..maxYear).map { it.toString() } }
    val months = remember { (1..12).map { "%02d".format(it) } }

    var year by remember(date) { mutableIntStateOf(date.year.coerceIn(minYear, maxYear)) }
    var month by remember(date) { mutableIntStateOf(date.monthValue) }
    var day by remember(date) { mutableIntStateOf(date.dayOfMonth) }

    val dayCount = remember(year, month) { YearMonth.of(year, month).lengthOfMonth() }
    val days = remember(dayCount) { (1..dayCount).map { "%02d".format(it) } }

    fun emit(y: Int, m: Int, d: Int) {
        val safeYear = y.coerceIn(minYear, maxYear)
        val safeMonth = m.coerceIn(1, 12)
        val lastDay = YearMonth.of(safeYear, safeMonth).lengthOfMonth()
        val safeDay = d.coerceIn(1, lastDay)
        year = safeYear
        month = safeMonth
        day = safeDay
        onDateChange(LocalDate.of(safeYear, safeMonth, safeDay))
    }

    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1.3f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("年", style = labelStyle, color = labelColor)
            WheelWithInput(
                items = years,
                value = year,
                onValueChange = { emit(it, month, day) },
                maxDigits = 4,
                inputWidth = 84.dp,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("月", style = labelStyle, color = labelColor)
            WheelWithInput(
                items = months,
                value = month,
                onValueChange = { emit(year, it, day) },
                inputWidth = 56.dp,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("日", style = labelStyle, color = labelColor)
            WheelWithInput(
                items = days,
                value = day,
                onValueChange = { emit(year, month, it) },
                inputWidth = 56.dp,
            )
        }
    }
}
