package com.tangdun.app.ai

/**
 * 食物营养表 (每 100g: 碳水 g, 热量 kcal, GI)
 *
 * ★ v2.9: 从 AiRecordHelper 中提取为公共常量, 让 AgentToolExecutor 等模块复用
 */
object FoodNutrition {
    /** 食物 -> Triple(碳水/100g, 热量/100g, GI) */
    val TABLE: Map<String, Triple<Double, Double, Double>> = mapOf(
        // 主食类
        "米饭" to Triple(28.0, 116.0, 70.0),
        "面条" to Triple(25.0, 110.0, 60.0),
        "馒头" to Triple(45.0, 220.0, 85.0),
        "荞麦馒头" to Triple(45.0, 220.0, 65.0),
        "全麦面包" to Triple(41.0, 250.0, 50.0),
        "面包" to Triple(50.0, 280.0, 70.0),
        "小米粥" to Triple(10.0, 46.0, 65.0),
        "玉米" to Triple(19.0, 86.0, 55.0),
        "红薯" to Triple(20.0, 86.0, 54.0),
        "土豆" to Triple(17.0, 77.0, 62.0),
        "燕麦" to Triple(12.0, 68.0, 55.0),
        // ★ v3.0.8: 饺子/包子/烧麦/米线等中式主食 (按 100g 估算)
        "饺子" to Triple(22.0, 240.0, 60.0),       // 猪肉白菜水饺
        "猪肉饺子" to Triple(22.0, 240.0, 60.0),
        "三鲜饺子" to Triple(22.0, 230.0, 60.0),
        "韭菜饺子" to Triple(23.0, 235.0, 60.0),
        "玉米饺子" to Triple(26.0, 245.0, 62.0),
        "虾仁饺子" to Triple(20.0, 215.0, 58.0),
        "素饺子" to Triple(24.0, 200.0, 60.0),
        "馄饨" to Triple(15.0, 180.0, 55.0),
        "包子" to Triple(28.0, 220.0, 65.0),       // 猪肉大葱包
        "肉包子" to Triple(28.0, 220.0, 65.0),
        "素包子" to Triple(30.0, 180.0, 65.0),
        "豆沙包" to Triple(45.0, 230.0, 70.0),
        "奶黄包" to Triple(40.0, 240.0, 70.0),
        "烧麦" to Triple(25.0, 230.0, 65.0),
        "春卷" to Triple(30.0, 280.0, 65.0),
        "煎饺" to Triple(26.0, 290.0, 62.0),
        "锅贴" to Triple(26.0, 280.0, 62.0),
        "米线" to Triple(22.0, 100.0, 65.0),
        "河粉" to Triple(24.0, 110.0, 65.0),
        "米粉" to Triple(25.0, 110.0, 65.0),
        "凉皮" to Triple(20.0, 90.0, 60.0),
        "年糕" to Triple(35.0, 160.0, 75.0),
        "汤圆" to Triple(45.0, 280.0, 75.0),
        "粽子" to Triple(40.0, 220.0, 70.0),
        // 饼干零食
        "苏打饼干" to Triple(72.0, 440.0, 70.0),
        "饼干" to Triple(60.0, 400.0, 70.0),
        "蛋糕" to Triple(38.0, 340.0, 67.0),
        // 蛋奶
        "鸡蛋" to Triple(1.0, 70.0, 30.0),
        "牛奶" to Triple(5.0, 65.0, 30.0),
        "酸奶" to Triple(8.0, 70.0, 35.0),
        // 蔬菜
        "小油菜" to Triple(2.0, 20.0, 20.0),
        "油菜" to Triple(2.0, 20.0, 20.0),
        "上海青" to Triple(2.0, 13.0, 15.0),
        "白菜" to Triple(2.0, 17.0, 23.0),
        "菠菜" to Triple(3.0, 24.0, 15.0),
        "黄瓜" to Triple(3.0, 16.0, 15.0),
        "番茄" to Triple(4.0, 18.0, 15.0),
        "凉拌黄瓜" to Triple(3.0, 20.0, 15.0),
        // 豆制品
        "豆腐" to Triple(2.0, 80.0, 31.0),
        "香椿豆腐" to Triple(3.0, 85.0, 30.0),
        "蒜蓉上海青" to Triple(2.0, 25.0, 15.0),
        // 荤菜
        "香椿炒鸡蛋" to Triple(5.0, 180.0, 40.0),
        "炸鸡腿" to Triple(25.0, 280.0, 60.0),
        "炸鸡" to Triple(25.0, 280.0, 60.0),
        "葱爆羊肉" to Triple(5.0, 200.0, 30.0),
        "羊肉" to Triple(0.0, 200.0, 30.0),
        "清炖牛肉" to Triple(0.0, 130.0, 0.0),
        "清炖瘦牛肉" to Triple(0.0, 130.0, 0.0),
        "白灼虾仁" to Triple(1.0, 90.0, 40.0),
        "虾仁" to Triple(1.0, 90.0, 40.0),
        "虾" to Triple(1.0, 90.0, 40.0),
        "卤鸡腿" to Triple(2.0, 200.0, 30.0),
        "鸡腿" to Triple(2.0, 200.0, 30.0),
        "鸡肉" to Triple(0.0, 130.0, 0.0),
        // 水果
        "苹果" to Triple(14.0, 52.0, 36.0),
        "蜜橘" to Triple(10.0, 43.0, 43.0),
        "橘子" to Triple(10.0, 43.0, 43.0),
        "水果" to Triple(13.0, 50.0, 40.0),
        "香蕉" to Triple(22.0, 89.0, 52.0)
    )

    /**
     * 估算食物营养 (智能匹配, 支持子串匹配)
     * @return Triple(碳水g, 热量kcal, GI), 找不到时返回默认值
     */
    fun estimate(food: String, grams: Double): Triple<Double, Double, Double> {
        val exact = TABLE[food]
        if (exact != null) {
            val ratio = grams / 100.0
            return Triple(exact.first * ratio, exact.second * ratio, exact.third)
        }
        // 子串模糊匹配
        val fuzzy = TABLE.entries.firstOrNull { (key, _) ->
            food.contains(key) || key.contains(food)
        }
        if (fuzzy != null) {
            val ratio = grams / 100.0
            return Triple(fuzzy.value.first * ratio, fuzzy.value.second * ratio, fuzzy.value.third)
        }
        // 默认: 15% 碳水, 1.5 kcal/g, GI 50
        return Triple(grams * 0.15, grams * 1.5, 50.0)
    }
}