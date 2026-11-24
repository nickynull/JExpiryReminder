package com.jimmy.expiryreminder.core

import com.jimmy.expiryreminder.core.ImageStore
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 分类改名的失败原因；成功时返回 null。 */
enum class CategoryNameError { empty, duplicate }

/** 空间改名的失败原因；成功时返回 null。 */
enum class SpaceNameError { empty, duplicate }

/**
 * 到期提醒数据仓库。
 * 单例 + Mutex 串行化落盘，UI 通过 [items] / [categories] StateFlow 订阅。
 */
class ExpiryStore private constructor() {

    data class State(
        val loaded: Boolean = false,
        val items: List<ExpiryItem> = emptyList(),
        val categories: List<CustomCategory> = emptyList(),
        val categoryColors: Map<String, String> = emptyMap(),
        /** 内置分类的改名覆盖（自定义分类的改名直接改 [categories] 里的条目）。 */
        val categoryLabels: Map<String, String> = emptyMap(),
        /** 分类展示顺序（只存 id），用户在分类管理里拖动排序后写入。 */
        val order: List<String> = emptyList(),
        /** 自定义空间（用户在空间管理里主动创建）。 */
        val spaces: List<SpaceInfo> = emptyList(),
        /** 「未分配」「全部空间」的颜色覆盖。 */
        val spaceColors: Map<String, String> = emptyMap(),
        /** 本机全局修改时钟（Lamport）：所有物品 rev 的水位。 */
        val revision: Long = 0,
        /** 各分类新增物品时预填的提前提醒天数（键 = 分类 id；没有键 = 无默认）。 */
        val categoryRemindDefaults: Map<String, List<Int>> = emptyMap(),
        /** 删除墓碑（键 = 物品 id）：删除动作要经同步传播到另一台设备。 */
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    val loaded: Boolean get() = _state.value.loaded
    val items: List<ExpiryItem> get() = _state.value.items
    val categories: List<CustomCategory> get() = _state.value.categories
    val revision: Long get() = _state.value.revision

    private val mutex = Mutex()

    /** 上一次落盘时每条记录的内容指纹（不含 rev）：用于在统一入口识别「真的改过」。 */
    private val lastHashes = HashMap<String, Int>()

    /** 内容指纹刻意排除 rev，避免「只改标记」被当成一次新修改。 */
    /** 内容指纹用于判断「本机有没有改过」；排除同步元字段，避免补发 syncId 造成 rev 震荡。 */
    private fun contentHash(item: ExpiryItem): Int =
        item.copy(rev = 0L, syncId = "").toJson().toString().hashCode()

    companion object {
        /** 墓碑留存上限：超过后从最旧的开始丢，防止极端情况下无限增长。 */

        @Volatile
        private var instance: ExpiryStore? = null

        fun get(): ExpiryStore = instance ?: synchronized(this) {
            instance ?: ExpiryStore().also { instance = it }
        }

        /** 通用合并：
         *  对象逐键递归，带 id 的数组按 id 覆盖，其余以新数据为准。 */
        fun mergeValue(base: Any?, incoming: Any?): Any? {
            if (base is JSONArray && incoming is JSONArray) {
                fun allWithId(arr: JSONArray): Boolean {
                    if (arr.length() == 0) return false
                    for (i in 0 until arr.length()) {
                        val e = arr.opt(i)
                        if (e !is JSONObject || !e.has("id") || e.opt("id") !is String) return false
                    }
                    return true
                }
                if (allWithId(base) && allWithId(incoming)) {
                    val map = LinkedHashMap<String, Any>()
                    for (i in 0 until base.length()) {
                        val e = base.opt(i) as JSONObject
                        map[e.getString("id")] = e
                    }
                    for (i in 0 until incoming.length()) {
                        val e = incoming.opt(i) as JSONObject
                        map[e.getString("id")] = e
                    }
                    return JSONArray(map.values.toList())
                }
                return incoming
            }
            if (base is JSONObject && incoming is JSONObject) {
                val merged = JSONObject()
                for (key in base.keys()) merged.put(key, base.opt(key))
                for (key in incoming.keys()) {
                    merged.put(key, mergeValue(base.opt(key), incoming.opt(key)))
                }
                return merged
            }
            return incoming
        }
    }

    // ---------------------------------------------------------------- 分类

    /** 内置 + 自定义，覆盖色在这里统一生效；顺序按用户拖动后的 [State.order]。 */
    fun categoryOptions(): List<CategoryDef> {
        val labels = _state.value.categoryLabels
        val all = Categories.BUILTIN.map { builtin ->
            CategoryDef(
                builtin.id,
                labels[builtin.id] ?: builtin.label,
                _state.value.categoryColors[builtin.id] ?: builtin.color,
            )
        } + _state.value.categories.map { CategoryDef(it.id, it.label, it.color) }

        val order = _state.value.order
        // sortedBy 是稳定排序：order 里没登记的（新加的分类/脏 id）保持原有相对顺序排在最后
        return all.orderedBy(order)
    }

    fun isBuiltinCategory(id: String): Boolean = Categories.BUILTIN.any { it.id == id }

    fun categoryOf(id: String): CategoryDef? = categoryOptions().firstOrNull { it.id == id }

    fun categoryLabelOf(id: String): String = categoryOf(id)?.label ?: id

    fun categoryColorOf(id: String): String = when {
        // 「全部分类」不是真分类，颜色走覆盖表；没配过就用默认灰
        id == Categories.ALL -> _state.value.categoryColors[id] ?: Categories.ALL_COLOR
        else -> categoryOf(id)?.color ?: Categories.ALL_COLOR
    }

    /**
     * 该分类在「新增物品」时预填的提前提醒天数；没配置过返回空列表（= 不预选任何档位）。
     * 只读取，不改写：旧数据没有这条记录时行为与历史版本一致。
     */
    fun defaultRemindDaysOf(categoryId: String): List<Int> =
        _state.value.categoryRemindDefaults[categoryId] ?: emptyList()

    /**
     * 设置分类的默认提前提醒天数；传空列表表示「无默认」，直接把记录删掉而不是存空数组。
     * 档位统一走 [normalizeRemindDays] 清洗（越界丢弃、去重、由远到近）。
     */
    suspend fun setCategoryRemindDefaults(
        context: Context,
        categoryId: String,
        days: List<Int>,
    ) {
        if (categoryOf(categoryId) == null) return
        val clean = normalizeRemindDays(days)
        mutex.withLock {
            val current = _state.value.categoryRemindDefaults
            val next = if (clean.isEmpty()) current - categoryId else current + (categoryId to clean)
            if (next == current) return
            _state.value = _state.value.copy(categoryRemindDefaults = next)
            persistLocked(context)
        }
    }

    // ---------------------------------------------------------------- 空间

    /** 自定义空间（按创建先后）。没有用户主动创建的空间时返回空列表。 */
    fun spaceOptions(): List<SpaceDef> {
        val colors = _state.value.spaceColors
        return _state.value.spaces.map { SpaceDef(it.id, it.label, colors[it.id] ?: it.color) }
    }

    /** 未分配空间（空串）：物品可以不属于任何空间。 */
    fun isUnassignedSpace(id: String?): Boolean = id.isNullOrEmpty()

    fun spaceOf(id: String): SpaceDef? = spaceOptions().firstOrNull { it.id == id }

    /**
     * 规整空间 id：空串保留（未分配是合法状态），指向不存在的空间（旧数据/被删空间/他人数据）
     * 也回落到「未分配」，不再有内置默认空间。
     */
    fun resolveSpaceId(id: String?): String = when {
        id.isNullOrEmpty() -> ""
        spaceOf(id) != null -> id
        else -> ""
    }

    fun spaceLabelOf(id: String): String = when {
        id == Spaces.ALL -> Spaces.ALL_LABEL
        isUnassignedSpace(id) -> Spaces.UNASSIGNED_LABEL
        else -> spaceOf(id)?.label ?: Spaces.UNASSIGNED_LABEL
    }

    fun spaceColorOf(id: String): String {
        val overrides = _state.value.spaceColors
        return when {
            id == Spaces.ALL -> overrides[Spaces.ALL] ?: Spaces.ALL_COLOR
            isUnassignedSpace(id) -> overrides[Spaces.UNASSIGNED_KEY] ?: Spaces.UNASSIGNED_COLOR
            else -> overrides[id] ?: spaceOf(id)?.color ?: "#409EFF"
        }
    }

    /** 该空间下未归档的物品（按到期日升序），首页列表用；[Spaces.ALL] = 所有空间 + 未分配。
     *  物品的 spaceId 指向未维护的空间（旧数据/被删空间）时视为「未分配」，不写死任何历史 id。 */
    fun itemsInSpace(spaceId: String): List<ExpiryItem> = when (spaceId) {
        Spaces.ALL -> activeItems()
        else -> items
            .filter { !it.archived && resolveSpaceId(it.spaceId) == spaceId }
            .sortedBy { it.expireAt }
    }

    /** 该空间下的物品总条数（含归档）：空间管理页的「占用」与删除空间的提示用。 */
    fun itemCountInSpace(spaceId: String): Int =
        if (spaceId == Spaces.ALL) items.size
        else items.count { resolveSpaceId(it.spaceId) == spaceId }

    /**
     * 该空间下「可操作」的物品条数（不含归档），与 [itemsInSpace] 严格同口径。
     * 主页空间下拉、跨设备传输的空间选择都要用这个——归档物品不出现在列表里，
     * 也不参与分享，数字必须与点进去后看到的一致。
     */
    fun activeCountInSpace(spaceId: String): Int =
        if (spaceId == Spaces.ALL) items.count { !it.archived }
        else items.count { !it.archived && resolveSpaceId(it.spaceId) == spaceId }

    // ---------------------------------------------------------------- 物品

    /** 未归档物品，按到期日期升序。 */
    fun activeItems(): List<ExpiryItem> =
        items.filter { !it.archived }.sortedBy { it.expireAt }

    /** 已归档物品，按到期日期升序。归档页列表用。 */
    fun archivedItems(): List<ExpiryItem> =
        items.filter { it.archived }.sortedBy { it.expireAt }

    /** 归档模式的空间筛选：与 [itemsInSpace] 同一套空间口径，「全部空间」= 所有归档物品。 */
    fun archivedItemsInSpace(spaceId: String): List<ExpiryItem> = when (spaceId) {
        Spaces.ALL -> archivedItems()
        else -> archivedItems().filter { resolveSpaceId(it.spaceId) == spaceId }
    }

    /** 空间下拉里的条数：归档模式按归档物品计数，与列表口径一致。 */
    fun archivedCountInSpace(spaceId: String): Int = when (spaceId) {
        Spaces.ALL -> items.count { it.archived }
        else -> items.count { it.archived && resolveSpaceId(it.spaceId) == spaceId }
    }

    fun statusOf(item: ExpiryItem): ExpiryStatus {
        val days = Dates.daysUntil(item.expireAt) ?: return ExpiryStatus.normal
        return when {
            days < 0 -> ExpiryStatus.expired
            days <= 7 -> ExpiryStatus.soon
            days <= 30 -> ExpiryStatus.month
            else -> ExpiryStatus.normal
        }
    }

    /** 30 天内（含已过期）待处理的条数。 */
    fun alertCount(): Int = activeItems().count { statusOf(it) != ExpiryStatus.normal }

    fun getItem(id: String): ExpiryItem? = items.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- load/save

    suspend fun load(context: Context) {
        mutex.withLock {
            if (_state.value.loaded) return
            val file = ImageStore.dataFile(context)
            // 主文件损坏（写一半、被外部改坏）时退回上一次成功写入的 .bak，并把它扶正
            var stored: JSONObject? = null
            for (candidate in listOf(file, AtomicFiles.backupOf(file))) {
                if (!candidate.exists()) continue
                val parsed = runCatching { JSONObject(candidate.readText()) }.getOrNull() ?: continue
                stored = parsed
                if (candidate != file) {
                    runCatching { candidate.copyTo(file, overwrite = true) }
                }
                break
            }
            val rawItems = stored?.optJSONArray("items")
            val normalized = if (rawItems != null) {
                (0 until rawItems.length()).mapNotNull { ExpiryItem.fromJson(rawItems.opt(it)) }
            } else emptyList()

            // v3 的内联 base64 图片迁移成文件，同时丢掉读不出内容的脏图片
            var migrated = false
            val migratedItems = normalized.map { item ->
                val kept = mutableListOf<ExpiryImage>()
                for (image in item.images) {
                    if (image.path.isNotEmpty()) {
                        kept.add(image)
                        continue
                    }
                    val data = image.data
                    if (data == null) {
                        migrated = true
                        continue
                    }
                    try {
                        val path = ImageStore.saveDataUrl(context, data, image.id)
                        kept.add(ExpiryImage(image.id, path, image.addedAt))
                        migrated = true
                    } catch (_: Exception) {
                        migrated = true
                    }
                }
                if (kept.size != item.images.size) migrated = true
                if (kept.size != item.images.size || kept != item.images) {
                    item.copy(images = kept)
                } else item
            }

            val rawCategories = stored?.optJSONArray("categories")
            val categories = if (rawCategories != null) {
                (0 until rawCategories.length()).mapNotNull { CustomCategory.fromJson(rawCategories.opt(it)) }
            } else emptyList()

            // 内置分类的颜色覆盖：只留内置 id（+「全部分类」）+ 合法色值
            val colors = LinkedHashMap<String, String>()
            val storedColors = stored?.optJSONObject("categoryColors")
            if (storedColors != null) {
                for (key in storedColors.keys()) {
                    val value = storedColors.optStringOrNull(key)
                    val known = isBuiltinCategory(key) || key == Categories.ALL
                    if (known && value != null && isHexColor(value)) {
                        colors[key] = value
                    }
                }
            }

            // 内置分类的改名覆盖：只留内置 id + 非空名称
            val labels = LinkedHashMap<String, String>()
            val storedLabels = stored?.optJSONObject("categoryLabels")
            if (storedLabels != null) {
                for (key in storedLabels.keys()) {
                    val value = storedLabels.optStringOrNull(key)?.trim()
                    if (isBuiltinCategory(key) && !value.isNullOrEmpty()) {
                        labels[key] = value
                    }
                }
            }

            // 分类顺序：只留已知分类 id，去重后保序（脏数据不会把顺序弄乱）
            val knownIds = Categories.BUILTIN.map { it.id }.toSet() + categories.map { it.id }
            val order = LinkedHashSet<String>()
            stored?.optJSONArray("categoryOrder")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.opt(i) as? String ?: continue
                    if (id in knownIds) order.add(id)
                }
            }

            // 分类默认提前提醒天数：只留已知分类 id + 合法档位；脏键、空档位直接丢掉，不写回
            val remindDefaults = LinkedHashMap<String, List<Int>>()
            stored?.optJSONObject("categoryRemindDefaults")?.let { obj ->
                for (key in obj.keys()) {
                    if (key !in knownIds) continue
                    val arr = obj.optJSONArray(key) ?: continue
                    val days = (0 until arr.length()).mapNotNull { i ->
                        when (val v = arr.opt(i)) {
                            is Number -> v.toInt()
                            is String -> v.toIntOrNull()
                            else -> null
                        }
                    }
                    val clean = normalizeRemindDays(days)
                    if (clean.isNotEmpty()) remindDefaults[key] = clean
                }
            }

            // 空间：自定义项（id 不能与内置默认空间撞车，重复 id 只留第一条）
            val spaces = LinkedHashMap<String, SpaceInfo>()
            stored?.optJSONArray("spaces")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val space = SpaceInfo.fromJson(arr.opt(i)) ?: continue
                    spaces.putIfAbsent(space.id, space)
                }
            }

            // 空间颜色覆盖：「未分配」「全部空间」的颜色（自定义空间的颜色直接写在 spaces 里）
            val spaceColors = LinkedHashMap<String, String>()
            stored?.optJSONObject("spaceColors")?.let { colorJson ->
                for (key in colorJson.keys()) {
                    val value = colorJson.optStringOrNull(key)
                    val known = key == Spaces.ALL || key == Spaces.UNASSIGNED_KEY
                    if (known && value != null && isHexColor(value)) spaceColors[key] = value
                }
            }

            _state.value = State(
                loaded = true,
                items = migratedItems,
                categories = categories,
                categoryColors = colors,
                categoryLabels = labels,
                order = order.toList(),
                spaces = spaces.values.toList(),
                spaceColors = spaceColors,
                revision = stored?.optLong("revision", 0L) ?: 0L,
                categoryRemindDefaults = remindDefaults,
            )
            // 以当前内容作为「已落盘」基线，首次写入不会把全部老数据误判成新修改
            lastHashes.clear()
            for (item in migratedItems) lastHashes[item.id] = contentHash(item)

            // 兼容策略：不改写数据。物品 spaceId 指向未维护的空间（旧数据/被删空间）时，
            // 由 itemsInSpace / itemCountInSpace 在查询时用 resolveSpaceId 归一为「未分配」

            // v3 图片迁移改动了数据才落盘（既有逻辑，仅转换存储形式，不删数据）
            if (migrated) persistLocked(context)
            // 旧版本 data.json 没有 syncId：立刻按 id 派生补发（首次落盘时自然写入）
            else if (migratedItems.any { it.syncId.isBlank() }) {
                persistLocked(context)
            }
        }
    }

    suspend fun persist(context: Context) {
        mutex.withLock { persistLocked(context) }
    }

    /**
     * 统一落盘入口，也是修改标记的唯一分配点：
     * 本机写操作（新增/编辑/导入/批量改…）→ 内容确实变了的记录 rev = ++时钟。
     */
    private suspend fun persistLocked(context: Context) {
        val current = _state.value
        var clock = current.revision
        val hashes = HashMap<String, Int>(current.items.size)
        val stamped = current.items.map { raw ->
            // 旧数据没有共同唯一 ID：按本地 id 稳定派生，不随机、不产生跨设备漂移
            val item = if (raw.syncId.isBlank()) raw.copy(syncId = "s_" + raw.id) else raw
            val hash = contentHash(item)
            hashes[item.id] = hash
            val changed = lastHashes[item.id] != hash
            if (changed || item.rev <= 0L) {
                clock += 1
                item.copy(rev = clock)
            } else item
        }
        lastHashes.clear()
        lastHashes.putAll(hashes)
        val next = current.copy(items = stamped, revision = clock)
        _state.value = next

        val file = ImageStore.dataFile(context)
        file.parentFile?.mkdirs()
        val payload = ExpiryData(
            version = DATA_VERSION,
            items = next.items,
            categories = next.categories,
            categoryColors = next.categoryColors,
            categoryLabels = next.categoryLabels,
            categoryOrder = categoryOptions().map { it.id },
            spaces = next.spaces,
            spaceColors = next.spaceColors,
            revision = next.revision,
            categoryRemindDefaults = next.categoryRemindDefaults,
        )
        AtomicFiles.writeText(file, payload.toJson().toString(2))
    }

    /** 供导入后整体重载。 */
    suspend fun reload(context: Context) {
        mutex.withLock { _state.value = State() }
        load(context)
    }

    // ---------------------------------------------------------------- items

    suspend fun createItem(context: Context, draft: ExpiryItem) {
        val now = Dates.nowIsoTimestamp()
        val item = draft.copy(
            id = uid(),
            syncId = "s_" + uid(),
            notifiedKeys = emptyList(),
            archived = false,
            createdAt = now,
            updatedAt = now,
        )
        mutex.withLock {
            _state.value = _state.value.copy(items = _state.value.items + item)
            persistLocked(context)
        }
    }

    /**
     * 循环物品「完成本次」：以实际完成日为新起点推进周期，并清空本周期提醒记录。
     * 返回新的到期日；非循环物品、周期不合法或 id 不存在时返回 null（不写盘）。
     */
    suspend fun completeCycle(
        context: Context,
        id: String,
        today: java.time.LocalDate = java.time.LocalDate.now(),
    ): String? {
        var nextExpire: String? = null
        mutex.withLock {
            val list = _state.value.items.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            val current = list[index]
            if (!current.repeatEnabled || current.shelfLife <= 0) return@withLock
            val advanced = ExpiryCalc.advanceCycle(
                current, today, Dates.nowIsoTimestamp(),
            )
            // 新周期到期日 = 完成日 + 周期，必然晚于完成日，正常情况下一定变化。
            // 只有同一天重复点「完成本次」时两个字段都不变，视为无变化直接返回。
            if (advanced.expireAt == current.expireAt &&
                advanced.productionDate == current.productionDate
            ) {
                return@withLock
            }
            nextExpire = advanced.expireAt
            list[index] = advanced
            _state.value = _state.value.copy(items = list)
            persistLocked(context)
        }
        return nextExpire
    }

    suspend fun updateItem(context: Context, id: String, patch: ExpiryItem) {
        mutex.withLock {
            val list = _state.value.items.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = list[index]
            val expireChanged = patch.expireAt != current.expireAt
            // 日期变了就重置提醒记录，让新周期的提醒能正常触发
            list[index] = patch.copy(
                id = id,
                updatedAt = Dates.nowIsoTimestamp(),
                notifiedKeys = if (expireChanged) emptyList() else current.notifiedKeys,
            )
            _state.value = _state.value.copy(items = list)
            persistLocked(context)
        }
    }

    /**
     * 整体替换已有物品（跨设备「覆盖同名」用）：保留 id 与创建时间，其余字段全用 [patch]，
     * 并删除被替换掉的旧图片文件。物品不存在时原样新增。
     */
    suspend fun replaceItem(context: Context, id: String, patch: ExpiryItem) {
        var stalePaths: List<String> = emptyList()
        var missing = false
        mutex.withLock {
            val list = _state.value.items.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) {
                missing = true
                return@withLock
            }
            val current = list[index]
            val keep = patch.images.map { it.path }.toSet()
            stalePaths = current.images.map { it.path }.filterNot { it in keep }
            list[index] = patch.copy(
                id = id,
                createdAt = current.createdAt,
                updatedAt = Dates.nowIsoTimestamp(),
            )
            _state.value = _state.value.copy(items = list)
            persistLocked(context)
        }
        if (missing) {
            createItem(context, patch)
            return
        }
        if (stalePaths.isNotEmpty()) ImageStore.removeImages(context, stalePaths)
    }

    suspend fun removeItem(context: Context, id: String) {
        var removed: ExpiryItem? = null
        mutex.withLock {
            removed = _state.value.items.firstOrNull { it.id == id }
            _state.value = _state.value.copy(items = _state.value.items.filter { it.id != id })
            persistLocked(context)
        }
        removed?.let { ImageStore.removeImages(context, it.images.map { img -> img.path }) }
    }

    /** 批量删除：只写一次存储，避免逐条删除时反复落盘。 */
    suspend fun removeItems(context: Context, ids: List<String>): Int {
        val drop = ids.toSet()
        if (drop.isEmpty()) return 0
        var removedItems: List<ExpiryItem> = emptyList()
        mutex.withLock {
            removedItems = _state.value.items.filter { it.id in drop }
            _state.value = _state.value.copy(items = _state.value.items.filterNot { it.id in drop })
            persistLocked(context)
        }
        if (removedItems.isNotEmpty()) {
            ImageStore.removeImages(context, removedItems.flatMap { it.images.map { img -> img.path } })
        }
        return removedItems.size
    }

    /**
     * 批量改归属空间：多选态下整体切换空间用。
     * [toSpaceId] 为空串表示移到「未分配」；返回真正发生变化的条数。
     */
    suspend fun moveItemsToSpace(context: Context, ids: Collection<String>, toSpaceId: String): Int {
        if (ids.isEmpty()) return 0
        val target = resolveSpaceId(toSpaceId)
        val pick = ids.toSet()
        var moved = 0
        mutex.withLock {
            val now = Dates.nowIsoTimestamp()
            val list = _state.value.items.map { item ->
                if (item.id !in pick || item.spaceId == target) item
                else {
                    moved += 1
                    item.copy(spaceId = target, updatedAt = now)
                }
            }
            if (moved > 0) {
                _state.value = _state.value.copy(items = list)
                persistLocked(context)
            }
        }
        return moved
    }

    /**
     * 批量改分类：多选态下整体切换分类用。
     * 分类不存在（被删/脏 id）时降级到默认分类，避免物品在分类筛选里消失。
     */
    suspend fun setItemsCategory(context: Context, ids: Collection<String>, categoryId: String): Int {
        if (ids.isEmpty()) return 0
        val target = categoryId.takeIf { categoryOf(it) != null } ?: Categories.DEFAULT
        val pick = ids.toSet()
        var changed = 0
        mutex.withLock {
            val now = Dates.nowIsoTimestamp()
            val list = _state.value.items.map { item ->
                if (item.id !in pick || item.category == target) item
                else {
                    changed += 1
                    item.copy(category = target, updatedAt = now)
                }
            }
            if (changed > 0) {
                _state.value = _state.value.copy(items = list)
                persistLocked(context)
            }
        }
        return changed
    }

    /**
     * 批量归档 / 调回：只改 [ExpiryItem.archived] 标记，不动图片与提醒记录。
     * 归档变化会刷新 updatedAt（是一次真实的本机修改），经统一入口分配 rev。
     * 返回真正发生变化的条数。
     */
    suspend fun setItemsArchived(
        context: Context,
        ids: Collection<String>,
        archived: Boolean,
    ): Int {
        if (ids.isEmpty()) return 0
        val pick = ids.toSet()
        var changed = 0
        mutex.withLock {
            val now = Dates.nowIsoTimestamp()
            val list = _state.value.items.map { item ->
                if (item.id !in pick || item.archived == archived) item
                else {
                    changed += 1
                    item.copy(archived = archived, updatedAt = now)
                }
            }
            if (changed > 0) {
                _state.value = _state.value.copy(items = list)
                persistLocked(context)
            }
        }
        return changed
    }

    suspend fun clearItems(context: Context) {
        val paths = items.flatMap { it.images.map { img -> img.path } }
        mutex.withLock {
            _state.value = _state.value.copy(items = emptyList())
            persistLocked(context)
        }
        ImageStore.removeImages(context, paths)
    }

    /**
     * 批量入库：跨设备接收时用。
     * id 与图片路径由调用方重写好（避免与本地数据撞车），这里只负责追加 + 落盘。
     */
    suspend fun addItems(context: Context, additions: List<ExpiryItem>): Int {
        if (additions.isEmpty()) return 0
        mutex.withLock {
            _state.value = _state.value.copy(items = _state.value.items + additions)
            persistLocked(context)
        }
        return additions.size
    }

    // ------------------------------------------------------------ categories

    /** 新增自定义分类；重名（含内置）返回 null。 */
    suspend fun addCategory(context: Context, label: String): CustomCategory? {
        val name = label.trim()
        if (name.isEmpty()) return null
        if (categoryOptions().hasNameConflict(name)) return null
        val used = categoryOptions().map { it.color }.toSet()
        val color = Categories.CUSTOM_COLORS.firstOrNull { it !in used }
            ?: Categories.CUSTOM_COLORS[_state.value.categories.size % Categories.CUSTOM_COLORS.size]
        val category = CustomCategory(
            id = "c_${uid()}",
            label = name,
            color = color,
            createdAt = Dates.nowIsoTimestamp(),
        )
        mutex.withLock {
            _state.value = _state.value.copy(categories = _state.value.categories + category)
            persistLocked(context)
        }
        return category
    }

    /** 删除自定义分类；内置分类、或分类下还挂着物品（含已归档）时拒绝，返回 false。 */
    suspend fun removeCategory(context: Context, id: String): Boolean {
        if (isBuiltinCategory(id)) return false
        var ok = false
        mutex.withLock {
            // 锁内二次校验，避免 UI 判断与落盘之间被并发改动
            if (_state.value.items.any { it.category == id }) return false
            val list = _state.value.categories.filter { it.id != id }
            ok = list.size != _state.value.categories.size
            if (ok) {
                // 分类没了，它的默认提醒天数也一并清掉，避免留脏记录
                _state.value = _state.value.copy(
                    categories = list,
                    categoryRemindDefaults = _state.value.categoryRemindDefaults - id,
                )
                persistLocked(context)
            }
        }
        return ok
    }

    /** 修改分类颜色。自定义分类直接改自身，内置分类与「全部分类」记进覆盖表。 */
    suspend fun setCategoryColor(context: Context, id: String, color: String) {
        if (!isHexColor(color)) return
        mutex.withLock {
            val current = _state.value
            val catIndex = current.categories.indexOfFirst { it.id == id }
            if (catIndex >= 0) {
                val old = current.categories[catIndex]
                if (old.color == color) return
                _state.value = current.copy(
                    categories = current.categories.toMutableList().also { it[catIndex] = old.copy(color = color) }
                )
            } else if (isBuiltinCategory(id) || id == Categories.ALL) {
                // 与「全部空间」同款：只影响界面显示，保持默认色时不写冗余记录
                if (current.categoryColors[id] == color) return
                val next = if (color == Categories.ALL_COLOR && id == Categories.ALL) {
                    current.categoryColors - id
                } else {
                    current.categoryColors + (id to color)
                }
                _state.value = current.copy(categoryColors = next)
            } else return
            persistLocked(context)
        }
    }

    /** 内置分类的默认名称；自定义分类没有「默认名称」这一说，返回 null。 */
    fun defaultLabelOf(id: String): String? =
        if (isBuiltinCategory(id)) Categories.BUILTIN.firstOrNull { it.id == id }?.label else null

    /**
     * 把内置分类的名称恢复为默认值（清掉改名覆盖）。
     * 默认名已被别的分类占用时（用户可能新建了同名自定义分类）返回 duplicate，不做改动。
     */
    suspend fun resetCategoryLabel(context: Context, id: String): CategoryNameError? {
        val default = defaultLabelOf(id) ?: return null
        mutex.withLock {
            val current = _state.value
            if (!current.categoryLabels.containsKey(id)) return null
            if (categoryOptions().hasNameConflict(default, exceptId = id)) {
                return CategoryNameError.duplicate
            }
            _state.value = current.copy(categoryLabels = current.categoryLabels - id)
            persistLocked(context)
        }
        return null
    }

    /**
     * 分类改名：自定义分类直接改自身，内置分类记进覆盖表。
     * 名称去空格后不能为空、也不能与其它分类重名（忽略大小写）；失败时返回原因且不落盘。
     */
    suspend fun renameCategory(context: Context, id: String, label: String): CategoryNameError? {
        val name = label.trim()
        if (name.isEmpty()) return CategoryNameError.empty
        mutex.withLock {
            val current = _state.value
            if (categoryOptions().hasNameConflict(name, exceptId = id)) {
                return CategoryNameError.duplicate
            }
            val catIndex = current.categories.indexOfFirst { it.id == id }
            if (catIndex >= 0) {
                val old = current.categories[catIndex]
                if (old.label == name) return null
                _state.value = current.copy(
                    categories = current.categories.toMutableList()
                        .also { it[catIndex] = old.copy(label = name) },
                )
            } else if (isBuiltinCategory(id)) {
                // 改回内置原名就把覆盖删掉，别留冗余记录
                val default = Categories.BUILTIN.firstOrNull { it.id == id }?.label
                val next = if (name == default) current.categoryLabels - id
                else current.categoryLabels + (id to name)
                if (next == current.categoryLabels) return null
                _state.value = current.copy(categoryLabels = next)
            } else return null
            persistLocked(context)
        }
        return null
    }

    /**
     * 拖动排序：传入完整的分类 id 顺序。
     * 未知 id 丢掉、漏掉的分类按现有顺序补在后面，所以调用方只给「看得见的那些」也不会丢分类。
     */
    suspend fun reorderCategories(context: Context, ids: List<String>) {
        mutex.withLock {
            val known = categoryOptions().map { it.id }
            val kept = ids.filter { it in known }.distinct()
            val merged = kept + known.filter { it !in kept }
            if (merged == _state.value.order) return
            _state.value = _state.value.copy(order = merged)
            persistLocked(context)
        }
    }

    // ------------------------------------------------------------ spaces

    /** 新增自定义空间；空名/重名返回 null。 */
    suspend fun addSpace(context: Context, label: String): SpaceInfo? {
        val name = label.trim()
        if (name.isEmpty()) return null
        if (spaceOptions().hasSpaceNameConflict(name)) return null
        val used = spaceOptions().map { it.color }.toSet()
        val color = Spaces.CUSTOM_COLORS.firstOrNull { it !in used }
            ?: Spaces.CUSTOM_COLORS[_state.value.spaces.size % Spaces.CUSTOM_COLORS.size]
        val space = SpaceInfo(
            id = "sp_${uid()}",
            label = name,
            color = color,
            createdAt = Dates.nowIsoTimestamp(),
        )
        mutex.withLock {
            _state.value = _state.value.copy(spaces = _state.value.spaces + space)
            persistLocked(context)
        }
        return space
    }

    /** 空间改名：改自身。 */
    suspend fun renameSpace(context: Context, id: String, label: String): SpaceNameError? {
        val name = label.trim()
        if (name.isEmpty()) return SpaceNameError.empty
        mutex.withLock {
            val current = _state.value
            if (spaceOptions().hasSpaceNameConflict(name, exceptId = id)) {
                return SpaceNameError.duplicate
            }
            val index = current.spaces.indexOfFirst { it.id == id }
            if (index < 0) return null
            val old = current.spaces[index]
            if (old.label == name) return null
            _state.value = current.copy(
                spaces = current.spaces.toMutableList().also { it[index] = old.copy(label = name) },
            )
            persistLocked(context)
        }
        return null
    }

    /**
     * 换色：「未分配」「全部空间」记进颜色覆盖表，自定义空间改自身颜色。
     * [id] 传空串表示「未分配」。
     */
    suspend fun setSpaceColor(context: Context, id: String, color: String) {
        if (!isHexColor(color)) return
        val key = Spaces.colorKeyOf(id)
        mutex.withLock {
            val current = _state.value
            val index = current.spaces.indexOfFirst { it.id == id }
            if (index >= 0) {
                val old = current.spaces[index]
                if (old.color == color) return
                _state.value = current.copy(
                    spaces = current.spaces.toMutableList().also { it[index] = old.copy(color = color) },
                )
                persistLocked(context)
                return
            }
            val overrideable = key == Spaces.ALL || key == Spaces.UNASSIGNED_KEY
            if (!overrideable) return
            if (current.spaceColors[key] == color) return
            _state.value = current.copy(spaceColors = current.spaceColors + (key to color))
            persistLocked(context)
        }
    }

    /**
     * 拖动排序：传入完整的空间 id 顺序。
     * 未知 id 丢掉、漏掉的空间按现有顺序补在后面，所以调用方只给「看得见的那些」也不会丢空间。
     * 空间顺序就是 [State.spaces] 的列表顺序（落盘即按此顺序写入），不额外存一份 order 表。
     */
    suspend fun reorderSpaces(context: Context, ids: List<String>) {
        mutex.withLock {
            val current = _state.value
            val known = current.spaces.map { it.id }
            val kept = ids.filter { it in known }.distinct()
            val merged = kept + known.filter { it !in kept }
            if (merged == known) return
            val byId = current.spaces.associateBy { it.id }
            _state.value = current.copy(spaces = merged.mapNotNull { byId[it] })
            persistLocked(context)
        }
    }

    /**
     * 删除空间：空间本身移除，空间下的物品一律不删——只把它们的 spaceId 清空，
     * 变成「未分配」（含已归档物品，避免盘里留下指向已删空间的死 id）。
     * 返回被改成「未分配」的物品数；空间不存在时返回 null。
     */
    suspend fun removeSpace(context: Context, id: String): Int? = mutex.withLock {
        val current = _state.value
        val list = current.spaces.filter { it.id != id }
        if (list.size == current.spaces.size) return@withLock null
        var moved = 0
        val items = current.items.map { item ->
            if (item.spaceId == id) {
                moved++
                item.copy(spaceId = "")
            } else {
                item
            }
        }
        _state.value = current.copy(spaces = list, items = items)
        persistLocked(context)
        moved
    }

    // ------------------------------------------------------------- reminders
    /** 提醒去重标记：同一天同一档位只提醒一次。 */
    fun reminderKey(item: ExpiryItem, days: Int, date: String): String = "$date:${item.id}:$days"

    suspend fun markNotified(context: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        val set = keys.toSet()
        var changed = false
        mutex.withLock {
            val list = _state.value.items.toMutableList()
            for (i in list.indices) {
                val item = list[i]
                val mine = set.filter { key ->
                    key.contains(":${item.id}:") && key !in item.notifiedKeys
                }
                if (mine.isNotEmpty()) {
                    list[i] = item.copy(notifiedKeys = item.notifiedKeys + mine)
                    changed = true
                }
            }
            if (changed) {
                _state.value = _state.value.copy(items = list)
                persistLocked(context)
            }
        }
    }

    // ---------------------------------------------------------------- merge

    /** 把合并后的 data.json 写回磁盘（导入合并模式用）。 */
    suspend fun applyMergedDataJson(context: Context, mergedJson: String) {
        AtomicFiles.writeText(ImageStore.dataFile(context), mergedJson)
        reload(context)
    }
}
