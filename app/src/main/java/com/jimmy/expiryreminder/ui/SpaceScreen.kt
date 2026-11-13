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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
 * 交互跟分类管理页保持一致：点整行改名、点左侧圆点换色、右侧 × 删除。
 * 不做拖动排序——空间一般只有几个，按创建顺序足够用。
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

    var newLabel by remember { mutableStateOf("") }
    var newError by remember { mutableStateOf<String?>(null) }
    var colorFor by remember { mutableStateOf<SpaceDef?>(null) }
    var renameFor by remember { mutableStateOf<SpaceDef?>(null) }
    var pendingDelete by remember { mutableStateOf<SpaceDef?>(null) }

    fun submitNew() {
        if (newLabel.isBlank()) {
            newError = "名称不能为空"
            return
        }
        scope.launch {
            val created = store.addSpace(context, newLabel)
            if (created == null) newError = "空间已存在" else {
                newLabel = ""
                newError = null
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Text(
                "点整行改名，点左侧圆点换颜色（「全部空间」「未分配」也能换）；"
                    + "空间下还有物品时不能删除，先把物品移到别的空间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { it.id }) { space ->
                    SpaceRow(
                        space = space,
                        usage = store.itemCountInSpace(space.id),
                        onPickColor = { colorFor = space },
                        onRename = { renameFor = space },
                        onDelete = {
                            val usage = store.itemCountInSpace(space.id)
                            if (usage > 0) {
                                Toast.makeText(
                                    context,
                                    "「${space.label}」下还有 $usage 件物品，先移走再删除",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                pendingDelete = space
                            }
                        },
                    )
                }

                // 「全部空间」「未分配」不是真空间，但颜色同样可改（只影响界面显示）
                item {
                    PseudoSpaceRow(
                        label = Spaces.ALL_LABEL,
                        hint = "首页默认停在这里，包含所有空间与未分配的物品",
                        color = store.spaceColorOf(Spaces.ALL),
                        count = store.itemCountInSpace(Spaces.ALL),
                        tag = "汇总",
                        onPickColor = {
                            colorFor = SpaceDef(Spaces.ALL, Spaces.ALL_LABEL, store.spaceColorOf(Spaces.ALL))
                        },
                    )
                }

                item {
                    PseudoSpaceRow(
                        label = Spaces.UNASSIGNED_LABEL,
                        hint = "不属于任何空间的物品会显示在这里",
                        color = store.spaceColorOf(""),
                        count = store.itemCountInSpace(""),
                        tag = null,
                        onPickColor = {
                            colorFor = SpaceDef("", Spaces.UNASSIGNED_LABEL, store.spaceColorOf(""))
                        },
                    )
                }
            }

            // 新增区钉在底部，键盘弹出时跟着抬起
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InputBox(
                            value = newLabel,
                            onValueChange = { newLabel = it; newError = null },
                            placeholder = "新增空间名称，如：爸妈家",
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
                    newError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
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
            onConfirm = { name ->
                scope.launch {
                    val error = store.renameSpace(context, space.id, name)
                    when (error) {
                        SpaceNameError.empty -> Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
                        SpaceNameError.duplicate -> Toast.makeText(context, "空间名已存在", Toast.LENGTH_SHORT).show()
                        null -> renameFor = null
                    }
                }
            },
            onDismiss = { renameFor = null },
        )
    }

    pendingDelete?.let { space ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除空间") },
            text = { Text("确定删除「${space.label}」吗？空间里的物品不会受影响（空空间才能删）。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = space
                    pendingDelete = null
                    scope.launch {
                        val ok = store.removeSpace(context, target.id)
                        if (!ok) {
                            Toast.makeText(context, "删除失败：空间下还有物品", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

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
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun SpaceRow(
    space: SpaceDef,
    usage: Int,
    onPickColor: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onRename)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(hexColor(space.color))
                    .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .then(
                        if (onPickColor != null) Modifier.clickable(onClick = onPickColor) else Modifier
                    ),
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
                    contentDescription = if (usage > 0) "空间下有物品，不能删除" else "删除空间",
                    tint = if (usage > 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 空间改名：预填原名并全选，方便直接覆盖。 */
@Composable
private fun RenameSpaceDialog(
    space: SpaceDef,
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
                    onValueChange = { value = it },
                    placeholder = "空间名称",
                    fixedHeight = 48.dp,
                    focusRequester = focus,
                    showClear = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
