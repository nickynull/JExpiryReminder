package com.jimmy.expiryreminder.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.floor

/**
 * 数据模型：对应 data.json v5 逐字段定义。
 *
 * 解析全部走容错模式（脏字段丢弃 / 补默认值）。
 */

// ---------------------------------------------------------------- 通用工具

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

fun isHexColor(value: Any?): Boolean = value is String && HEX_COLOR.matches(value)

/** 容忍省略 `#` 与 3 位简写；非法返回 null。 */
fun normalizeHexColor(input: String): String? {
    val text = input.trim().removePrefix("#")
    return when {
        Regex("^[0-9a-fA-F]{3}$").matches(text) ->
            "#" + text.map { "$it$it" }.joinToString("").uppercase()
        Regex("^[0-9a-fA-F]{6}$").matches(text) -> "#${text.uppercase()}"
        else -> null
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) {
        val v = opt(key)
        when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    } else null

/** 生成短随机 id（类似 TS 侧的 uid()）。 */
fun uid(): String {
    val ts = Instant.now().toEpochMilli().toString(36)
    val rand = (System.nanoTime() % 1679616L).toString(36)
    return ts + rand.padStart(4, '0')
}

// ---------------------------------------------------------------- 分类

/** 内置分类：id 写进数据不可改，界面文案走 label。 */
data class CategoryDef(val id: String, val label: String, val color: String)

/**
 * 按 [order] 里登记的先后重排分类。
 * order 里没登记的（新加的分类、脏 id）保持原有相对顺序排在最后，所以加分类不会打乱已有排序。
 */
fun List<CategoryDef>.orderedBy(order: List<String>): List<CategoryDef> {
    if (order.isEmpty()) return this
    val rank = order.withIndex().associate { (index, id) -> id to index }
    return sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

/**
 * 名称是否与已有分类重名：去首尾空格、忽略大小写比较。
 * [exceptId] 用于改名——自己跟自己不算重名。
 */
fun List<CategoryDef>.hasNameConflict(name: String, exceptId: String? = null): Boolean {
    val target = name.trim()
    if (target.isEmpty()) return false
    // 「全部分类」是分类管理里的保留行，被自定义分类占用会让人分不清哪个是真的筛选项
    if (target.equals(Categories.ALL_LABEL, ignoreCase = true)) return true
    return any { it.id != exceptId && it.label.trim().equals(target, ignoreCase = true) }
}

object Categories {
    /**
     * 「全部分类」是筛选用的虚拟 id（不对应任何真实分类，物品的 category 永远不会等于它）：
     * 首页默认停在这里，看到的是所有分类的物品。颜色可改，覆盖记录存在 categoryColors 里。
     */
    const val ALL = "all"
    const val ALL_LABEL = "全部分类"
    /** 未配置覆盖时的默认色，与兜底分类「其他」同色。 */
    const val ALL_COLOR = "#909399"

    val BUILTIN = listOf(
        CategoryDef("food", "食品", "#E6A23C"),
        CategoryDef("medicine", "药品", "#F56C6C"),
        CategoryDef("cosmetic", "化妆品", "#D4537E"),
        CategoryDef("document", "证件", "#409EFF"),
        CategoryDef("other", "其他", "#909399"),
    )
    /** 数据里缺 category 字段时的兜底分类（与 TS 侧一致，勿改）。 */
    const val DEFAULT = "other"

    /** 新增物品时的默认分类：食品。 */
    const val NEW_ITEM_DEFAULT = "food"

    /** 自定义分类取色顺序。 */
    val CUSTOM_COLORS = listOf(
        "#67C23A", "#9254DE", "#13C2C2", "#EB2F96",
        "#FA8C16", "#2F54EB", "#12B76A", "#F759AB",
    )

    /** 分类取色板：分类管理里换颜色用。 */
    val PALETTE = listOf(
        "#E6A23C", "#FA8C16", "#F56C6C", "#D4537E",
        "#EB2F96", "#F759AB", "#9254DE", "#2F54EB",
        "#409EFF", "#13C2C2", "#67C23A", "#12B76A",
    )
}

// ---------------------------------------------------------------- 空间

/**
 * 空间：物品的归属容器（我的空间 / 我的房间 / 公司…）。
 *
 * 与「分类」是两个正交维度：分类描述物品是什么，空间描述物品归谁管。
 * 每个物品属于且仅属于一个空间；跨设备分享时接收方指定落到哪个空间。
 */
data class SpaceDef(val id: String, val label: String, val color: String)

object Spaces {
    /** 空串 spaceId = 物品不归属任何空间，界面上显示为「未分配」。默认状态就是它，只有用户主动维护空间才会分配。 */
    const val UNASSIGNED_LABEL = "未分配"
    const val UNASSIGNED_COLOR = "#909399"
    /** 「未分配」的颜色覆盖在 data.json 里的键（空串当 JSON 键可读性太差）。 */
    const val UNASSIGNED_KEY = "__unassigned__"

    /**
     * 「全部空间」是筛选用的虚拟 id（不落盘、不对应任何真实空间）：
     * 首页默认停在这里，看到的是所有空间 + 未分配的全部物品。
     */
    const val ALL = "__all__"
    const val ALL_LABEL = "全部空间"
    const val ALL_COLOR = "#409EFF"

    /** 颜色覆盖表用的键：未分配用 [UNASSIGNED_KEY]，其余（含「全部空间」）直接用 id。 */
    fun colorKeyOf(id: String): String = id.ifEmpty { UNASSIGNED_KEY }

    /** 自定义空间取色顺序。 */
    val CUSTOM_COLORS = listOf(
        "#67C23A", "#9254DE", "#13C2C2", "#EB2F96",
        "#FA8C16", "#2F54EB", "#12B76A", "#F759AB",
    )

    val PALETTE = Categories.PALETTE
}

/** 空间重名判断：去首尾空格、忽略大小写；[exceptId] 用于改名时排除自己。 */
fun List<SpaceDef>.hasSpaceNameConflict(name: String, exceptId: String? = null): Boolean {
    val target = name.trim()
    if (target.isEmpty()) return false
    // 「未分配」「全部空间」都是保留字，被占用会让用户分不清「真的空间」和「筛选/没归属」
    if (target.equals(Spaces.UNASSIGNED_LABEL, ignoreCase = true)) return true
    if (target.equals(Spaces.ALL_LABEL, ignoreCase = true)) return true
    return any { it.id != exceptId && it.label.trim().equals(target, ignoreCase = true) }
}

/** 自定义空间，持久化在 data.json 的 spaces 里。 */
data class SpaceInfo(
    val id: String,
    val label: String,
    val color: String,
    val createdAt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("color", color)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(raw: Any?): SpaceInfo? {
            if (raw !is JSONObject) return null
            // 内置默认空间与「全部空间」虚拟 id 都不允许出现在自定义空间里
            val id = raw.optStringOrNull("id")
                ?.takeIf { it != Spaces.ALL }
                ?: return null
            val label = raw.optStringOrNull("label")?.trim() ?: return null
            if (label.isEmpty()) return null
            val color = raw.optStringOrNull("color")?.takeIf { isHexColor(it) } ?: "#409EFF"
            return SpaceInfo(
                id = id,
                label = label,
                color = color,
                createdAt = raw.optStringOrNull("createdAt") ?: Dates.nowIsoTimestamp(),
            )
        }
    }
}

/** 自定义分类，持久化在 data.json 的 categories 里。 */
data class CustomCategory(
    val id: String,
    val label: String,
    val color: String,
    val createdAt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("color", color)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(raw: Any?): CustomCategory? {
            if (raw !is JSONObject) return null
            val id = raw.optStringOrNull("id") ?: return null
            val label = raw.optStringOrNull("label")?.trim() ?: return null
            if (label.isEmpty()) return null
            val color = raw.optStringOrNull("color")?.takeIf { isHexColor(it) } ?: "#909399"
            return CustomCategory(
                id = id,
                label = label,
                color = color,
                createdAt = raw.optStringOrNull("createdAt") ?: Dates.nowIsoTimestamp(),
            )
        }
    }
}

// ---------------------------------------------------------------- 有效期

enum class ShelfLifeUnit { day, month, year;

    companion object {
        fun fromName(name: String?): ShelfLifeUnit =
            entries.firstOrNull { it.name == name } ?: month
    }
}

val ShelfLifeUnit.label: String
    get() = when (this) {
        ShelfLifeUnit.day -> "天"
        ShelfLifeUnit.month -> "月"
        ShelfLifeUnit.year -> "年"
    }

/** 单件物品最多几张图片。 */
const val MAX_IMAGES = 6

/** 提醒档位选项。预设之外的天数由用户在编辑页「自定义」补进来。 */
val REMIND_DAY_OPTIONS = listOf(60, 30, 15, 7, 3, 1, 0)

/** 自定义提前天数的上限：超过一年的提前提醒没有实际意义。 */
const val REMIND_DAY_MAX = 365

fun remindDayLabel(day: Int): String = if (day == 0) "当天" else "$day 天前"

/** 档位清洗：丢弃越界值（0..[REMIND_DAY_MAX]）、去重、按由远到近排。 */
fun normalizeRemindDays(days: List<Int>): List<Int> =
    days.filter { it in 0..REMIND_DAY_MAX }.distinct().sortedDescending()

/**
 * 列表项用的提醒摘要：`提前 30/7/1 天`。
 * 只勾了「当天」时是 `当天提醒`；同时含 0 天时追加 `· 当天`；未设置返回 null（列表不显示）。
 */
fun remindDaysSummary(days: List<Int>): String? {
    val levels = days.filter { it >= 0 }.distinct().sortedDescending()
    if (levels.isEmpty()) return null
    val ahead = levels.filter { it > 0 }
    val sameDay = 0 in levels
    return when {
        ahead.isEmpty() -> "当天提醒"
        sameDay -> "提前 ${ahead.joinToString("/")} 天 · 当天"
        else -> "提前 ${ahead.joinToString("/")} 天"
    }
}

// ---------------------------------------------------------------- 图片

data class ExpiryImage(
    val id: String,
    val path: String,
    val addedAt: String,
    val data: String? = null, // v3 的内联 base64，加载时迁移成文件后清空
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("path", path).put("addedAt", addedAt)
        if (data != null) o.put("data", data)
        return o
    }

    companion object {
        fun fromJson(raw: Any?): ExpiryImage? {
            if (raw !is JSONObject) return null
            val path = normalizeImagePath(raw.optStringOrNull("path") ?: "")
            val dataRaw = raw.optStringOrNull("data")
            val legacy = dataRaw?.takeIf { it.startsWith("data:image/") }
            if (path.isEmpty() && legacy == null) return null
            val id = raw.optStringOrNull("id")?.takeIf { it.isNotEmpty() }
                ?: "img_${Instant.now().toEpochMilli().toString(36)}"
            return ExpiryImage(
                id = id,
                path = path,
                addedAt = raw.optStringOrNull("addedAt") ?: Dates.nowIsoTimestamp(),
                data = legacy,
            )
        }
    }
}

private val ALLOWED_IMAGE_EXT = Regex("\\.(jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE)

/** 校验 data.json 里的图片相对路径：拒绝 `..`、绝对路径、非图片扩展名。 */
fun normalizeImagePath(raw: String?): String {
    if (raw == null) return ""
    var value = raw.trim().replace('\\', '/').trimStart('/')
    if (value.isEmpty() || value.contains("..") || !ALLOWED_IMAGE_EXT.containsMatchIn(value)) return ""
    return value
}

/** 新图片的存放路径（去掉 id 里的异常字符，避免目录穿越）。 */
fun newImagePath(id: String): String {
    val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "")
    val name = safe.ifEmpty { "img_${System.currentTimeMillis().toString(36)}" }
    return "images/$name.jpg"
}

// ---------------------------------------------------------------- 物品

data class ExpiryItem(
    val id: String,
    val name: String,
    val category: String,
    val productionDate: String,
    val shelfLife: Int,
    val shelfLifeUnit: ShelfLifeUnit,
    val expireAt: String,
    val remindDays: List<Int>,
    val quantity: Int,
    val note: String,
    val images: List<ExpiryImage>,
    val notifiedKeys: List<String>,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
    /** 归属空间；缺省 = 未分配。只有用户主动维护了空间，才会在这里写入空间 id。 */
    val spaceId: String = "",
    /**
     * 修改标记（Lamport 逻辑时钟）。每次本机新增/修改时取本机全局时钟 +1；
     * 从别的设备同步来的记录**沿用对方的 rev**，不重新分配。
     * 同步时只交换 rev 大于对方水位的记录，双方都有变更时以 rev 较大者为准。
     */
    val rev: Long = 0,
    /**
     * 共同唯一 ID：创建时生成、跨设备同步时随记录传递、永不随编辑变化。
     * 旧数据缺字段时由仓库用 id 派生补发；两台设备各自离线录入同一物品时，
     * 自动同步会按业务键把双方收敛到同一个 syncId。
     */
    val syncId: String = "",
    /**
     * 循环任务：到期不代表物品消耗完，完成本次维护（充电/清理/添置等）后进入下一周期。
     * 复用 productionDate 作为「上次完成日」、shelfLife + shelfLifeUnit 作为循环周期，
     * expireAt 始终由这两项推导，不额外引入多个周期字段，旧数据缺字段按普通物品读取。
     */
    val repeatEnabled: Boolean = false,
    /**
     * 定点提醒时刻（"HH:mm"，空 = 未设置，仅循环任务可配置）。
     * 设置后：该循环任务按提醒档位生成的触发日改在此时此刻提醒（未勾档位时默认按「当天」触发）；
     * 未设置：沿用设置页的全局推送时刻。
     */
    val reminderTime: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("spaceId", spaceId)
        .put("productionDate", productionDate)
        .put("shelfLife", shelfLife)
        .put("shelfLifeUnit", shelfLifeUnit.name)
        .put("expireAt", expireAt)
        .put("remindDays", JSONArray(remindDays))
        .put("quantity", quantity)
        .put("note", note)
        .put("images", JSONArray(images.map { it.toJson() }))
        .put("notifiedKeys", JSONArray(notifiedKeys))
        .put("archived", archived)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("rev", rev)
        .put("syncId", syncId)
        .put("repeatEnabled", repeatEnabled)
        .put("reminderTime", reminderTime)

    companion object {
        fun fromJson(raw: Any?): ExpiryItem? {
            if (raw !is JSONObject) return null
            val id = raw.optStringOrNull("id") ?: return null
            val name = raw.optStringOrNull("name") ?: return null
            val now = Dates.nowIsoTimestamp()
            val remindRaw = raw.optJSONArray("remindDays")
            val remindDays = if (remindRaw != null) {
                (0 until remindRaw.length()).mapNotNull { i ->
                    val v = remindRaw.opt(i)
                    if (v is Number) v.toInt() else v.toString().toIntOrNull()
                }
            } else emptyList()
            val imagesRaw = raw.optJSONArray("images")
            val images = if (imagesRaw != null) {
                (0 until imagesRaw.length()).mapNotNull { ExpiryImage.fromJson(imagesRaw.opt(it)) }
            } else emptyList()
            return ExpiryItem(
                id = id,
                name = name,
                category = raw.optStringOrNull("category") ?: Categories.DEFAULT,
                productionDate = raw.optStringOrNull("productionDate") ?: "",
                shelfLife = raw.optIntOrNull("shelfLife") ?: 0,
                shelfLifeUnit = ShelfLifeUnit.fromName(raw.optStringOrNull("shelfLifeUnit")),
                expireAt = raw.optStringOrNull("expireAt") ?: "",
                remindDays = remindDays,
                quantity = raw.optIntOrNull("quantity") ?: 1,
                note = raw.optStringOrNull("note") ?: "",
                images = images.take(MAX_IMAGES),
                notifiedKeys = raw.optJSONArray("notifiedKeys")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optStringOrNull2(i) }
                } ?: emptyList(),
                archived = raw.optBoolean("archived", false),
                createdAt = raw.optStringOrNull("createdAt") ?: now,
                updatedAt = raw.optStringOrNull("updatedAt") ?: now,
                // 字段缺失或显式空串都是「未分配」，默认不归属任何空间
                spaceId = raw.optStringOrNull("spaceId") ?: "",
                // 老版本没有修改标记：读到 0，首次落盘时统一补发
                rev = raw.optLong("rev", 0L),
                syncId = raw.optStringOrNull("syncId")?.trim().orEmpty(),
                repeatEnabled = raw.optBoolean("repeatEnabled", false),
                reminderTime = raw.optStringOrNull("reminderTime").orEmpty(),
            )
        }

        private fun JSONArray.optStringOrNull2(index: Int): String? {
            val v = opt(index) ?: return null
            return v as? String
        }
    }
}

// ---------------------------------------------------------------- data.json

data class ExpiryData(
    val version: Int,
    val items: List<ExpiryItem>,
    val categories: List<CustomCategory>,
    val categoryColors: Map<String, String>,
    /** 内置分类的改名覆盖（自定义分类的改名直接写在 [categories] 里）。 */
    val categoryLabels: Map<String, String> = emptyMap(),
    /** 分类展示顺序（只存 id）；为空表示按「内置在前、自定义按创建先后」的默认顺序。 */
    val categoryOrder: List<String> = emptyList(),
    /** 自定义空间。 */
    val spaces: List<SpaceInfo> = emptyList(),
    /**
     * 「未分配」「全部空间」的颜色覆盖。
     * 自定义空间的颜色直接写在 [spaces] 里，不进这张表。
     */
    val spaceColors: Map<String, String> = emptyMap(),
    /** 本机全局修改时钟：所有物品 rev 的最大值水位，随 data.json 一起落盘。 */
    val revision: Long = 0,
    /** 每种分类在「新增物品」时预填的提前提醒天数（键 = 分类 id）。
     * 没有登记的键表示该分类无默认值，新增物品时不预选任何档位。
     */
    val categoryRemindDefaults: Map<String, List<Int>> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val colors = JSONObject()
        for ((k, v) in categoryColors) colors.put(k, v)
        val labels = JSONObject()
        for ((k, v) in categoryLabels) labels.put(k, v)
        val spaceColorJson = JSONObject()
        for ((k, v) in spaceColors) spaceColorJson.put(k, v)
        val remindDefaults = JSONObject()
        for ((k, v) in categoryRemindDefaults) remindDefaults.put(k, JSONArray(v))
        return JSONObject()
            .put("version", version)
            .put("items", JSONArray(items.map { it.toJson() }))
            .put("categories", JSONArray(categories.map { it.toJson() }))
            .put("categoryColors", colors)
            .put("categoryLabels", labels)
            .put("categoryOrder", JSONArray(categoryOrder))
            .put("spaces", JSONArray(spaces.map { it.toJson() }))
            .put("spaceColors", spaceColorJson)
            .put("revision", revision)
            .put("categoryRemindDefaults", remindDefaults)
    }
}

/**
 * data.json 当前版本：v4 图片改文件相对路径；v5 分类颜色可编辑；v6 物品归属空间；
 * v7 空间颜色可编辑；v8 去掉内置默认空间（未维护的空间读取时视为未分配，不改写历史数据）；
 * v9 增加 rev 修改标记与顶层 revision 时钟且分类可设默认提前提醒天数（缺该键即无默认，
 * 不做迁移、不改写历史数据）；v10 增加 syncId 共同唯一 ID（旧数据缺字段按空读取，首次落盘由 id 派生补发）。
 *
 * 注：v9 在本分支同时承载「rev/revision 时钟」与「分类默认提醒」两项上游先后引入的语义，
 * 因 DATA_VERSION 仅作落盘标记、不参与读取分支判断，合并后统一记为 v11 以区别于两侧历史版本。
 * v12 增加物品的 repeatEnabled 循环任务标记（旧数据缺字段按 false 读取，不改写历史数据）。
 * v13 增加循环任务的定点提醒时刻 reminderTime（旧数据缺字段按空读取，不改写历史数据）。
 */
const val DATA_VERSION = 13

// ---------------------------------------------------------------- 状态

enum class ExpiryStatus { expired, soon, month, normal;

    val label: String
        get() = when (this) {
            expired -> "已过期"
            soon -> "7 天内到期"
            month -> "30 天内到期"
            normal -> "正常"
        }

    /** 状态色。 */
    val color: String
        get() = when (this) {
            expired -> "#F56C6C"
            soon -> "#E6A23C"
            month -> "#409EFF"
            normal -> "#67C23A"
        }
}

// ---------------------------------------------------------------- 计算

object ExpiryCalc {
    /**
     * 起始日 + 周期得到的终点日；起始日非法或周期非正时返回 null。
     * 整月/整年走日历月推进（月末由 LocalDate 自动收敛）。
     */
    private fun endOfSpan(
        productionDate: String,
        amount: Int,
        unit: ShelfLifeUnit,
    ): java.time.LocalDate? {
        val start = Dates.parseISO(productionDate) ?: return null
        if (amount <= 0) return null
        val span = floor(amount.toDouble()).toInt()
        return if (unit == ShelfLifeUnit.day) {
            start.plusDays(span.toLong())
        } else {
            start.plusMonths((if (unit == ShelfLifeUnit.year) span * 12 else span).toLong())
        }
    }

    /**
     * 由起始日期 + 有效期推算「最后一天有效」的到期日，条件不足返回空串。
     *
     * 有效期按含首尾的自然日计算：2026-11-10 生产、有效期 1 个月 → 2026-12-09。
     * 整月/整年在月末收敛后再减一天（1 月 31 日 + 1 月 → 2 月 27 日）。
     */
    fun computeExpireAt(productionDate: String, amount: Int, unit: ShelfLifeUnit): String {
        val end = endOfSpan(productionDate, amount, unit) ?: return ""
        return Dates.toISODate(end.minusDays(1))
    }

    /**
     * 循环任务的下次到期日：起始日 + 周期，**不**减一天。
     *
     * 与 [computeExpireAt] 的「含首尾」口径不同：循环任务按整天数推进，
     * 起始日 11-10、周期 1 个月 → 12-10；周期 1 天 → 次日。
     */
    fun computeCycleExpireAt(productionDate: String, amount: Int, unit: ShelfLifeUnit): String {
        val end = endOfSpan(productionDate, amount, unit) ?: return ""
        return Dates.toISODate(end)
    }

    /**
     * 循环物品完成本次后进入下一周期：以实际完成日为新起点，重新推导到期日，
     * 并清空本周期的提醒记录，让新周期的同一档位能再次触发。
     * 非循环物品或周期不合法时原样返回。
     */
    fun advanceCycle(
        item: ExpiryItem,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        updatedAt: String = Dates.nowIsoTimestamp(),
    ): ExpiryItem {
        if (!item.repeatEnabled || item.shelfLife <= 0) return item
        val start = Dates.toISODate(today)
        val nextExpire = computeCycleExpireAt(start, item.shelfLife, item.shelfLifeUnit)
        if (nextExpire.isEmpty()) return item
        return item.copy(
            productionDate = start,
            expireAt = nextExpire,
            notifiedKeys = emptyList(),
            updatedAt = updatedAt,
        )
    }

    /** FNV-1a：由去重键派生稳定的通知 id。 */
    fun hashKey(key: String): Int {
        var hash = 0x811c9dc5L
        for (ch in key) {
            hash = (hash xor ch.code.toLong()) * 0x01000193L and 0xFFFFFFFFL
        }
        return (((hash shr 1) and 0x7FFFFFFF).toInt() % 2000000000) + 1
    }
}
