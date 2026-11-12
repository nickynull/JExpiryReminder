package com.jimmy.expiryreminder.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** mDNS 服务类型：只在本机局域网广播，不经过任何服务器。 */
const val TRANSFER_SERVICE_TYPE = "_jexpiry._tcp."

/** 固定端口，方便「手动输入 IP」兜底；被占用时退回随机端口（此时只能靠自动发现）。 */
const val TRANSFER_PREFERRED_PORT = 47821

private const val PAIR_TIMEOUT_MS = 90_000L
private const val CONNECT_TIMEOUT_MS = 8_000
private const val SEND_TIMEOUT_MS = 120_000
/** 空间同步要等对方选空间 + 双方各自合并，给的时间比单向发送长一些。 */
private const val SYNC_TIMEOUT_MS = 180_000L

/**
 * 极简的行分隔 JSON 协议：每条消息一行 JSON，二进制载荷跟在 header 后面按 size 精确读取。
 * 两台设备都是自己人，不需要 HTTP 那套解析，也顺带绕开明文流量策略。
 */
object TransferWire {

    private const val MAX_HEADER = 8 * 1024

    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b == '\r'.code) continue
            if (buf.size() >= MAX_HEADER) throw IOException("消息头过长")
            buf.write(b)
        }
    }

    fun writeMessage(out: OutputStream, json: JSONObject) {
        out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 精确读 [size] 字节写进 [out]；对端提前断开时抛 [IOException]。 */
    fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("连接中断，数据不完整")
            out.write(buf, 0, n)
            left -= n
        }
    }
}

/**
 * 接收端服务：监听本机端口，处理配对请求与物品包。
 *
 * 配对：对端发来 4 位配对码 → 本机弹出确认（用户比对两台屏幕上的数字）→ 同意后发一个一次性 token，
 * 之后传输必须带 token。token 只存在内存里，离开传输页即失效。
 */
class TransferServer(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
) {

    data class PairRequest(
        val pin: String,
        val fromName: String,
        val fromId: String,
        /** 用户点「接受 / 拒绝」后调用。 */
        val respond: (Boolean) -> Unit,
    )

    /** 已收进本地缓存、等用户选空间的批次。 */
    data class Incoming(
        val id: String,
        val sender: String,
        val sentAt: String,
        val items: List<ExpiryItem>,
        val zip: File,
    )

    /** 对方发起的空间同步：等本机用户选空间并确认。 */
    data class SyncOffer(
        val id: String,
        val sender: String,
        val spaceLabel: String,
        val items: List<ExpiryItem>,
        val zip: File,
        /** 传本地空间 id 表示接受；[spaceId] 为 null 表示拒绝。 */
        val respond: (String?) -> Unit,
    )

    private val _syncOffer = MutableStateFlow<SyncOffer?>(null)
    val syncOffer: StateFlow<SyncOffer?> = _syncOffer.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _pairRequest = MutableStateFlow<PairRequest?>(null)
    val pairRequest: StateFlow<PairRequest?> = _pairRequest.asStateFlow()

    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val tokens = ConcurrentHashMap<String, String>() // token -> 对端 deviceId
    private val pendingPair = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingSync = AtomicReference<CompletableDeferred<String?>?>(null)

    fun start(): Int {
        serverSocket?.let { return _port.value }
        val socket = try {
            ServerSocket(TRANSFER_PREFERRED_PORT)
        } catch (_: Exception) {
            ServerSocket(0)
        }
        serverSocket = socket
        _port.value = socket.localPort
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                launch { handle(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { acceptJob?.cancel() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob = null
        _port.value = 0
        pendingPair.getAndSet(null)?.complete(false)
        pendingSync.getAndSet(null)?.complete(null)
        _pairRequest.value = null
        _syncOffer.value = null
        _incoming.value = emptyList()
        tokens.clear()
        // 未收录的批次离开页面即作废，顺手清掉缓存文件，免得 cache 越攒越大
        runCatching {
            TransferPayload.inboxDir(context).listFiles()?.forEach { it.delete() }
        }
        scope.cancel()
    }

    /** 从待接收列表里移除（已收录或用户丢弃）。 */
    fun takeIncoming(id: String): Incoming? {
        val batch = _incoming.value.firstOrNull { it.id == id } ?: return null
        _incoming.update { list -> list.filterNot { it.id == id } }
        return batch
    }

    /** 用户对同步请求作出决定：传空间 id = 接受，null = 拒绝。 */
    fun respondSync(spaceId: String?) {
        _syncOffer.value?.respond?.invoke(spaceId)
        _syncOffer.value = null
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SEND_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val header = TransferWire.readLine(input) ?: return
                val msg = runCatching { JSONObject(header) }.getOrNull() ?: return
                when (msg.optString("cmd")) {
                    "ping" -> TransferWire.writeMessage(
                        output,
                        JSONObject().put("ok", true).put("name", deviceName).put("id", deviceId),
                    )
                    "pair" -> handlePair(output, msg)
                    "send" -> handleSend(input, output, msg)
                    "sync" -> handleSync(input, output, msg)
                    else -> TransferWire.writeMessage(
                        output, JSONObject().put("ok", false).put("error", "未知指令"),
                    )
                }
            } catch (_: Exception) {
                // 对端断开 / 超时：不影响本机
            }
        }
    }

    private suspend fun handlePair(output: OutputStream, msg: JSONObject) {
        if (pendingPair.get() != null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方正忙，请稍后重试"))
            return
        }
        val pin = msg.optString("pin")
        val fromName = msg.optString("device").ifBlank { "另一台设备" }
        val fromId = msg.optString("deviceId")
        val deferred = CompletableDeferred<Boolean>()
        pendingPair.set(deferred)
        _pairRequest.value = PairRequest(
            pin = pin,
            fromName = fromName,
            fromId = fromId,
            respond = { accept ->
                // 只允许「当前这个请求」被响应，避免旧弹窗误提交
                if (pendingPair.compareAndSet(deferred, null)) {
                    _pairRequest.value = null
                    deferred.complete(accept)
                }
            },
        )
        val accepted = withTimeoutOrNull(PAIR_TIMEOUT_MS) { deferred.await() } ?: false
        pendingPair.set(null)
        _pairRequest.value = null

        if (!accepted) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方拒绝了配对"))
            return
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        tokens[token] = fromId
        TransferWire.writeMessage(
            output,
            JSONObject()
                .put("ok", true)
                .put("token", token)
                .put("name", deviceName)
                .put("id", deviceId),
        )
    }

    private fun handleSend(input: InputStream, output: OutputStream, msg: JSONObject) {
        val token = msg.optString("token")
        if (tokens[token] == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "配对已失效，请重新配对"))
            return
        }
        val size = msg.optLong("size")
        if (size <= 0 || size > TransferPayload.MAX_BYTES) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "物品包为空或超出大小上限"))
            return
        }
        val zip = File(TransferPayload.inboxDir(context), "in-${System.currentTimeMillis()}.zip")
        zip.outputStream().buffered().use { out ->
            TransferWire.copyExactly(input, out, size)
        }
        val batch = TransferPayload.readManifest(zip)
        if (batch == null || batch.items.isEmpty()) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "物品包无法解析"))
            return
        }
        _incoming.update {
            it + Incoming(
                id = uid(),
                sender = batch.sender,
                sentAt = batch.sentAt,
                items = batch.items,
                zip = zip,
            )
        }
        // 先告诉对方「已送达」；本机用户选完空间才算真正收录
        TransferWire.writeMessage(output, JSONObject().put("ok", true).put("count", batch.items.size))
    }

    /**
     * 空间同步（接收侧）：收下对方快照 → 等用户选本机空间 → 合并 → 把本机该空间的
     * 合并后快照回传。对方收到后再合并一次，两边收敛成同一份。
     */
    private suspend fun handleSync(input: InputStream, output: OutputStream, msg: JSONObject) {
        val token = msg.optString("token")
        if (tokens[token] == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "配对已失效，请重新配对"))
            return
        }
        if (pendingSync.get() != null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方正忙，请稍后重试"))
            return
        }
        val size = msg.optLong("size")
        if (size <= 0 || size > TransferPayload.MAX_BYTES) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "同步包为空或超出大小上限"))
            return
        }
        val zip = File(TransferPayload.inboxDir(context), "sync-${System.currentTimeMillis()}.zip")
        zip.outputStream().buffered().use { out -> TransferWire.copyExactly(input, out, size) }
        val batch = TransferPayload.readManifest(zip)
        if (batch == null) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "同步包无法解析"))
            return
        }

        val deferred = CompletableDeferred<String?>()
        pendingSync.set(deferred)
        _syncOffer.value = SyncOffer(
            id = uid(),
            sender = batch.sender,
            spaceLabel = msg.optString("space").ifBlank { "未命名空间" },
            items = batch.items,
            zip = zip,
            respond = { spaceId ->
                if (pendingSync.compareAndSet(deferred, null)) {
                    _syncOffer.value = null
                    deferred.complete(spaceId)
                }
            },
        )

        val targetSpace = withTimeoutOrNull(SYNC_TIMEOUT_MS) { deferred.await() }
        pendingSync.set(null)
        _syncOffer.value = null
        if (targetSpace == null) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方拒绝了同步"))
            return
        }

        val store = ExpiryStore.get()
        val plan = SpaceSync.plan(store.items, batch.items, targetSpace)
        val applied = SpaceSync.apply(context, store, zip, batch.items, targetSpace, plan)
        zip.delete()

        // 回传合并后本机该空间的快照，对方据此补齐自己缺的那部分
        val snapshot = store.itemsInSpace(targetSpace)
        val reply = TransferPayload.writeZip(context, snapshot, deviceName)
        try {
            TransferWire.writeMessage(
                output,
                JSONObject()
                    .put("ok", true)
                    .put("added", applied.added)
                    .put("updated", applied.overwritten)
                    .put("count", snapshot.size)
                    .put("size", reply.length()),
            )
            reply.inputStream().buffered().use { it.copyTo(output) }
            output.flush()
        } finally {
            reply.delete()
        }
    }
}

/** 局域网设备发现（mDNS）。只负责发现，不传数据。 */
class TransferDiscovery(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
) {

    data class Peer(
        val serviceName: String,
        val deviceId: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var started = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    /** 已经解析过（或正在解析）的服务名，避免同一台设备被反复 resolve。 */
    private val seen = ConcurrentHashMap.newKeySet<String>()
    /** resolveService 并发调用会 FAILURE_ALREADY_ACTIVE，这里串成队列逐个来。 */
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private val resolvingOne = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(port: Int) {
        if (started) return
        val manager = nsd ?: return
        started = true

        val info = NsdServiceInfo().apply {
            serviceName = "JEX-${deviceId.take(8)}"
            serviceType = TRANSFER_SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != TRANSFER_SERVICE_TYPE) return
                if (!seen.add(serviceInfo.serviceName)) return
                enqueueResolve(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                seen.remove(serviceInfo.serviceName)
                _peers.update { list -> list.filterNot { it.serviceName == serviceInfo.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = discovery
        runCatching {
            manager.discoverServices(TRANSFER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }
    }

    private fun enqueueResolve(manager: NsdManager, info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        drainResolve(manager)
    }

    private fun drainResolve(manager: NsdManager) {
        if (!resolvingOne.compareAndSet(false, true)) return
        val next = synchronized(resolveQueue) { resolveQueue.pollFirst() }
        if (next == null) {
            resolvingOne.set(false)
            return
        }
        fun finish() {
            resolvingOne.set(false)
            drainResolve(manager)
        }
        @Suppress("DEPRECATION")
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finish()
            override fun onServiceResolved(info: NsdServiceInfo) {
                addPeer(info)
                finish()
            }
        }
        val started = runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(next, listener)
        }.isSuccess
        if (!started) finish()
    }

    private fun addPeer(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        val port = info.port
        if (port <= 0) return
        val attrs = info.attributes.orEmpty()
        val id = attrs["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: info.serviceName
        if (id == deviceId) return // 自己
        val name = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: info.serviceName
        val peer = Peer(info.serviceName, id, name, host, port)
        _peers.update { list ->
            val index = list.indexOfFirst { it.deviceId == id }
            if (index >= 0) list.toMutableList().also { it[index] = peer } else list + peer
        }
    }

    fun stop() {
        val manager = nsd ?: return
        discoveryListener?.let { listener -> runCatching { manager.stopServiceDiscovery(listener) } }
        registrationListener?.let { listener -> runCatching { manager.unregisterService(listener) } }
        discoveryListener = null
        registrationListener = null
        started = false
        seen.clear()
        synchronized(resolveQueue) { resolveQueue.clear() }
        resolvingOne.set(false)
        _peers.value = emptyList()
    }
}

/** 发送端：配对 + 上传物品包。 */
object TransferClient {

    data class Paired(val token: String, val deviceName: String, val deviceId: String)

    suspend fun pair(
        host: String,
        port: Int,
        pin: String,
        deviceName: String,
        deviceId: String,
    ): Result<Paired> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = (PAIR_TIMEOUT_MS + 10_000).toInt()
            val out = BufferedOutputStream(socket.getOutputStream())
            TransferWire.writeMessage(
                out,
                JSONObject()
                    .put("cmd", "pair")
                    .put("pin", pin)
                    .put("device", deviceName)
                    .put("deviceId", deviceId),
            )
            val line = TransferWire.readLine(BufferedInputStream(socket.getInputStream()))
                ?: throw IOException("对方没有响应")
            val json = JSONObject(line)
            if (!json.optBoolean("ok")) {
                throw IOException(json.optStringOrNull("error") ?: "配对失败")
            }
            Paired(
                token = json.optString("token"),
                deviceName = json.optString("name").ifBlank { "对方设备" },
                deviceId = json.optString("id"),
            )
        }
    }

    /** 上传 zip；[onProgress] 回传已发送字节数。返回对方确认收到的件数。 */
    suspend fun send(
        host: String,
        port: Int,
        token: String,
        zip: File,
        count: Int,
        onProgress: (Long) -> Unit,
    ): Result<Int> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SEND_TIMEOUT_MS
            val out = BufferedOutputStream(socket.getOutputStream())
            TransferWire.writeMessage(
                out,
                JSONObject()
                    .put("cmd", "send")
                    .put("token", token)
                    .put("count", count)
                    .put("size", zip.length()),
            )
            zip.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent)
                }
            }
            out.flush()
            val line = TransferWire.readLine(BufferedInputStream(socket.getInputStream()))
                ?: throw IOException("对方没有响应")
            val json = JSONObject(line)
            if (!json.optBoolean("ok")) {
                throw IOException(json.optStringOrNull("error") ?: "发送失败")
            }
            json.optInt("count", count)
        }
    }

    data class SyncReply(val added: Int, val updated: Int, val count: Int, val zip: File)

    /**
     * 发起空间同步：上传本机快照 → 对方合并后回传它的快照 → 落到 [replyDir] 交给调用方合并。
     * [onProgress] 只反映上传阶段。
     */
    suspend fun sync(
        host: String,
        port: Int,
        token: String,
        zip: File,
        count: Int,
        spaceLabel: String,
        replyDir: File,
        onProgress: (Long) -> Unit,
    ): Result<SyncReply> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // 比服务端的确认超时再宽一点，避免两边同时放弃
            socket.soTimeout = (SYNC_TIMEOUT_MS + 30_000).toInt()
            val out = BufferedOutputStream(socket.getOutputStream())
            TransferWire.writeMessage(
                out,
                JSONObject()
                    .put("cmd", "sync")
                    .put("token", token)
                    .put("space", spaceLabel)
                    .put("count", count)
                    .put("size", zip.length()),
            )
            zip.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent)
                }
            }
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val line = TransferWire.readLine(input) ?: throw IOException("对方没有响应")
            val json = JSONObject(line)
            if (!json.optBoolean("ok")) {
                throw IOException(json.optStringOrNull("error") ?: "同步失败")
            }
            val size = json.optLong("size")
            if (size < 0 || size > TransferPayload.MAX_BYTES) throw IOException("回传包大小异常")
            replyDir.mkdirs()
            val file = File(replyDir, "sync-reply-${System.currentTimeMillis()}.zip")
            file.outputStream().buffered().use { TransferWire.copyExactly(input, it, size) }
            SyncReply(
                added = json.optInt("added"),
                updated = json.optInt("updated"),
                count = json.optInt("count"),
                zip = file,
            )
        }
    }
}
