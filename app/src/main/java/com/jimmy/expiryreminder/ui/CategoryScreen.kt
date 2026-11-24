package com.jimmy.expiryreminder.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.CategoryDef
import com.jimmy.expiryreminder.core.CategoryNameError
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.remindDaysSummary
import kotlinx.coroutines.launch

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
    // 分类默认提前提醒：先弹分类清单，选中某一行再进档位选择弹窗
    var remindListOpen by remember { mutableStateOf(false) }
    var remindFor by remember { mutableStateOf<CategoryDef?>(null) }
    // 新增成功后滚到新行，确认「加进去了」
    var scrollToNew by remember { mutableStateOf(false) }

    LaunchedEffect(options.size) {
        if (scrollToNew && options.isNotEmpty()) {
            scrollToNew = false
            // 列表最前面固定有一行「全部分类」，所以真分类行整体后移一位
            listState.animateScrollToItem(options.size)
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
                newError = "已有同名分类"
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
                actions = {
                    IconButton(onClick = { remindListOpen = true }) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = "分类默认提醒",
                        )
                    }
                },
            )
        },
    ) { padding ->
    Box(
        Modifier
            .padding(padding)
            .fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
        Text(
                "长按分类可拖动排序；点整行改名，点左侧圆点换颜色；右上角设新增时的默认提醒",
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
                bottomPad = 92.dp,
                // 「全部分类」不是真分类，但颜色同样可改（只影响界面显示）；
                // 它是首页默认停留的那一页，所以固定在最顶部、不参与拖动
                header = {
                    item {
                        PseudoCategoryRow(
                            label = Categories.ALL_LABEL,
                            hint = "首页默认，包含全部物品",
                            color = store.categoryColorOf(Categories.ALL),
                            count = state.items.size,
                            tag = "汇总",
                            onPickColor = { colorFor = Categories.ALL },
                        )
                    }
                },
            )
        }

        // 新增区悬浮胶囊：与首页/编辑页同一套磨砂浮起，列表从它下面穿过；
        // navigationBarsPadding 抬到手势横条之上，imePadding 让键盘弹起时跟着上移
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .frostedPanel(RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 与首页搜索、编辑页字段同款：48dp 高、10dp 圆角，带一键清空
                InputBox(
                    value = newLabel,
                    onValueChange = { newLabel = it; newError = null },
                    placeholder = "新增分类名称",
                    fixedHeight = 48.dp,
                    showClear = true,
                    isError = newError != null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = ::submitNew,
                    enabled = newLabel.isNotBlank(),
                    // 与空间管理、传输页、录入页统一：10dp 圆角，不用默认胶囊形
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("添加")
                }
            }
            // 错误文案放到框外，输入框本身不被撑高、两行内容也不会一起跳
            newError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
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
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
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

    // 分类清单：一行一个分类，右侧显示当前的默认提前提醒摘要，点进去改档位
    if (remindListOpen) {
        val listScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { remindListOpen = false },
            title = { Text("分类默认提醒") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(listScroll),
                ) {
                    Text(
                        "新增物品时会按所选分类预填这些提前天数，保存后仍可在物品里单独改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (cat in options) {
                        val days = store.defaultRemindDaysOf(cat.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    remindListOpen = false
                                    remindFor = cat
                                }
                                .padding(horizontal = 4.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(hexColor(cat.color)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                cat.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                remindDaysSummary(days) ?: "无默认",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (days.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { remindListOpen = false }) { Text("完成") }
            },
        )
    }

    remindFor?.let { cat ->
        RemindDayPickerDialog(
            title = "「${cat.label}」默认提醒",
            subtitle = "新增该分类的物品时自动勾选这些天数；全不选表示不预选档位。",
            initial = store.defaultRemindDaysOf(cat.id),
            onConfirm = { days ->
                scope.launch { store.setCategoryRemindDefaults(context, cat.id, days) }
                remindFor = null
            },
            onDismiss = { remindFor = null },
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
            title = { Text("重命名分类") },
            text = {
                Column {
                    // 用 TextFieldValue 版：进来就全选原名，直接输入即替换；右侧也能一键清空
                    InputBox(
                        value = field,
                        onValueChange = { field = it; nameError = null },
                        placeholder = "分类名称",
                        fixedHeight = 48.dp,
                        showClear = true,
                        isError = nameError != null,
                        focusRequester = focus,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    nameError?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        )
                    }
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
                TextButton(
                    // 与空间管理一致：名为空时直接禁用，不靠点击后报错
                    onClick = {
                        scope.launch {
                            when (store.renameCategory(context, cat.id, field.text)) {
                                null -> renameFor = null
                                CategoryNameError.empty -> nameError = "名称不能为空"
                                CategoryNameError.duplicate -> nameError = "已有同名分类"
                            }
                        }
                    },
                    enabled = field.text.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 分类列表：外观与拖动排序统一走 [SortableRowList]，本页只负责每行的内容。
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
    bottomPad: Dp = 4.dp,
    header: (LazyListScope.() -> Unit)? = null,
) {
    SortableRowList(
        items = items,
        idOf = { it.id },
        listState = listState,
        onReorder = onReorder,
        onClick = onRename,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPad),
        headerContent = header,
    ) { cat ->
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

/**
 * 「全部分类」：与空间管理的「全部空间」同一套处理方式——卡片外观与真分类行一致
 * （同高、同圆角、同底色），但文字用次要色表示它不是真分类，且不参与拖动、不能删除。
 */
@Composable
private fun PseudoCategoryRow(
    label: String,
    hint: String,
    color: String,
    count: Int,
    tag: String?,
    onPickColor: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(SortRowHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, SortRowShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(hexColor(color))
                .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                .clickable(onClick = onPickColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "$count 件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tag != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
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
