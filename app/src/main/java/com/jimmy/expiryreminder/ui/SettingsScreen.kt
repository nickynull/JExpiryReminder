package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.BuildConfig
import com.jimmy.expiryreminder.core.Backup
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 设置页：主题、通知、备份导出导入、清空数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = ExpiryStore.get()
    val settings = SettingsStore.get()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showTimePicker by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val timeState = rememberTimePickerState(
        initialHour = notifyTime.substringBefore(':').toIntOrNull() ?: 9,
        initialMinute = notifyTime.substringAfter(':').toIntOrNull() ?: 0,
        is24Hour = true,
    )

    // 导出：SAF 建文档
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    Backup.exportTo(context, uri, store)
                    snackbar.showSnackbar("备份已导出")
                } catch (e: Exception) {
                    snackbar.showSnackbar("导出失败：${e.message}")
                }
            }
        }
    }

    // 导入：SAF 选文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份") },
            text = { Text("覆盖：清空当前数据后整包写入。\n合并：相同 id 的记录会被备份覆盖，其余保留。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = uri
                    pendingImport = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val payload = Backup.parseFrom(context, target)
                            val result = Backup.applyBackup(context, store, payload, Backup.ImportMode.merge)
                            snackbar.showSnackbar("合并完成：备份 ${result.replacedItems} 件，现有 ${result.totalItems} 件")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("导入失败：${e.message}")
                        }
                    }
                }) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val target = uri
                    pendingImport = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val payload = Backup.parseFrom(context, target)
                            val result = Backup.applyBackup(context, store, payload, Backup.ImportMode.replace)
                            snackbar.showSnackbar("覆盖完成：共 ${result.totalItems} 件")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("导入失败：${e.message}")
                        }
                    }
                }) { Text("覆盖") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("通知")

            ListItem(
                headlineContent = { Text("启用到期通知") },
                supportingContent = { Text("关闭后不再推送任何提醒") },
                trailingContent = {
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, notifyEnabled = enabled)
                            ReminderService.syncScheduled(context, store, enabled, notifyTime)
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("推送时刻") },
                supportingContent = { Text("每天 $notifyTime 推送到期提醒") },
                trailingContent = {
                    OutlinedButton(onClick = { showTimePicker = true }) { Text(notifyTime) }
                },
            )

            if (!ReminderService.canScheduleExact(context)) {
                ListItem(
                    headlineContent = { Text("精确闹钟权限") },
                    supportingContent = { Text("未授予时提醒可能延迟几分钟触发，点此前往授权") },
                    trailingContent = {
                        TextButton(onClick = {
                            activity?.startActivity(
                                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("去授权") }
                    },
                )
            }

            HorizontalDivider()
            SectionTitle("外观")

            // 横向单选项，三档并排一行，节省纵向空间
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (mode in SettingsStore.ThemeMode.entries) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = themeMode == mode,
                                onClick = { settings.update(context, themeMode = mode) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = null,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            modeLabel(mode),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("备份")

            ListItem(
                headlineContent = { Text("导出备份") },
                supportingContent = { Text("打包 data.json 与全部图片（.zip）") },
                trailingContent = {
                    OutlinedButton(onClick = { exportLauncher.launch(Backup.backupFileName()) }) {
                        Text("导出")
                    }
                },
            )

            ListItem(
                headlineContent = { Text("导入备份") },
                supportingContent = { Text("支持本应用导出的备份") },
                trailingContent = {
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("导入") }
                },
            )

            HorizontalDivider()
            SectionTitle("数据")

            ListItem(
                headlineContent = { Text("清空全部数据") },
                supportingContent = { Text("删除所有物品与图片，不可恢复") },
                trailingContent = {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                },
            )

            HorizontalDivider()
            SectionTitle("关于")

            ListItem(
                headlineContent = { Text("到期提醒") },
                supportingContent = { Text("作者：Jimmy") },
                trailingContent = {
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择推送时刻") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val time = "%02d:%02d".format(timeState.hour, timeState.minute)
                    settings.update(context, notifyTime = time)
                    ReminderService.syncScheduled(context, store, notifyEnabled, time)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部数据") },
            text = { Text("⚠️ 此操作非常危险，可能导致不可逆的数据丢失！建议先导出备份。确定要继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        store.clearItems(context)
                        ReminderService.syncScheduled(context, store, notifyEnabled, notifyTime)
                        snackbar.showSnackbar("已清空全部物品")
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun modeLabel(mode: SettingsStore.ThemeMode): String = when (mode) {
    SettingsStore.ThemeMode.system -> "跟随系统"
    SettingsStore.ThemeMode.light -> "浅色"
    SettingsStore.ThemeMode.dark -> "深色"
}
