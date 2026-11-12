package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SpaceDef
import com.jimmy.expiryreminder.core.Spaces
import com.jimmy.expiryreminder.core.TransferManager
import com.jimmy.expiryreminder.core.TransferPayload
import com.jimmy.expiryreminder.core.TransferServer
import kotlinx.coroutines.launch

/**
 * 跨设备传输：
 * - 本机开着接收服务，同时用 mDNS 发现同一 Wi-Fi 下的对端；
 * - 发起方生成 4 位配对码 → 对端弹出请求并比对数字 → 同意后开始传；
 * - 收到的物品先落在本机缓存，由接收方选空间后才入库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(itemIds: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val manager = TransferManager.get()
    val storeState by store.state.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val peers by manager.peers.collectAsStateWithLifecycle()
    val deviceName by manager.deviceName.collectAsStateWithLifecycle()
    val localIp by manager.localIp.collectAsStateWithLifecycle()
    val localPort by manager.port.collectAsStateWithLifecycle()
    val pairRequest by manager.pairRequest.collectAsStateWithLifecycle()
    val batches by manager.batches.collectAsStateWithLifecycle()
    val sendState by manager.sendState.collectAsStateWithLifecycle()
    val syncState by manager.syncState.collectAsStateWithLifecycle()
    val syncOffer by manager.syncOffer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 页面在传输服务就在；离开即关闭端口，配对 token 作废
    DisposableEffect(Unit) {
        manager.start(context)
        onDispose { manager.stop() }
    }

    val items = remember(storeState, itemIds) { itemIds.mapNotNull { store.getItem(it) } }

    var manualDialog by remember { mutableStateOf(false) }
    var sendingPeer by remember { mutableStateOf<TransferManager.Peer?>(null) }
    var sendingPin by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<TransferServer.Incoming?>(null) }
    // 同名冲突处理
    var conflictFor by remember { mutableStateOf<PendingImport?>(null) }
    var manualFor by remember { mutableStateOf<PendingImport?>(null) }
    // 空间同步：本机要同步的空间（null = 本次不发起同步）
    var syncSpaceId by remember { mutableStateOf<String?>(null) }
    var syncSpaceMenu by remember { mutableStateOf(false) }
    var syncingPeer by remember { mutableStateOf<TransferManager.Peer?>(null) }
    var syncingPin by remember { mutableStateOf<String?>(null) }

    fun showMessage(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    /** 按决定入库，并把结果拼成一句话提示。 */
    fun runImport(
        batch: TransferServer.Incoming,
        spaceId: String,
        decisions: Map<String, TransferPayload.Decision>,
    ) {
        scope.launch {
            val result = manager.importBatch(context, batch, spaceId, decisions)
            val parts = buildList {
                if (result.added > 0) add("新增 ${result.added} 件")
                if (result.overwritten > 0) add("覆盖 ${result.overwritten} 件")
                if (result.skipped > 0) add("跳过 ${result.skipped} 件")
            }
            showMessage(
                if (parts.isEmpty()) "没有物品入库"
                else "${parts.joinToString("、")} · 落到「${store.spaceLabelOf(spaceId)}」",
            )
        }
    }

    fun startSend(peer: TransferManager.Peer) {
        if (items.isEmpty()) {
            showMessage("先回首页长按多选物品，再点「分享」")
            return
        }
        val pin = manager.newPin()
        sendingPeer = peer
        sendingPin = pin
        scope.launch {
            val result = manager.sendItems(context, peer, items, pin) { }
            sendingPeer = null
            sendingPin = null
            result.onSuccess { count ->
                showMessage("已送达 $count 件，等待对方选择空间收录")
            }.onFailure { error ->
                showMessage("发送失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun startSync(peer: TransferManager.Peer) {
        val spaceId = syncSpaceId ?: return
        val pin = manager.newPin()
        syncingPeer = peer
        syncingPin = pin
        scope.launch {
            val result = manager.syncSpace(context, peer, spaceId, pin)
            syncingPeer = null
            syncingPin = null
            result.onSuccess { outcome ->
                showMessage(
                    "「${outcome.spaceLabel}」同步完成："
                        + "本机新增 ${outcome.localAdded} / 更新 ${outcome.localUpdated}，"
                        + "对方新增 ${outcome.peerAdded} / 更新 ${outcome.peerUpdated}",
                )
            }.onFailure { error ->
                showMessage("同步失败：${error.message ?: "未知错误"}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("跨设备传输") },
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
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            // ---------------------------------------------- 本机状态
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (running) "接收已开启 · 同一 Wi-Fi 下可被发现" else "正在启动接收…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(
                                if (running) hexColor("#67C23A") else hexColor("#909399"),
                                RoundedCornerShape(5.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        localIp?.let { "本机地址 $it:${localPort}" } ?: "未获取到局域网地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { manualDialog = true }) { Text("手动连接") }
                }
            }

            // ---------------------------------------------- 待发送
            if (itemIds.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Card {
                    Text(
                        "待发送 ${items.size} 件",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        TransferPayload.previewText(items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "发送的是副本，本机记录不受影响；由对方选择落到哪个空间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Card {
                    Text(
                        "这里只负责接收",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "要发送物品：回首页长按多选，点顶部「分享」再进来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------------------------------------- 空间同步
            SectionLabel("空间同步")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "把两台设备的同一个空间合并成一份",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "按「名称 + 到期日」配对，同一件两边都有时以较新的记录为准；"
                            + "双向合并，不会删掉任何一边的物品。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val chosen = syncSpaceId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            OutlinedButton(
                                onClick = { syncSpaceMenu = true },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(chosen?.let { store.spaceLabelOf(it) } ?: "选择要同步的空间")
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = syncSpaceMenu,
                                onDismissRequest = { syncSpaceMenu = false },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                for (space in store.spaceOptions()) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(space.label) },
                                        trailingIcon = { Text("${store.itemCountInSpace(space.id)}", style = MaterialTheme.typography.labelSmall) },
                                        onClick = {
                                            syncSpaceId = space.id
                                            syncSpaceMenu = false
                                        },
                                    )
                                }
                                androidx.compose.material3.HorizontalDivider()
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(Spaces.UNASSIGNED_LABEL) },
                                    trailingIcon = { Text("${store.itemCountInSpace("")}", style = MaterialTheme.typography.labelSmall) },
                                    onClick = {
                                        syncSpaceId = ""
                                        syncSpaceMenu = false
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (chosen != null) {
                            Text(
                                "${store.itemsInSpace(chosen).size} 件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (chosen != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "选好后点下方设备的「同步」。对方会看到请求，由它选择自己的哪个空间参与合并。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------------------------------------- 附近设备
            SectionLabel("附近的设备")
            val visiblePeers = remember(peers) { peers.distinctBy { it.key } }
            if (visiblePeers.isEmpty()) {
                Text(
                    "正在搜索…请确认两台设备连在同一个 Wi-Fi，且都没有开启「AP 隔离」。"
                        + "找不到时可以点上面的「手动连接」输入对方地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                )
            } else {
                for (peer in visiblePeers) {
                    PeerRow(
                        peer = peer,
                        canSend = items.isNotEmpty(),
                        onSend = { startSend(peer) },
                        canSync = syncSpaceId != null,
                        onSync = { startSync(peer) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---------------------------------------------- 待接收
            if (batches.isNotEmpty()) {
                SectionLabel("待接收")
                for (batch in batches) {
                    IncomingCard(
                        batch = batch,
                        onReceive = { importing = batch },
                        onDiscard = {
                            manager.discardBatch(batch)
                            showMessage("已丢弃该批次")
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "传输只在两台设备之间直连（局域网 TCP），不经过任何服务器。"
                    + "只有对方确认配对码后才会开始发送。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    // 收到配对请求：显示对方设备名 + 配对码，用户比对后才接受
    pairRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { manager.respondPair(false) },
            title = { Text("配对请求") },
            text = {
                Column {
                    Text("「${request.fromName}」想连接这台设备")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        request.pin,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "对方屏幕上应显示同样的 4 位数字，一致才接受。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { manager.respondPair(true) }) { Text("一致，接受") }
            },
            dismissButton = {
                TextButton(onClick = { manager.respondPair(false) }) { Text("拒绝") }
            },
        )
    }

    // 发送中的弹窗：显示配对码 + 进度
    val peer = sendingPeer
    val pin = sendingPin
    if (peer != null && pin != null) {
        val progress = sendState
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在发送到「${peer.name}」") },
            text = {
                Column {
                    Text(
                        "请对方确认配对码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        pin,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (progress != null) {
                        Text(progress.stage, style = MaterialTheme.typography.bodySmall)
                        if (progress.totalBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = {
                                    (progress.sentBytes.toFloat() / progress.totalBytes)
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text("等待对方确认…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sendingPeer = null; sendingPin = null }) {
                    Text("后台继续")
                }
            },
        )
    }

    importing?.let { batch ->
        SpacePickDialog(
            onPick = { spaceId ->
                importing = null
                // 同名物品交给用户决定：直接新增 / 跳过 / 覆盖 / 逐条挑选
                val conflicts = TransferPayload.findConflicts(store, batch.items, spaceId)
                if (conflicts.isEmpty()) {
                    runImport(batch, spaceId, emptyMap())
                } else {
                    conflictFor = PendingImport(batch, spaceId, conflicts)
                }
            },
            onDismiss = { importing = null },
        )
    }

    conflictFor?.let { pending ->
        ConflictDialog(
            pending = pending,
            onAdd = {
                conflictFor = null
                runImport(pending.batch, pending.spaceId, emptyMap())
            },
            onSkip = {
                conflictFor = null
                runImport(pending.batch, pending.spaceId, pending.skipAll())
            },
            onOverwrite = {
                conflictFor = null
                runImport(pending.batch, pending.spaceId, pending.overwriteAll())
            },
            onManual = {
                conflictFor = null
                manualFor = pending
            },
            onDismiss = { conflictFor = null },
        )
    }

    manualFor?.let { pending ->
        ManualConflictDialog(
            pending = pending,
            onConfirm = { decisions ->
                manualFor = null
                runImport(pending.batch, pending.spaceId, decisions)
            },
            onDismiss = { manualFor = null },
        )
    }

    // 同步进行中：显示配对码与阶段
    val syncPeer = syncingPeer
    val syncPin = syncingPin
    if (syncPeer != null && syncPin != null) {
        val progress = syncState
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在与「${syncPeer.name}」同步") },
            text = {
                Column {
                    Text(
                        "请对方确认配对码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        syncPin,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(progress?.stage ?: "等待对方确认…", style = MaterialTheme.typography.bodySmall)
                    if (progress != null && progress.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                (progress.sentBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { syncingPeer = null; syncingPin = null }) {
                    Text("后台继续")
                }
            },
        )
    }

    // 收到对方的同步请求：选本机哪个空间参与合并
    syncOffer?.let { offer ->
        var target by remember(offer.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { manager.respondSync(null) },
            title = { Text("空间同步请求") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "「${offer.sender}」想把它那边的「${offer.spaceLabel}」（${offer.items.size} 件）"
                            + "与本机合并成同一份内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "选择本机哪个空间参与同步：",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val options = store.spaceOptions()
                    for (space in options) {
                        SpacePickRow(
                            label = space.label,
                            color = space.color,
                            selected = target == space.id,
                            onClick = { target = space.id },
                        )
                    }
                    SpacePickRow(
                        label = Spaces.UNASSIGNED_LABEL,
                        color = store.spaceColorOf(""),
                        selected = target == "",
                        onClick = { target = "" },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "合并规则：按名称 + 到期日配对，同一件以较新的记录为准；不会删除物品。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { target?.let { manager.respondSync(it) } },
                    enabled = target != null,
                ) { Text("接受并合并") }
            },
            dismissButton = {
                TextButton(onClick = { manager.respondSync(null) }) { Text("拒绝") }
            },
        )
    }

    if (manualDialog) {
        ManualPeerDialog(
            onConfirm = { host, port ->
                manualDialog = false
                manager.addManualPeer(host, port)
                showMessage("已添加设备 $host，点「发送」开始配对")
            },
            onDismiss = { manualDialog = false },
        )
    }
}

// ---------------------------------------------------------------- 私有小组件

/** 待入库的批次：空间已选好；有同名物品时先问用户怎么处理。 */
private data class PendingImport(
    val batch: TransferServer.Incoming,
    val spaceId: String,
    val conflicts: List<TransferPayload.Conflict>,
) {
    fun skipAll(): Map<String, TransferPayload.Decision> = conflicts.associate {
        it.incomingId to TransferPayload.Decision(TransferPayload.ImportAction.skip)
    }

    fun overwriteAll(): Map<String, TransferPayload.Decision> = conflicts.associate {
        it.incomingId to TransferPayload.Decision(TransferPayload.ImportAction.overwrite, it.localId)
    }
}

/** 同名冲突总策略：直接新增 / 跳过 / 覆盖 / 人工挑选。 */
@Composable
private fun ConflictDialog(
    pending: PendingImport,
    onAdd: () -> Unit,
    onSkip: () -> Unit,
    onOverwrite: () -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val duplicates = pending.conflicts.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现 $duplicates 件同名物品") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "收到的 ${pending.batch.items.size} 件里有 $duplicates 件和本地同名："
                        + pending.conflicts.take(3).joinToString("、") { it.name }
                        + if (duplicates > 3) " 等" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ConflictOption("直接新增", "同名也作为新物品收下，两件都留着", onAdd)
                ConflictOption("跳过同名", "只收不冲突的 ${pending.batch.items.size - duplicates} 件", onSkip)
                ConflictOption("覆盖同名", "用收到的内容替换本地同名物品（含图片）", onOverwrite)
                ConflictOption("人工挑选", "逐条选择新增 / 覆盖 / 跳过", onManual)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ConflictOption(title: String, desc: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 人工挑选：每件同名物品单独决定新增 / 覆盖 / 跳过（默认跳过，避免误重复）。 */
@Composable
private fun ManualConflictDialog(
    pending: PendingImport,
    onConfirm: (Map<String, TransferPayload.Decision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember(pending) {
        androidx.compose.runtime.mutableStateMapOf<String, TransferPayload.ImportAction>().apply {
            pending.conflicts.forEach { put(it.incomingId, TransferPayload.ImportAction.skip) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("逐条处理同名物品") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "其余不冲突的 ${pending.batch.items.size - pending.conflicts.size} 件会直接新增",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (conflict in pending.conflicts) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        conflict.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conflict.localExpireAt.isNotEmpty()) {
                        Text(
                            "本地到期日 ${conflict.localExpireAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (action in TransferPayload.ImportAction.entries) {
                            ChoiceChip(
                                label = action.label,
                                selected = choices[conflict.incomingId] == action,
                                onClick = { choices[conflict.incomingId] = action },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val decisions = pending.conflicts.associate { conflict ->
                    val action = choices[conflict.incomingId] ?: TransferPayload.ImportAction.skip
                    conflict.incomingId to TransferPayload.Decision(
                        action = action,
                        localId = if (action == TransferPayload.ImportAction.overwrite) conflict.localId else null,
                    )
                }
                onConfirm(decisions)
            }) { Text("入库") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val TransferPayload.ImportAction.label: String
    get() = when (this) {
        TransferPayload.ImportAction.add -> "新增"
        TransferPayload.ImportAction.skip -> "跳过"
        TransferPayload.ImportAction.overwrite -> "覆盖"
    }

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PeerRow(
    peer: TransferManager.Peer,
    canSend: Boolean,
    onSend: () -> Unit,
    canSync: Boolean,
    onSync: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (peer.manual) "手动添加 · ${peer.host}:${peer.port}"
                    else "${peer.host}:${peer.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canSync) {
                OutlinedButton(
                    onClick = onSync,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("同步") }
            }
            Button(
                onClick = onSend,
                enabled = canSend,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (canSend) "发送" else "先选物品")
            }
        }
    }
}

@Composable
private fun IncomingCard(
    batch: TransferServer.Incoming,
    onReceive: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "来自 ${batch.sender}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${batch.items.size} 件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                TransferPayload.previewText(batch.items),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReceive, shape = RoundedCornerShape(10.dp)) { Text("接收") }
                OutlinedButton(onClick = onDiscard, shape = RoundedCornerShape(10.dp)) { Text("丢弃") }
            }
        }
    }
}

/** 接收方选空间（也可以现场新建，或干脆不归属空间）。 */
@Composable
private fun SpacePickDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.spaceOptions() }
    val scope = rememberCoroutineScope()
    // 空间默认为空：不选就是「未分配」，接收方需要时再挑空间
    var selected by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun createAndSelect() {
        if (newLabel.isBlank() || creating) return
        creating = true
        scope.launch {
            val created = store.addSpace(context, newLabel)
            creating = false
            if (created != null) {
                newLabel = ""
                selected = created.id
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择接收空间") },
        text = {
            Column {
                Text(
                    "收到的物品会落到下面选中的空间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (space in options) {
                    SpacePickRow(
                        label = space.label,
                        color = space.color,
                        selected = selected == space.id,
                        onClick = { selected = space.id },
                    )
                }
                SpacePickRow(
                    label = Spaces.UNASSIGNED_LABEL,
                    color = Spaces.UNASSIGNED_COLOR,
                    selected = selected.isEmpty(),
                    onClick = { selected = "" },
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputBox(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        placeholder = "新建空间…",
                        fixedHeight = 44.dp,
                        showClear = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { createAndSelect() },
                        enabled = newLabel.isNotBlank() && !creating,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(44.dp),
                    ) { Text("新建") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text("接收") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SpacePickRow(
    label: String,
    color: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Box(Modifier.size(10.dp).background(hexColor(color), RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 自动发现失败时的兜底：手填对方 IP（端口默认固定值，一般不用改）。 */
@Composable
private fun ManualPeerDialog(onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("47821") }
    val port = portText.toIntOrNull() ?: 0
    val valid = host.trim().isNotEmpty() && port in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动连接") },
        text = {
            Column {
                Text(
                    "在对方设备的这个页面能看到的「本机地址」。自动发现被路由器挡住时才需要用到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                InputBox(
                    value = host,
                    onValueChange = { host = it },
                    placeholder = "对方 IP，如 192.168.1.23",
                    fixedHeight = 48.dp,
                    showClear = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                InputBox(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    placeholder = "端口",
                    fixedHeight = 48.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(host.trim(), port) },
                enabled = valid,
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
