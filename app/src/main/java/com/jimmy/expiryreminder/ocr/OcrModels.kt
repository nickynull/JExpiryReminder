package com.jimmy.expiryreminder.ocr

import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ShelfLifeUnit

/**
 * OCR 链路的中间模型。
 *
 * 这里的对象全部只存在于内存与页面之间传递，**不写进 data.json**，
 * 以免为识别功能引入需要长期维护的持久化字段与数据格式迁移。
 */

/** 一行识别结果。[height] 是 boundingBox 高度，用来做版面分析（字大的是品名）。 */
data class OcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val height: Int get() = bottom - top
}

/** 打分阈值：达到 [PRE_FILL_SCORE] 才敢预填，[CANDIDATE_SCORE] 以下直接丢弃。 */
const val PRE_FILL_SCORE = 0.7
const val CANDIDATE_SCORE = 0.4

/** 日期在这张包装上扮演什么角色。 */
enum class DateRole {
    produce, expire, unknown;

    val label: String
        get() = when (this) {
            produce -> "生产"
            expire -> "有效期至"
            unknown -> "日期"
        }
}

/**
 * 一个日期候选。
 *
 * [imageCount] 是同一个值在多张图里出现的次数——跨图重复出现属于交叉印证，
 * 由 [ResultMerger] 加分。
 */
data class DateCandidate(
    val raw: String,
    val isoDate: String,
    val role: DateRole,
    val score: Double,
    val sourceLine: String = "",
    val imageIndex: Int = 0,
    val imageCount: Int = 1,
) {
    val prefillable: Boolean get() = score >= PRE_FILL_SCORE
}

/** 相对保质期，例如「保质期 12 个月」。 */
data class ShelfLifeCandidate(
    val amount: Int,
    val unit: ShelfLifeUnit,
    val score: Double,
    val sourceLine: String = "",
    val imageIndex: Int = 0,
) {
    val prefillable: Boolean get() = score >= PRE_FILL_SCORE
}

/** 品名候选。[imageCount] ≥2 表示多张图给出同一名称，展示时会标注「多图一致」。 */
data class NameCandidate(
    val text: String,
    val score: Double,
    val imageCount: Int = 1,
    val imageIndex: Int = 0,
)

/**
 * 预填草稿：识别结果 → 添加页的载体。
 *
 * [manualExpire] 为 true 表示到期日是识别直接得到的，添加页不应再用
 * 「生产日期 + 有效期」的自动推算覆盖它。
 */
data class PrefillDraft(
    val name: String = "",
    val nameCandidates: List<NameCandidate> = emptyList(),
    val category: String? = null,
    val productionDate: String = "",
    val shelfLife: Int = 0,
    val shelfLifeUnit: ShelfLifeUnit = ShelfLifeUnit.month,
    val expireAt: String = "",
    val manualExpire: Boolean = false,
    val dateCandidates: List<DateCandidate> = emptyList(),
    val images: List<ExpiryImage> = emptyList(),
) {
    /** 一个字段都没识别出来——添加页只提示、不预填。 */
    val hasNothing: Boolean
        get() = name.isEmpty() && productionDate.isEmpty() && expireAt.isEmpty() && shelfLife <= 0
}
