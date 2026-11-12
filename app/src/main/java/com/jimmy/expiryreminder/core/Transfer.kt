package com.jimmy.expiryreminder.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 跨设备分享的载荷。
 *
 * 一个 zip 里装 `manifest.json` + `images/<id>.jpg`：
 * manifest 的 items 与 data.json 里的物品对象同构（图片 path 就是 zip 内的条目名），
 * 所以接收方可以直接复用 [ExpiryItem.fromJson]，不必再定义一套传输模型。
 */
object TransferPayload {

    const val APP = "JExpiryReminder"
    const val TYPE = "items"
    const val SCHEMA = 1

    /** 单次传输上限。图片已压到 ≤1280px / JPEG85，64MB 能装下几百张。 */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 发送方预览里最多列几件物品名称。 */
    const val PREVIEW_NAMES = 3

    data class Batch(val sender: String, val sentAt: String, val items: List<ExpiryItem>)

    /** 同名冲突：本地已有同名物品时，每件收到的物品怎么处理。 */
    enum class ImportAction { add, skip, overwrite }

    /** 单件物品的接收决定；[localId] 只在 [ImportAction.overwrite] 时用到。 */
    data class Decision(val action: ImportAction, val localId: String? = null)

    data class ImportResult(val added: Int, val overwritten: Int, val skipped: Int) {
        val total: Int get() = added + overwritten
    }

    /** 收到的一件物品与本地同名物品的对应关系。 */
    data class Conflict(
        val incomingId: String,
        val name: String,
        val localId: String,
        val localExpireAt: String,
    )

    /**
     * 按名称找同名物品（去首尾空格、忽略大小写），同名多条时优先取同空间的。
     * 返回 itemId 到本地物品的映射，供界面提示「将覆盖哪一条」。
     */
    fun findConflicts(store: ExpiryStore, items: List<ExpiryItem>, targetSpaceId: String): List<Conflict> {
        if (items.isEmpty()) return emptyList()
        val byName = store.items.groupBy { it.name.trim().lowercase() }
        return items.mapNotNull { incoming ->
            val key = incoming.name.trim().lowercase()
            if (key.isEmpty()) return@mapNotNull null
            val candidates = byName[key] ?: return@mapNotNull null
            val local = candidates.firstOrNull { it.spaceId == targetSpaceId } ?: candidates.first()
            Conflict(
                incomingId = incoming.id,
                name = incoming.name,
                localId = local.id,
                localExpireAt = local.expireAt,
            )
        }
    }

    fun outboxDir(context: Context): File =
        File(context.cacheDir, "transfer/out").apply { mkdirs() }

    fun inboxDir(context: Context): File =
        File(context.cacheDir, "transfer/in").apply { mkdirs() }

    /** 打包选中的物品（含图片）到临时 zip；图片文件缺失的条目跳过，不阻断整次发送。 */
    fun writeZip(context: Context, items: List<ExpiryItem>, senderName: String): File {
        val file = File(outboxDir(context), "out-${System.currentTimeMillis()}.zip")
        val manifest = JSONObject()
            .put("app", APP)
            .put("type", TYPE)
            .put("schema", SCHEMA)
            .put("sender", senderName)
            .put("sentAt", Dates.nowIsoTimestamp())
            .put("items", JSONArray(items.map { it.toJson() }))

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 同一路径只写一次（正常不会有重复引用，防御脏数据）
            val written = HashSet<String>()
            for (item in items) {
                for (image in item.images) {
                    val path = normalizeImagePath(image.path)
                    if (path.isEmpty() || !written.add(path)) continue
                    val src = ImageStore.imageFile(context, path)
                    if (!src.exists()) continue
                    // 图片本来就是压缩格式，STORED 省一次无意义的压缩
                    val entry = ZipEntry(path).apply {
                        method = ZipEntry.STORED
                        size = src.length()
                        crc = src.crc32()
                    }
                    zip.putNextEntry(entry)
                    src.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    /** 只读 zip 里的 manifest；格式不对（不是本应用的物品包）返回 null。 */
    fun readManifest(file: File): Batch? {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") {
                    val json = runCatching {
                        JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }.getOrNull() ?: return null
                    if (json.optString("app") != APP) return null
                    val arr = json.optJSONArray("items") ?: return null
                    val items = (0 until arr.length()).mapNotNull { ExpiryItem.fromJson(arr.opt(it)) }
                    return Batch(
                        sender = json.optStringOrNull("sender")?.takeIf { it.isNotBlank() } ?: "另一台设备",
                        sentAt = json.optStringOrNull("sentAt") ?: Dates.nowIsoTimestamp(),
                        items = items,
                    )
                }
                zip.closeEntry()
            }
        }
        return null
    }

    /** 待接收批次的预览文案：`牛奶、鸡蛋 等 5 件`。 */
    fun previewText(items: List<ExpiryItem>): String {
        if (items.isEmpty()) return "0 件物品"
        val head = items.take(PREVIEW_NAMES).joinToString("、") { it.name }
        return if (items.size > PREVIEW_NAMES) "$head 等 ${items.size} 件" else "$head（${items.size} 件）"
    }

    /**
     * 把收到的批次写进本地数据：
     * 物品换新 id、图片落到本地 `images/`、分类回落成接收方已有的分类、
     * 归属改为接收方选择的 [spaceId]。
     *
     * [decisions] 里没有条目的物品按「直接新增」处理；同名物品要跳过/覆盖由调用方决定。
     */
    suspend fun importBatch(
        context: Context,
        store: ExpiryStore,
        zip: File,
        batch: Batch,
        spaceId: String,
        decisions: Map<String, Decision> = emptyMap(),
    ): ImportResult {
        val extractDir = File(inboxDir(context), "extract-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val extracted = extractImages(zip, extractDir)
            return importItems(context, store, batch.items, extracted, spaceId, decisions)
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /** 把 zip 里的图片条目解到 [dir]，返回「zip 内路径 → 临时文件」。 */
    fun extractImages(zip: File, dir: File): Map<String, File> {
        val extracted = HashMap<String, File>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = normalizeImagePath(entry.name)
                    if (path.isNotEmpty()) {
                        // 同一个条目可能被多条物品引用，按路径缓存，避免重复读 zip
                        val out = File(dir, path.replace('/', '_'))
                        out.writeBytes(zis.readBytes())
                        extracted[path] = out
                    }
                }
                zis.closeEntry()
            }
        }
        return extracted
    }

    /**
     * 按决定把物品入库（接收与空间同步共用）。
     * 覆盖会保留本地物品的 id 与创建时间，旧图片文件由 [ExpiryStore.replaceItem] 清掉。
     */
    suspend fun importItems(
        context: Context,
        store: ExpiryStore,
        items: List<ExpiryItem>,
        extracted: Map<String, File>,
        spaceId: String,
        decisions: Map<String, Decision> = emptyMap(),
    ): ImportResult {
        val target = store.resolveSpaceId(spaceId)
        val now = Dates.nowIsoTimestamp()
        val additions = ArrayList<ExpiryItem>(items.size)
        var added = 0
        var overwritten = 0
        var skipped = 0

        items.forEachIndexed { itemIndex, item ->
            val decision = decisions[item.id] ?: Decision(ImportAction.add)
            val localId = decision.localId
            if (decision.action == ImportAction.skip ||
                (decision.action == ImportAction.overwrite && localId == null)
            ) {
                skipped += 1
                return@forEachIndexed
            }

            val images = item.images.mapNotNull { image ->
                val src = extracted[normalizeImagePath(image.path)] ?: return@mapNotNull null
                // id 里带序号：一次接收几十件时时间戳可能撞在同一毫秒
                val newId = "img_${uid()}_$itemIndex"
                val newPath = ImageStore.saveImageBytes(context, src.readBytes(), newId)
                ExpiryImage(id = newId, path = newPath, addedAt = now)
            }
            val base = item.copy(
                // 对方的分类/空间在本地不一定存在：分类降级成「其他」，空间用接收方选的
                category = if (store.categoryOf(item.category) == null) Categories.DEFAULT else item.category,
                spaceId = target,
                images = images.take(MAX_IMAGES),
                notifiedKeys = emptyList(),
                archived = false,
                updatedAt = now,
            )

            if (decision.action == ImportAction.overwrite && localId != null) {
                val created = store.getItem(localId)?.createdAt ?: now
                store.replaceItem(context, localId, base.copy(id = localId, createdAt = created))
                overwritten += 1
            } else {
                additions += base.copy(id = "${uid()}_$itemIndex", createdAt = now)
                added += 1
            }
        }

        if (additions.isNotEmpty()) store.addItems(context, additions)
        return ImportResult(added = added, overwritten = overwritten, skipped = skipped)
    }

    private fun File.crc32(): Long {
        val crc = java.util.zip.CRC32()
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }
}

/**
 * 空间同步：两台设备把各自空间里的物品合并成一份（双向）。
 *
 * 设备间的物品 id 互不相通，所以按「名称（去空格、忽略大小写）+ 到期日」做指纹匹配；
 * 两边都有同一件时按 `updatedAt` 较新的胜出（时间戳是本机本地时间，跨时区使用会有偏差，
 * 局域网同场景可接受）。
 */
object SpaceSync {

    /** 合并计划：[decisions] 直接喂给 [TransferPayload.importItems]。 */
    data class Plan(
        val decisions: Map<String, TransferPayload.Decision>,
        val added: Int,
        val updated: Int,
    )

    fun fingerprint(item: ExpiryItem): String =
        item.name.trim().lowercase() + "|" + item.expireAt

    /** 收到的 [incoming] 合并进本机 [spaceId] 时需要新增/覆盖哪些。 */
    fun plan(localItems: List<ExpiryItem>, incoming: List<ExpiryItem>, spaceId: String): Plan {
        val localByKey = HashMap<String, ExpiryItem>()
        for (item in localItems) {
            if (item.spaceId != spaceId) continue
            localByKey.putIfAbsent(fingerprint(item), item)
        }
        val decisions = LinkedHashMap<String, TransferPayload.Decision>()
        var added = 0
        var updated = 0
        for (item in incoming) {
            val local = localByKey[fingerprint(item)]
            when {
                local == null -> added += 1 // 没有条目 = 默认新增
                newer(item.updatedAt, local.updatedAt) -> {
                    decisions[item.id] = TransferPayload.Decision(
                        action = TransferPayload.ImportAction.overwrite,
                        localId = local.id,
                    )
                    updated += 1
                }
                else -> decisions[item.id] = TransferPayload.Decision(TransferPayload.ImportAction.skip)
            }
        }
        return Plan(decisions, added, updated)
    }

    /** 解包并应用合并计划，返回实际入库结果。 */
    suspend fun apply(
        context: Context,
        store: ExpiryStore,
        zip: File,
        items: List<ExpiryItem>,
        spaceId: String,
        plan: Plan,
    ): TransferPayload.ImportResult {
        val dir = File(TransferPayload.inboxDir(context), "sync-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            val extracted = TransferPayload.extractImages(zip, dir)
            return TransferPayload.importItems(
                context = context,
                store = store,
                items = items,
                extracted = extracted,
                spaceId = spaceId,
                decisions = plan.decisions,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** [a] 是否比 [b] 新；格式异常时退回字符串比较（同机同格式下等价）。 */
    private fun newer(a: String, b: String): Boolean {
        val ta = runCatching { java.time.LocalDateTime.parse(a) }.getOrNull()
        val tb = runCatching { java.time.LocalDateTime.parse(b) }.getOrNull()
        return if (ta != null && tb != null) ta.isAfter(tb) else a > b
    }
}
