package com.jimmy.expiryreminder.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SpaceDef
import com.jimmy.expiryreminder.core.SpaceNameError
import com.jimmy.expiryreminder.core.Spaces
import kotlinx.coroutines.launch

/**
 * 空间管理：用户主动创建的自定义空间。
 *
 * 外观与交互完全对齐分类管理页（同一套 [SortableRowList]）：长按整行拖动排序、
 * 点整行改名、点左侧色点换色、右侧 × 删除、底部磨砂胶囊新增。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    // 收集到的 state 变了才重算，否则新增/改名后列表不刷新（与分类管理页同一坑）
    val options = remember(state) { store.spaceOptions() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var newLabel by remember { mutableStateOf("") }
    var newError by remember { mutableStateOf<String?>(null) }
    var colorFor by remember { mutableStateOf<SpaceDef?>(null) }
    var renameFor by remember { mutableStateOf<SpaceDef?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SpaceDef?>(null) }
    // 新增成功后滚到新行，确认「加进去了」（与分类管理页一致）
    var scrollToNew by remember { mutableStateOf(false) }

    LaunchedEffect(options.size) {
        if (scrollToNew && options.isNotEmpty()) {
            scrollToNew = false
            // 列表最前面固定有一行「全部空间」，所以真空间行整体后移一位
            listState.animateScrollToItem(options.size)
        }
    }

    fun submitNew() {
        if (newLabel.isBlank()) {
            newError = "名称不能为空"
            return
        }
        scope.launch {
            val created = store.addSpace(context, newLabel)
            if (created == null) {
                newError = "已有同名空间"
            } else {
                newLabel = ""
                newError = null
                scrollToNew = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空间管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                    "长按空间可拖动排序；点整行改名，点左侧圆点换颜色（「全部空间」「未分配」也能换）；"
                        + "删除空间不会删除物品，空间里的物品会变成「未分配」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
                )

                SortableRowList(
                    items = options,
                    idOf = { it.id },
                    listState = listState,
                    onReorder = { ids -> scope.launch { store.reorderSpaces(context, ids) } },
                    onClick = { renameFor = it },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 92.dp,
                    ),
                    // 「全部空间」不是真空间，但颜色同样可改（只影响界面显示）；
                    // 它是首页默认停留的那一页，跟分类页的「全部分类」一样固定在顶部、不参与拖动
                    headerContent = {
                        item {
                            PseudoSpaceRow(
                                label = Spaces.ALL_LABEL,
                                hint = "首页默认，包含全部空间与物品",
                                color = store.spaceColorOf(Spaces.ALL),
                                count = store.itemCountInSpace(Spaces.ALL),
                                tag = "汇总",
                                onPickColor = {
                                    colorFor = SpaceDef(
                                        Spaces.ALL,
                                        Spaces.ALL_LABEL,
                                        store.spaceColorOf(Spaces.ALL),
                                    )
                                },
                            )
                        }
                    },
                    extraContent = {
                        // 「未分配」是兜底归属，放在列表最末尾、所有真空间之后；同样不参与拖动
                        item {
                            PseudoSpaceRow(
                                label = Spaces.UNASSIGNED_LABEL,
                                hint = "不属于任何空间的物品",
                                color = store.spaceColorOf(""),
                                count = store.itemCountInSpace(""),
                                tag = null,
                                onPickColor = {
                                    colorFor = SpaceDef(
                                        "",
                                        Spaces.UNASSIGNED_LABEL,
                                        store.spaceColorOf(""),
                                    )
                                },
                            )
                        }
                    },
                ) { space ->
                    SpaceRowContent(
                        space = space,
                        usage = store.itemCountInSpace(space.id),
                        onPickColor = { colorFor = space },
                        onDelete = { pendingDelete = space },
                    )
                }
            }

            // 新增区悬浮胶囊：与首页/编辑页同一套磨砂浮起，列表从它下面穿过
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
                    InputBox(
                        value = newLabel,
                        onValueChange = { newLabel = it; newError = null },
                        placeholder = "新增空间名称，如：我的房间",
                        fixedHeight = 48.dp,
                        showClear = true,
                        isError = newError != null,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { submitNew() },
                        enabled = newLabel.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("添加")
                    }
                }
                // 错误文案放到框外，样式与分类管理页一致
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

    colorFor?.let { space ->
        ColorPickerDialog(
            current = space.color,
            onPick = { color ->
                colorFor = null
                scope.launch { store.setSpaceColor(context, space.id, color) }
            },
            onDismiss = { colorFor = null },
        )
    }

    renameFor?.let { space ->
        RenameSpaceDialog(
            space = space,
            error = renameError,
            onEdit = { renameError = null },
            onConfirm = { name ->
                scope.launch {
                    when (store.renameSpace(context, space.id, name)) {
                        // 与分类管理页一致：错误就地显示在输入框下方，对话框不关
                        SpaceNameError.empty -> renameError = "名称不能为空"
                        SpaceNameError.duplicate -> renameError = "已有同名空间"
                        null -> {
                            renameFor = null
                            renameError = null
                        }
                    }
                }
            },
            onDismiss = {
                renameFor = null
                renameError = null
            },
        )
    }

    pendingDelete?.let { space ->
        val usage = store.itemCountInSpace(space.id)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除空间") },
            text = {
                Text(
                    if (usage > 0) {
                        "删除「${space.label}」后，空间里的 $usage 件物品不会被删除，" +
                            "只会变成「未分配」，之后仍可在首页和「未分配」里找到。确定删除？"
                    } else {
                        "确定删除「${space.label}」吗？"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = space
                    pendingDelete = null
                    scope.launch {
                        val moved = store.removeSpace(context, target.id)
                        val message = when {
                            moved == null -> "「${target.label}」已不存在"
                            moved == 0 -> "已删除「${target.label}」"
                            else -> "已删除「${target.label}」，$moved 件物品已移到未分配"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 真空间行的内容（容器、背景、拖动都交给 [SortableRowList]）。
 */
@Composable
private fun RowScope.SpaceRowContent(
    space: SpaceDef,
    usage: Int,
    onPickColor: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(hexColor(space.color))
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .clickable(onClick = onPickColor),
    )
    Spacer(Modifier.width(12.dp))
    Text(
        space.label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Text(
        "$usage 件",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(6.dp))
    IconButton(onClick = onDelete) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "删除空间",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
    }
    // 与分类管理页同款把手；空间行同样支持长按整行拖动
    Icon(
        Icons.Filled.Menu,
        contentDescription = "长按拖动排序",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * 「全部空间」「未分配」：卡片外观与真空间行一致（同高、同圆角、同底色），
 * 但文字用次要色表示「不是真空间」，且不参与拖动、不能删除。
 */
@Composable
private fun PseudoSpaceRow(
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

/** 空间改名：预填原名并全选，方便直接覆盖；重名/空名就地提示。 */
@Composable
private fun RenameSpaceDialog(
    space: SpaceDef,
    error: String?,
    onEdit: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(space.id) {
        mutableStateOf(TextFieldValue(space.label, TextRange(0, space.label.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(space.id) {
        withFrameNanos { }
        focus.requestFocus()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名空间") },
        text = {
            Column {
                InputBox(
                    value = value,
                    onValueChange = {
                        value = it
                        onEdit()
                    },
                    placeholder = "空间名称",
                    fixedHeight = 48.dp,
                    focusRequester = focus,
                    showClear = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
