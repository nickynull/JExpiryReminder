package com.jimmy.expiryreminder.ocr

/**
 * 多图结果合并。
 *
 * 6 张照片大概率是同一物品的不同面（正面品名、侧面配料、底部喷码），所以要跨图
 * 汇总：同一个值在多张图里出现属于交叉印证，同一个品名在多张图里出现更可信。
 */
object ResultMerger {

    private const val MAX_NAME_CANDIDATES = 4
    private const val MAX_DATE_CANDIDATES = 8
    private const val MULTI_IMAGE_NAME_BONUS = 0.20
    private const val MULTI_IMAGE_DATE_BONUS = 0.15
    private const val SIMILAR_DISTANCE = 2

    data class Merged(
        val names: List<NameCandidate> = emptyList(),
        val dates: List<DateCandidate> = emptyList(),
        val shelfLives: List<ShelfLifeCandidate> = emptyList(),
    )

    fun merge(
        nameGroups: List<List<NameCandidate>>,
        dateGroups: List<List<DateCandidate>>,
        shelfGroups: List<List<ShelfLifeCandidate>>,
    ): Merged = Merged(
        names = mergeNames(nameGroups.flatten()),
        dates = mergeDates(dateGroups.flatten()),
        shelfLives = mergeShelfLives(shelfGroups.flatten()),
    )

    // ------------------------------------------------------------ 品名

    private fun mergeNames(all: List<NameCandidate>): List<NameCandidate> {
        if (all.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<NameCandidate>>()
        for (candidate in all) {
            val key = TextNormalizer.comparisonKey(candidate.text)
            if (key.isEmpty()) continue
            val hit = clusters.firstOrNull { cluster ->
                val ref = TextNormalizer.comparisonKey(cluster.first().text)
                distance(ref, key) <= SIMILAR_DISTANCE
            }
            if (hit != null) hit += candidate else clusters += mutableListOf(candidate)
        }

        return clusters.mapNotNull { cluster ->
            val best = cluster.maxByOrNull { it.score } ?: return@mapNotNull null
            // 簇内保留字符更完整的一条（「特仑苏纯牛奶」优于「特仑苏」）
            val longest = cluster.maxByOrNull { it.text.length } ?: best
            val images = cluster.map { it.imageIndex }.distinct().size
            NameCandidate(
                text = longest.text,
                score = (best.score + if (images >= 2) MULTI_IMAGE_NAME_BONUS else 0.0).coerceAtMost(1.0),
                imageCount = images,
                imageIndex = best.imageIndex,
            )
        }
            .sortedWith(compareByDescending<NameCandidate> { it.score }.thenByDescending { it.imageCount })
            .take(MAX_NAME_CANDIDATES)
    }

    // ------------------------------------------------------------ 日期

    private fun mergeDates(all: List<DateCandidate>): List<DateCandidate> {
        if (all.isEmpty()) return emptyList()
        return all.groupBy { it.isoDate }
            .mapNotNull { (_, group) ->
                val best = group.maxByOrNull { it.score } ?: return@mapNotNull null
                val images = group.map { it.imageIndex }.distinct().size
                best.copy(
                    score = (best.score + if (images >= 2) MULTI_IMAGE_DATE_BONUS else 0.0).coerceAtMost(1.0),
                    imageCount = images,
                )
            }
            .sortedByDescending { it.score }
            .take(MAX_DATE_CANDIDATES)
    }

    // ------------------------------------------------------------ 保质期

    private fun mergeShelfLives(all: List<ShelfLifeCandidate>): List<ShelfLifeCandidate> {
        if (all.isEmpty()) return emptyList()
        return all.groupBy { it.amount to it.unit }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
            .take(4)
    }

    // ------------------------------------------------------------ 编辑距离

    /** Levenshtein 距离，用于把「特仑苏纯牛奶」和「特仑苏纯牛乳」聚成一类。 */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
