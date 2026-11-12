package com.jimmy.expiryreminder.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

/**
 * 跨设备传输的会话编排：本机接收服务 + 局域网发现 + 发送流程。
 *
 * 生命周期跟随「跨设备传输」页面：进页面 [start]，离开 [stop]（服务端口关闭、配对 token 作废）。
 * 单例是为了让 Compose 重组不会重启服务；配置变更由 Activity 自己处理，页面不会重建。
 */
class TransferManager private constructor() {

    /** 可发送/可配对的目标设备。[serviceName] 为 null 表示手动输入的地址。 */
    data class Peer(
        val key: String,
        val name: String,
        val host: String,
        val port: Int,
        val manual: Boolean = false,
    )

    data class SendState(
        val peerName: String,
        val stage: String,
        val sentBytes: Long = 0,
        val totalBytes: Long = 0,
    )

    /** 空间同步结果：两边各自新增/更新了多少件。 */
    data class SyncOutcome(
        val spaceLabel: String,
        val sent: Int,
        val localAdded: Int,
        val localUpdated: Int,
        val peerAdded: Int,
        val peerUpdated: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: TransferServer? = null
    private var discovery: TransferDiscovery? = null
    private var collectJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _pairRequest = MutableStateFlow<TransferServer.PairRequest?>(null)
    val pairRequest: StateFlow<TransferServer.PairRequest?> = _pairRequest.asStateFlow()

    private val _batches = MutableStateFlow<List<TransferServer.Incoming>>(emptyList())
    val batches: StateFlow<List<TransferServer.Incoming>> = _batches.asStateFlow()

    private val _sendState = MutableStateFlow<SendState?>(null)
    val sendState: StateFlow<SendState?> = _sendState.asStateFlow()

    private val _syncState = MutableStateFlow<SendState?>(null)
    val syncState: StateFlow<SendState?> = _syncState.asStateFlow()

    private val _syncOffer = MutableStateFlow<TransferServer.SyncOffer?>(null)
    val syncOffer: StateFlow<TransferServer.SyncOffer?> = _syncOffer.asStateFlow()

    private val _deviceName = MutableStateFlow("这台设备")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val manualPeers = mutableListOf<Peer>()

    fun start(context: Context) {
        if (_running.value) return
        val appContext = context.applicationContext
        _deviceId.value = deviceIdOf(appContext)
        _deviceName.value = defaultDeviceName()
        val srv = TransferServer(appContext, _deviceId.value, _deviceName.value)
        val port = srv.start()
        val disc = TransferDiscovery(appContext, _deviceId.value, _deviceName.value)
        disc.start(port)
        server = srv
        discovery = disc
        _port.value = port
        _localIp.value = localIpv4()
        _running.value = true

        collectJob = scope.launch {
            launch { srv.pairRequest.collect { _pairRequest.value = it } }
            launch { srv.incoming.collect { _batches.value = it } }
            launch { srv.syncOffer.collect { _syncOffer.value = it } }
            launch {
                disc.peers.collect { found ->
                    val mapped = found.map {
                        Peer(key = it.serviceName, name = it.name, host = it.host, port = it.port)
                    }
                    _peers.value = mapped + manualPeers
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        manualPeers.clear()
        _peers.value = emptyList()
        _pairRequest.value = null
        _batches.value = emptyList()
        _running.value = false
        _port.value = 0
        _sendState.value = null
        _syncState.value = null
        _syncOffer.value = null
    }

    fun respondPair(accept: Boolean) {
        _pairRequest.value?.respond?.invoke(accept)
        _pairRequest.value = null
    }

    /** 收到同步请求后作答：传本地空间 id 表示接受，null 表示拒绝。 */
    fun respondSync(spaceId: String?) {
        _syncOffer.value?.respond?.invoke(spaceId)
        _syncOffer.value = null
    }

    /** 手动输入对方 IP（自动发现失败时的兜底）；端口默认用固定端口。 */
    fun addManualPeer(host: String, port: Int = TRANSFER_PREFERRED_PORT): Peer {
        val peer = Peer(
            key = "manual:$host:$port",
            name = host,
            host = host,
            port = port,
            manual = true,
        )
        manualPeers.removeAll { it.key == peer.key }
        manualPeers.add(peer)
        _peers.update { list -> list.filterNot { it.key == peer.key } + peer }
        return peer
    }

    /** 4 位配对码，由发起方生成并显示，接收方比对后确认。 */
    fun newPin(): String = String.format(java.util.Locale.US, "%04d", Random.nextInt(0, 10000))

    /**
     * 配对 + 发送。调用前 UI 已把 [pin] 显示给用户，接收方看到同样的数字才会点接受。
     */
    suspend fun sendItems(
        context: Context,
        peer: Peer,
        items: List<ExpiryItem>,
        pin: String,
        onStage: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.failure(IllegalStateException("没有要发送的物品"))
        _sendState.value = SendState(peerName = peer.name, stage = "等待对方确认配对码 $pin")
        onStage("等待对方确认配对码 $pin")

        val paired = TransferClient.pair(
            host = peer.host,
            port = peer.port,
            pin = pin,
            deviceName = _deviceName.value,
            deviceId = _deviceId.value,
        ).getOrElse { error ->
            _sendState.value = null
            return@withContext Result.failure(error)
        }

        var zip: File? = null
        try {
            onStage("正在打包 ${items.size} 件物品…")
            _sendState.value = SendState(peerName = peer.name, stage = "正在打包物品…")
            val file = TransferPayload.writeZip(context, items, _deviceName.value)
            zip = file
            val total = file.length()
            if (total > TransferPayload.MAX_BYTES) {
                throw IllegalStateException(
                    "物品包 ${total / 1024 / 1024}MB 超过 ${TransferPayload.MAX_BYTES / 1024 / 1024}MB 上限，请减少数量"
                )
            }
            _sendState.value = SendState(peer.name, "正在发送…", 0, total)
            val result = TransferClient.send(
                host = peer.host,
                port = peer.port,
                token = paired.token,
                zip = file,
                count = items.size,
            ) { sent ->
                _sendState.update { it?.copy(sentBytes = sent, totalBytes = total) }
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            zip?.delete()
            _sendState.value = null
        }
    }

    /** 接收方收录一个批次：物品落到 [spaceId]，成功后删除缓存 zip。 */
    suspend fun importBatch(
        context: Context,
        batch: TransferServer.Incoming,
        spaceId: String,
        decisions: Map<String, TransferPayload.Decision> = emptyMap(),
    ): TransferPayload.ImportResult = withContext(Dispatchers.IO) {
        val result = TransferPayload.importBatch(
            context = context,
            store = ExpiryStore.get(),
            zip = batch.zip,
            batch = TransferPayload.Batch(batch.sender, batch.sentAt, batch.items),
            spaceId = spaceId,
            decisions = decisions,
        )
        server?.takeIncoming(batch.id)
        batch.zip.delete()
        result
    }

    /** 丢弃批次（不收录），顺手删掉缓存文件。 */
    fun discardBatch(batch: TransferServer.Incoming) {
        server?.takeIncoming(batch.id)
        batch.zip.delete()
    }

    /**
     * 空间同步（发起侧）：配对 → 上传本机该空间快照 → 对方合并后回传它的快照 →
     * 本机按同一套规则合并。两边最终是同一份内容。
     */
    suspend fun syncSpace(
        context: Context,
        peer: Peer,
        spaceId: String,
        pin: String,
    ): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        val store = ExpiryStore.get()
        val items = store.itemsInSpace(spaceId)
        val label = store.spaceLabelOf(spaceId)
        _syncState.value = SendState(peerName = peer.name, stage = "等待对方确认配对码 $pin")

        val paired = TransferClient.pair(
            host = peer.host,
            port = peer.port,
            pin = pin,
            deviceName = _deviceName.value,
            deviceId = _deviceId.value,
        ).getOrElse { error ->
            _syncState.value = null
            return@withContext Result.failure(error)
        }

        var zip: File? = null
        var replyZip: File? = null
        try {
            _syncState.value = SendState(peer.name, "正在打包本机空间…")
            val file = TransferPayload.writeZip(context, items, _deviceName.value)
            zip = file
            if (file.length() > TransferPayload.MAX_BYTES) {
                throw IllegalStateException("空间物品太多，超过单次同步上限，请分次处理")
            }
            _syncState.value = SendState(peer.name, "正在上传本机空间…", 0, file.length())
            val reply = TransferClient.sync(
                host = peer.host,
                port = peer.port,
                token = paired.token,
                zip = file,
                count = items.size,
                spaceLabel = label,
                replyDir = TransferPayload.inboxDir(context),
            ) { sent ->
                _syncState.update { it?.copy(sentBytes = sent, totalBytes = file.length()) }
            }.getOrElse { throw it }
            replyZip = reply.zip

            _syncState.update { it?.copy(stage = "正在合并对方回传的物品…") }
            val incoming = TransferPayload.readManifest(reply.zip)
                ?: throw IllegalStateException("对方回传的包无法解析")
            val plan = SpaceSync.plan(store.items, incoming.items, spaceId)
            val applied = SpaceSync.apply(context, store, reply.zip, incoming.items, spaceId, plan)

            Result.success(
                SyncOutcome(
                    spaceLabel = label,
                    sent = items.size,
                    localAdded = applied.added,
                    localUpdated = applied.overwritten,
                    peerAdded = reply.added,
                    peerUpdated = reply.updated,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            zip?.delete()
            replyZip?.delete()
            _syncState.value = null
        }
    }

    private fun deviceIdOf(context: Context): String {
        val prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        val existing = prefs.getString("deviceId", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("deviceId", created).apply()
        return created
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "Android 设备" }
    }

    /** 本机局域网 IPv4（优先 wlan 接口），用于「手动输入地址」兜底时显示。 */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        @Volatile
        private var instance: TransferManager? = null

        fun get(): TransferManager = instance ?: synchronized(this) {
            instance ?: TransferManager().also { instance = it }
        }
    }
}
