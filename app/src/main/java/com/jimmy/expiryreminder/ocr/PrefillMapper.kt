package com.jimmy.expiryreminder.ocr

import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 合并结果 → 添加页预填草稿。
 *
 * 按「能确定多少填多少」处理，三种组合分别对应：
 * 生产日期 + 保质期 → 推算到期日；只有「有效期至」→ 直接填；只有生产日期 → 只填它。
 */
object PrefillMapper {

    fun toDraft(merged: ResultMerger.Merged, images: List<ExpiryImage> = emptyList()): PrefillDraft {
        val bestName = merged.names.firstOrNull()
        val category = bestName?.let { CategoryGuesser.guess(it.text) }

        val produce = merged.dates
            .filter { it.role == DateRole.produce && it.prefillable }
            .maxByOrNull { it.score }
            ?.isoDate
            ?.takeIf { Dates.isValidISODate(it) }

        val expire = merged.dates
            .filter { it.role == DateRole.expire && it.prefillable }
            .maxByOrNull { it.score }
            ?.isoDate
            ?.takeIf { Dates.isValidISODate(it) }

        val life = merged.shelfLives.filter { it.prefillable }.maxByOrNull { it.score }

        var productionDate = ""
        var shelfLife = 0
        var unit = ShelfLifeUnit.month
        var expireAt = ""
        var manualExpire = false

        when {
            produce != null && life != null -> {
                productionDate = produce
                shelfLife = life.amount
                unit = life.unit
                expireAt = ExpiryCalc.computeExpireAt(productionDate, shelfLife, unit)
            }

            expire != null -> {
                val produceDate = produce?.let { Dates.parseISO(it) }
                val expireDate = Dates.parseISO(expire)
                if (produceDate != null && expireDate != null && expireDate.isBefore(produceDate)) {
                    // 两个日期自相矛盾，至少有一个识别错了：宁可都留空，只让用户在候选区里挑
                    productionDate = ""
                    expireAt = ""
                } else {
                    expireAt = expire
                    manualExpire = true
                    if (produce != null && produceDate != null && expireDate != null) {
                        productionDate = produce
                        inferShelfLife(produceDate, expireDate)?.let { inferred ->
                            shelfLife = inferred.first
                            unit = inferred.second
                        }
                    }
                }
            }

            produce != null -> productionDate = produce
        }

        return PrefillDraft(
            name = bestName?.text.orEmpty(),
            nameCandidates = merged.names,
            category = category,
            productionDate = productionDate,
            shelfLife = shelfLife,
            shelfLifeUnit = unit,
            expireAt = expireAt,
            manualExpire = manualExpire,
            dateCandidates = merged.dates,
            images = images,
        )
    }

    /**
     * 由生产日期与到期日反推有效期：优先按整月、整年，否则按天。
     * 反推不准也不致命——[PrefillDraft.manualExpire] 为 true 时添加页不会重算到期日。
     */
    internal fun inferShelfLife(produce: LocalDate, expire: LocalDate): Pair<Int, ShelfLifeUnit>? {
        if (!expire.isAfter(produce)) return null
        val months = ChronoUnit.MONTHS.between(produce, expire)
        if (months > 0 && produce.plusMonths(months) == expire) {
            return if (months % 12L == 0L) {
                (months / 12).toInt() to ShelfLifeUnit.year
            } else {
                months.toInt() to ShelfLifeUnit.month
            }
        }
        val days = ChronoUnit.DAYS.between(produce, expire)
        return if (days > 0) days.toInt() to ShelfLifeUnit.day else null
    }
}
