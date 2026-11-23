package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.BuildConfig
import com.jimmy.expiryreminder.core.Backup
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.core.StatCard
import com.jimmy.expiryreminder.widget.ExpiryWidgetBase
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页：通知、外观、同步、备份与关于；全部分组平铺，不再折叠。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onTransfer: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = ExpiryStore.get()
    val settings = SettingsStore.get()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val useAlarmClock by settings.useAlarmClock.collectAsStateWithLifecycle()
    val statCards by settings.statCards.collectAsStateWithLifecycle()
    val liveUpdateEnabled by settings.liveUpdateEnabled.collectAsStateWithLifecycle()
    val hideNotificationDetail by settings.hideNotificationDetail.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var showTimePicker by remember { mutableStateOf(false) }
    // 滚轮对话框里的临时选择，点「确定」才写回设置
    var wheelHour by remember { mutableIntStateOf(notifyTime.substringBefore(':').toIntOrNull() ?: 9) }
    var wheelMinute by remember { mutableIntStateOf(notifyTime.substringAfter(':').toIntOrNull() ?: 0) }
    var showStatCards by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLiveDetail by remember { mutableStateOf(false) }
    var showReliabilityDetail by remember { mutableStateOf(false) }

    // 权限/能力状态每次回到前台都重新读一遍：用户去系统设置改完回来能立刻看到结果
    var reliabilityTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reliabilityTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val exactGranted = remember(reliabilityTick) { ReminderService.canScheduleExact(context) }
    val batteryIgnored = remember(reliabilityTick) {
        ReminderService.isIgnoringBatteryOptimizations(context)
    }
    // 流体云 / 系统实时动态能力：每次回前台重读
    val liveStatus = remember(reliabilityTick, liveUpdateEnabled) {
        ReminderService.liveUpdateStatus(context)
    }
    val liveStatusText = when {
        !liveUpdateEnabled -> "已关闭，只保留逐条到期通知"
        !liveStatus.supported -> "系统低于 Android 16，不支持实时动态"
        liveStatus.promoted -> "已提升为实时动态 / 状态栏胶囊"
        !liveStatus.canPost -> "系统未允许发布实时动态，点此去开启"
        !liveStatus.active -> "当前没有 7 天内到期物品，或汇总尚未发布"
        liveStatus.promotable == false -> "通知不满足系统提升条件"
        else -> "已请求提升但暂未提升，点此可重发"
    }
    // 已配对设备数：以长期信任表为准（水位文件只说明「同步过」，不代表仍被信任）

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSection("通知与提醒")

            ListItem(
                headlineContent = { Text("启用到期通知") },
                supportingContent = { Text("关闭后不再推送任何提醒") },
                trailingContent = {
                    AppSwitch(
                        checked = notifyEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, notifyEnabled = enabled)
                            ReminderService.syncScheduled(
                                context, store, enabled, notifyTime, useAlarmClock, liveUpdateEnabled,
                            )
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("推送时刻") },
                supportingContent = { Text("每天 $notifyTime 推送到期提醒") },
                trailingContent = {
                    OutlinedButton(onClick = {
                        // 每次打开都从当前设置重新起算，滚轮从现有时刻开始
                        wheelHour = notifyTime.substringBefore(':').toIntOrNull() ?: 9
                        wheelMinute = notifyTime.substringAfter(':').toIntOrNull() ?: 0
                        showTimePicker = true
                    }) { Text(notifyTime) }
                },
            )

            ListItem(
                headlineContent = { Text("闹钟式提醒") },
                supportingContent = {
                    Text("状态栏显示闹钟图标，抗系统省电清理最稳；关闭后提醒可能延后")
                },
                trailingContent = {
                    AppSwitch(
                        checked = useAlarmClock,
                        onCheckedChange = { enabled ->
                            settings.update(context, useAlarmClock = enabled)
                            ReminderService.syncScheduled(
                                context, store, notifyEnabled, notifyTime, enabled, liveUpdateEnabled,
                            )
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("流体云提醒") },
                supportingContent = { Text(liveStatusText) },
                modifier = Modifier.clickable { showLiveDetail = true },
                trailingContent = {
                    AppSwitch(
                        checked = liveUpdateEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, liveUpdateEnabled = enabled)
                            scope.launch {
                                ReminderService.syncScheduled(
                                    context, store, notifyEnabled, notifyTime, useAlarmClock, enabled,
                                )
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("锁屏隐藏详情") },
                supportingContent = { Text("开启后锁屏只显示「到期提醒」，解锁后才看得到物品名") },
                trailingContent = {
                    AppSwitch(
                        checked = hideNotificationDetail,
                        onCheckedChange = { enabled ->
                            settings.update(context, hideNotificationDetail = enabled)
                            // 已发布的通知不会自己变，立刻按新口径重发一次流体云
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        ReminderService.repostLiveSummary(context, store, liveUpdateEnabled)
                                    }
                                }
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("后台运行保障") },
                supportingContent = {
                    Text(
                        buildString {
                            append(if (exactGranted) "精确闹钟已授予" else "精确闹钟未授予")
                            append(" · ")
                            append(if (batteryIgnored) "已忽略电池优化" else "未忽略电池优化")
                            append("\n点此检查权限并查看各机型自启动设置")
                        },
                    )
                },
                modifier = Modifier.clickable { showReliabilityDetail = true },
                trailingContent = { TextButton(onClick = { showReliabilityDetail = true }) { Text("检查") } },
            )

            SettingsSection("外观与显示")

            ListItem(
                headlineContent = { Text("主题") },
                supportingContent = { Text("桌面小组件同步跟随应用主题") },
            )
            // 横向单选项，三档并排一行
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (mode in SettingsStore.ThemeMode.entries) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = themeMode == mode,
                                onClick = {
                                    settings.update(context, themeMode = mode)
                                    ExpiryWidgetBase.refreshAll(context)
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Spacer(Modifier.width(2.dp))
                        Text(modeLabel(mode), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }

            ListItem(
                headlineContent = { Text("主页统计卡") },
                supportingContent = { Text(statCards.joinToString(" / ") { it.label }) },
                modifier = Modifier.clickable { showStatCards = true },
                trailingContent = {
                    TextButton(onClick = { showStatCards = true }) { Text("选择") }
                },
            )

            ListItem(
                headlineContent = { Text("刷新桌面小组件") },
                supportingContent = { Text("主题或数据未及时更新时，可手动刷新一次") },
                trailingContent = {
                    TextButton(onClick = {
                        ExpiryWidgetBase.refreshAll(context)
                        scope.launch { snackbar.showSnackbar("桌面小组件已刷新") }
                    }) { Text("刷新") }
                },
            )

            SettingsSection("跨设备同步")

            ListItem(
                headlineContent = { Text("跨设备分享") },
                supportingContent = { Text("同一 Wi-Fi 下与另一台设备配对，双向传输物品记录") },
                trailingContent = {
                    OutlinedButton(onClick = onTransfer) { Text("打开") }
                },
            )

            SettingsSection("数据与备份")

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

            ListItem(
                headlineContent = { Text("清空全部数据") },
                supportingContent = { Text("删除所有物品与图片，不可恢复") },
                trailingContent = {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                },
            )

            SettingsSection("关于")

            ListItem(
                headlineContent = { Text("到期提醒") },
                supportingContent = { Text("作者：Jimmy & Sunny") },
                trailingContent = {
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("GitHub 项目地址") },
                supportingContent = { Text(GITHUB_URL.removePrefix("https://")) },
                modifier = Modifier.clickable { openUrl(context, GITHUB_URL) },
                trailingContent = {
                    TextButton(onClick = { openUrl(context, GITHUB_URL) }) { Text("打开") }
                },
            )
        }
    }

    if (showStatCards) {
        // 勾选立即生效并保存；取消最后一张时忽略这次点击，保证主页至少有一张卡
        AlertDialog(
            onDismissRequest = { showStatCards = false },
            title = { Text("主页统计卡") },
            text = {
                Column {
                    for (card in StatCard.entries) {
                        val checked = card in statCards
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    val next = if (checked) statCards - card else statCards + card
                                    if (next.isNotEmpty()) {
                                        settings.update(context, statCards = next)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        card.status?.let { hexColor(it.color) }
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(card.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按此顺序显示在主页顶部，至少保留一张。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatCards = false }) { Text("完成") }
            },
        )
    }

    if (showTimePicker) {
        // 用自绘 Dialog 而不是 AlertDialog：滚轮中心行可以直接敲数字，
        // 数字键盘弹起时需要 imePadding 把整块内容顶上去
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            "选择推送时刻",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text(
                            "滑动选择，或在下方输入框直接输入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        TimeWheelPicker(
                            hour = wheelHour,
                            minute = wheelMinute,
                            onHourChange = { wheelHour = it },
                            onMinuteChange = { wheelMinute = it },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                            TextButton(onClick = {
                                // 先让滚轮失焦把草稿交上来，等一帧再读，否则会存到旧值
                                focusManager.clearFocus()
                                scope.launch {
                                    withFrameNanos { }
                                    val time = "%02d:%02d".format(wheelHour, wheelMinute)
                                    settings.update(context, notifyTime = time)
                                    ReminderService.syncScheduled(
                                        context, store, notifyEnabled, time, useAlarmClock, liveUpdateEnabled,
                                    )
                                    showTimePicker = false
                                }
                            }) { Text("确定") }
                        }
                    }
                }
            }
        }
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
                        ReminderService.syncScheduled(
                            context, store, notifyEnabled, notifyTime, useAlarmClock, liveUpdateEnabled,
                        )
                        snackbar.showSnackbar("已清空全部物品")
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showLiveDetail) {
        AlertDialog(
            onDismissRequest = { showLiveDetail = false },
            title = { Text("流体云提醒") },
            text = {
                Column {
                    Text("当前状态：$liveStatusText")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "7 天内到期物品会合成一条常驻提醒；能否显示为流体云由系统决定。" +
                            "普通到期通知与流体云是两条链路，互不影响。若状态未提升，" +
                            "先确认系统里的实时活动开关，再点「重发」。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { ReminderService.openLiveUpdateSettings(context) }) {
                        Text("去开启")
                    }
                    TextButton(onClick = {
                        ReminderService.repostLiveSummary(context, store, liveUpdateEnabled)
                        reliabilityTick++
                        scope.launch { snackbar.showSnackbar("已重新发布到期汇总") }
                    }) { Text("重发") }
                    TextButton(onClick = { showLiveDetail = false }) { Text("知道了") }
                }
            },
        )
    }

    if (showReliabilityDetail) {
        AlertDialog(
            onDismissRequest = { showReliabilityDetail = false },
            title = { Text("后台运行保障") },
            text = {
                Column {
                    PermissionRow(
                        title = "精确闹钟权限",
                        description = if (exactGranted) "已授予，提醒可以准点触发" else "未授予，后台容易被推迟或丢掉",
                        granted = exactGranted,
                        grantedLabel = "已授予",
                        actionLabel = "去授权",
                        onAction = { openExactAlarmSettings(activity, context) },
                    )
                    PermissionRow(
                        title = "忽略电池优化",
                        description = if (batteryIgnored) "已忽略，系统不会因省电清理提醒" else "未忽略，部分机型会限制后台",
                        granted = batteryIgnored,
                        grantedLabel = "已忽略",
                        actionLabel = "去设置",
                        onAction = { openBatterySettings(activity, context) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "自启动 / 后台运行还需按机型手动允许：\n" +
                            "· 小米 / 红米：应用管理 → 到期提醒 → 自启动 + 省电策略「无限制」\n" +
                            "· 华为 / 荣耀：应用启动管理 → 到期提醒 → 手动管理（全部打开）\n" +
                            "· OPPO / 一加：自启动管理开启，耗电管理选「允许后台运行」\n" +
                            "· vivo / iQOO：自启动开启，后台耗电选「允许后台高耗电」\n\n" +
                            "在最近任务里「强行停止」会清掉全部闹钟，重新打开应用即可恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReliabilityDetail = false }) { Text("知道了") }
            },
        )
    }
}

/** 对话框内的权限状态行：左侧标题+说明，右侧状态或操作按钮。 */
@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 两侧保持完全相同的文字规格与内边距，仅点击行为不同，避免视觉基线错位
        val actionModifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 8.dp)
        if (granted) {
            Text(
                grantedLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = actionModifier,
            )
        } else {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = actionModifier.clickable(onClick = onAction),
            )
        }
    }
}

/** 平铺设置分组标题：只做视觉分节，不折叠、不点击。 */
@Composable
private fun SettingsSection(title: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun modeLabel(mode: SettingsStore.ThemeMode): String = when (mode) {
    SettingsStore.ThemeMode.system -> "跟随系统"
    SettingsStore.ThemeMode.light -> "浅色"
    SettingsStore.ThemeMode.dark -> "深色"
}

/** 跳到系统的「闹钟和提醒」特殊权限页。 */
private fun openExactAlarmSettings(activity: Activity?, context: Context) {
    runCatching {
        activity?.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
}

/** 申请忽略电池优化；部分 ROM 没有直连入口时退回到电池优化列表。 */
private fun openBatterySettings(activity: Activity?, context: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { activity?.startActivity(direct) }.onFailure {
        runCatching { activity?.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** 关于页展示的项目主页。 */
private const val GITHUB_URL = "https://github.com/nickynull/JExpiryReminder"

/** 用浏览器打开外部链接；系统无浏览器时静默忽略。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
