package com.openminis.app.data.murmur

/**
 * [T-today-murmur] Which of a murmur's 吃喝 mentions are dishes for 我的菜单
 * (SPEC §19.6). The model also picks out ingredients when a murmur is a
 * recipe ("咖喱 · 花椒 · 牛肉块 · 茄子"), now and then a plant (绣球花), and
 * sometimes cuts a name short (厚蛋 for 厚蛋烧) — cleaned up here, by rule,
 * tested on cases shaped like real murmurs. Only the model says what is food.
 */
object DishRules {
    /** Too general to be a dish. */
    private val GENERAL = setOf(
        "饭", "菜", "外卖", "美食", "零食", "早饭", "午饭", "晚饭", "早餐", "午餐", "晚餐", "夜宵", "吃的", "东西", "饭菜",
        "做饭", "水", "食材", "海鲜", "菜谱", "菜单",
    )

    /** Raw ingredients and seasonings: what goes into a dish, not the dish. */
    private val INGREDIENTS = setOf(
        "花椒", "辣椒", "小米辣", "胡椒", "胡椒粉", "八角", "桂皮", "香叶", "孜然", "盐", "糖", "白糖", "冰糖", "醋", "生抽", "老抽",
        "酱油", "蚝油", "料酒", "味精", "鸡精", "豆瓣酱", "淀粉", "面粉", "油", "猪油", "菜籽油", "香油", "麻油",
        "葱", "大葱", "小葱", "葱段", "葱花", "姜", "姜片", "姜丝", "生姜", "蒜", "大蒜", "蒜瓣", "蒜末", "姜葱", "葱姜", "洋葱",
        "香菜", "芹菜", "韭菜", "白菜", "青菜", "生菜", "菠菜", "茄子", "丝瓜", "黄瓜", "冬瓜", "南瓜", "苦瓜", "土豆", "番茄",
        "西红柿", "胡萝卜", "萝卜", "玉米", "茭白", "香菇", "蘑菇", "口蘑", "金针菇", "木耳", "豆腐", "豆芽", "荷兰豆", "豇豆",
        "西兰花", "花菜", "莲藕", "山药", "芋头",
        "鸡蛋", "蛋", "牛奶", "奶油", "黄油", "芝士", "米", "米饭", "大米", "面条",
        "牛肉", "牛肉块", "猪肉", "五花肉", "肉片", "肉末", "排骨", "鸡肉", "鸡腿", "鸡翅", "鸭肉", "羊肉", "香肠", "腊肠", "火腿",
        "虾", "虾仁", "鱿鱼", "蟹", "螃蟹", "鱼", "干贝", "瑶贝干", "肥牛片", "肥牛",
        "芒果", "西瓜", "苹果", "香蕉", "橙子", "葡萄", "草莓", "柠檬", "石榴",
    )

    /** Kept even though they end like a plant name. */
    private val FLOWERY_FOOD = setOf("豆花", "花生", "桂花糕", "桂花酒酿", "菊花茶", "西兰花", "花菜")

    /** A name with one of these reads as a dish or a drink, even in a recipe murmur. */
    private val DISH_MARK = Regex("汤|面|饭|粥|饼|饺|包|锅|烧|炒|煎|炸|烤|卤|炖|蒸|焖|拌|煲|羹|糕|酥|卷|球|丸|冰淇淋|冰浆|气泡水|奶茶|咖啡|茶|酒|兔|蟹|排|串")

    /** A name cut short before one of these is lengthened to include it (厚蛋 → 厚蛋烧). */
    private val TAIL = Regex("^(烧|汤|面|饭|饼|球|卷|粥|锅)")

    /**
     * The dishes among [foods] (a murmur's 吃喝 mentions, in order), named as
     * the murmur writes them. A murmur listing three or more is a recipe or a
     * meal plan: only the first name and the dish-like ones are kept.
     */
    fun dishes(foods: List<String>, body: String): List<String> {
        val recipe = foods.size >= 3
        return foods.mapIndexedNotNull { i, raw ->
            var name = raw.trim().removePrefix("《").removeSuffix("》")
            val at = body.indexOf(name)
            if (at >= 0) TAIL.find(body.substring(at + name.length))?.let { name += it.value }
            when {
                name.length < 2 || name in GENERAL || name in INGREDIENTS -> null
                // "RS3": a term, not a food.
                Regex("^[A-Za-z0-9]+$").matches(name) && name.any(Char::isDigit) -> null
                Regex("[花草树叶]$").containsMatchIn(name) && name !in FLOWERY_FOOD -> null
                recipe && i > 0 && !DISH_MARK.containsMatchIn(name) -> null
                else -> name
            }
        }.distinct()
    }
}
