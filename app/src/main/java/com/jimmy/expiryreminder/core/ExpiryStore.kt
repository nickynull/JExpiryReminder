package com.jimmy.expiryreminder.core

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    val loaded: Boolean get() = _state.value.loaded
    val items: List<ExpiryItem> get() = _state.value.items
    val categories: List<CustomCategory> get() = _state.value.categories

    private val mutex = Mutex()

    companion object {
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

    fun categoryColorOf(id: String): String = categoryOf(id)?.color ?: "#909399"

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

    fun itemCountInSpace(spaceId: String): Int =
        if (spaceId == Spaces.ALL) items.size
        else items.count { resolveSpaceId(it.spaceId) == spaceId }

    // ---------------------------------------------------------------- 物品

    /** 未归档物品，按到期日期升序。 */
    fun activeItems(): List<ExpiryItem> =
        items.filter { !it.archived }.sortedBy { it.expireAt }

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
            var stored: JSONObject? = null
            if (file.exists()) {
                try {
                    stored = JSONObject(file.readText())
                } catch (_: Exception) {
                    stored = null
                }
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

            // 内置分类的颜色覆盖：只留内置 id + 合法色值
            val colors = LinkedHashMap<String, String>()
            val storedColors = stored?.optJSONObject("categoryColors")
            if (storedColors != null) {
                for (key in storedColors.keys()) {
                    val value = storedColors.optStringOrNull(key)
                    if (isBuiltinCategory(key) && value != null && isHexColor(value)) {
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
            val order = LinkedHashSet<String>()
            stored?.optJSONArray("categoryOrder")?.let { arr ->
                val known = Categories.BUILTIN.map { it.id }.toSet() + categories.map { it.id }
                for (i in 0 until arr.length()) {
                    val id = arr.opt(i) as? String ?: continue
                    if (id in known) order.add(id)
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
            )

            // 兼容策略：不改写数据。物品 spaceId 指向未维护的空间（旧数据/被删空间）时，
            // 由 itemsInSpace / itemCountInSpace 在查询时用 resolveSpaceId 归一为「未分配」

            // v3 图片迁移改动了数据才落盘（既有逻辑，仅转换存储形式，不删数据）
            if (migrated) persistLocked(context)
        }
    }

    suspend fun persist(context: Context) {
        mutex.withLock { persistLocked(context) }
    }

    private suspend fun persistLocked(context: Context) {
        val file = ImageStore.dataFile(context)
        file.parentFile?.mkdirs()
        val payload = ExpiryData(
            version = DATA_VERSION,
            items = _state.value.items,
            categories = _state.value.categories,
            categoryColors = _state.value.categoryColors,
            categoryLabels = _state.value.categoryLabels,
            categoryOrder = categoryOptions().map { it.id },
            spaces = _state.value.spaces,
            spaceColors = _state.value.spaceColors,
        )
        file.writeText(payload.toJson().toString(2))
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
                _state.value = _state.value.copy(categories = list)
                persistLocked(context)
            }
        }
        return ok
    }

    /** 修改分类颜色。自定义分类直接改自身，内置分类记进覆盖表。 */
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
            } else if (isBuiltinCategory(id)) {
                if (current.categoryColors[id] == color) return
                _state.value = current.copy(
                    categoryColors = current.categoryColors + (id to color)
                )
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
     * 删除空间：空间下还有物品（含已归档）时拒绝，返回 false。
     * 物品不会因为删空间被连带删除——必须先转移或删除，避免误伤数据。
     */
    suspend fun removeSpace(context: Context, id: String): Boolean {
        var ok = false
        mutex.withLock {
            if (_state.value.items.any { it.spaceId == id }) return false
            val list = _state.value.spaces.filter { it.id != id }
            ok = list.size != _state.value.spaces.size
            if (ok) {
                _state.value = _state.value.copy(spaces = list)
                persistLocked(context)
            }
        }
        return ok
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
        ImageStore.dataFile(context).writeText(mergedJson)
        reload(context)
    }
}
