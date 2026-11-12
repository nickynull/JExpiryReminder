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
    return any { it.id != exceptId && it.label.trim().equals(target, ignoreCase = true) }
}

object Categories {
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
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
) {
    fun toJson(): JSONObject {
        val colors = JSONObject()
        for ((k, v) in categoryColors) colors.put(k, v)
        val labels = JSONObject()
        for ((k, v) in categoryLabels) labels.put(k, v)
        return JSONObject()
            .put("version", version)
            .put("items", JSONArray(items.map { it.toJson() }))
            .put("categories", JSONArray(categories.map { it.toJson() }))
            .put("categoryColors", colors)
            .put("categoryLabels", labels)
            .put("categoryOrder", JSONArray(categoryOrder))
    }
}

/** data.json 当前版本：v4 图片改文件相对路径；v5 分类颜色可编辑。 */
const val DATA_VERSION = 5

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
    /** 由生产日期 + 有效期推算到期日期，条件不足返回空串。 */
    fun computeExpireAt(productionDate: String, amount: Int, unit: ShelfLifeUnit): String {
        if (!Dates.isValidISODate(productionDate) || amount <= 0) return ""
        val span = floor(amount.toDouble()).toInt()
        return if (unit == ShelfLifeUnit.day) {
            Dates.addDays(productionDate, span)
        } else {
            Dates.addMonths(productionDate, if (unit == ShelfLifeUnit.year) span * 12 else span)
        }
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
