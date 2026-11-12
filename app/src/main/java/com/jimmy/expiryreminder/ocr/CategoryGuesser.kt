package com.jimmy.expiryreminder.ocr

/**
 * 品名关键词 → 内置分类 id。
 *
 * 顺序有讲究：化妆品排在食品前面，否则「乳液」会先被食品的「乳」命中。
 * 猜测失败返回 null，由添加页回落到默认分类。
 */
object CategoryGuesser {

    private val RULES = listOf(
        "cosmetic" to listOf(
            "面膜", "精华", "乳液", "面霜", "洗发水", "护发素", "沐浴露", "口红", "粉底",
            "防晒", "香水", "眼霜", "洁面", "卸妆", "化妆水", "身体乳", "发膜", "唇膏",
        ),
        "medicine" to listOf(
            "胶囊", "片剂", "口服液", "颗粒", "糖浆", "软膏", "滴眼液", "创可贴", "维生素",
            "布洛芬", "感冒灵", "消炎", "阿莫西林", "钙片", "眼药水", "喷雾剂",
        ),
        "document" to listOf(
            "护照", "身份证", "驾驶证", "驾照", "签证", "证书", "执照", "许可证", "居住证", "通行证",
        ),
        "food" to listOf(
            "牛奶", "酸奶", "面包", "饼干", "饮料", "果汁", "零食", "大米", "面粉", "食用油",
            "酱油", "食醋", "巧克力", "糖果", "茶叶", "咖啡", "火腿", "香肠", "罐头", "麦片",
            "坚果", "奶粉", "啤酒", "白酒", "矿泉水",
        ),
    )

    fun guess(name: String): String? {
        if (name.isEmpty()) return null
        return RULES.firstOrNull { (_, words) -> words.any { name.contains(it) } }?.first
    }
}
