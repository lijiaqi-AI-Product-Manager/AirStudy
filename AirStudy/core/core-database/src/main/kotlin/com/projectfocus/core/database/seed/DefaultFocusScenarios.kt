package com.projectfocus.core.database.seed


object DefaultFocusScenarios {

    /**
     * 一个内置场景的定义。
     *
     * @param key      资源 key，写进 `focus_categories.name`
     * @param emoji    推定值（见类注释）
     * @param colorHex 推定值（见类注释）
     * @param sortOrder 推定值
     */
    data class Seed(
        val key: String,
        val emoji: String,
        val colorHex: String,
        val sortOrder: Int,
    )

    /**
     * 9 个内置场景，顺序即 UI 默认排序。
     *
     * ⚠️ 这个列表是**唯一真相源**：改名/加场景只改这里。
     *    `FocusCategorySeeder` 与 UI 层的翻译映射都从这里取 key。
     */
    val ALL: List<Seed> = listOf(
        Seed(key = "career", emoji = "💼", colorHex = "#3F51B5", sortOrder = 0),
        Seed(key = "work", emoji = "🗂️", colorHex = "#303F9F", sortOrder = 1),
        Seed(key = "code", emoji = "💻", colorHex = "#00B8D4", sortOrder = 2),
        Seed(key = "design", emoji = "🎨", colorHex = "#E91E63", sortOrder = 3),
        Seed(key = "write", emoji = "✍️", colorHex = "#7E57C2", sortOrder = 4),
        Seed(key = "read", emoji = "📖", colorHex = "#8D6E63", sortOrder = 5),
        Seed(key = "exercise", emoji = "🏃", colorHex = "#4CAF50", sortOrder = 6),
        Seed(key = "meditate", emoji = "🧘", colorHex = "#607D8B", sortOrder = 7),
        Seed(key = "cook", emoji = "🍳", colorHex = "#FF7043", sortOrder = 8),
    )

    /** 合法的 key 集合，供 UI 层翻译时校验（避免拼错） */
    val KEYS: Set<String> = ALL.map { it.key }.toSet()

    /** 按 key 取定义；未知 key 返回 null（UI 层据此回退到显示原始 name） */
    fun byKey(key: String): Seed? = ALL.firstOrNull { it.key == key }
}
