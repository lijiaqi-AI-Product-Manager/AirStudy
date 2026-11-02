package com.projectfocus.core.model.focus


data class FocusCategory(

    val id: Long,
    /** 分类名。isDefault=true 时是资源 key（career/code/...），否则是用户输入的文案 */
    val name: String,
    /** 颜色，`#RRGGBB` 字符串 */
    val colorHex: String,
    /** 表情符号 */
    val emoji: String,
    /** 内置分类：不可删除（DAO 里靠 `AND isDefault = 0` 保护） */
    val isDefault: Boolean,
    /** 手动排序权重，升序 */
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * 展示名解析用的 key。
     *
     * 内置分类的 name 是资源 key（"career"），需要查 strings.xml 翻译；
     * 用户自建分类的 name 就是最终文案，直接返回。
     * 真正的翻译在 UI 层做（core-ui 的 FocusCategoryTextMapper），
     * 领域层只负责告诉调用方「该走翻译还是直接用」。
     */
    val displayNameKey: String?
        get() = name.takeIf { isDefault }

    /** 是否走资源翻译 */
    val needsTranslation: Boolean get() = isDefault
}


data class BlockedApp(

    val id: Long,
    /** 外键 → focus_categories.id。**NOT NULL**，删分类时 CASCADE 连规则一起删 */
    val categoryId: Long,
    val packageName: String,


    val appName: String,
    val type: BlockedAppType,
    val createdAt: Long,
)


enum class BlockedAppType {
    APP,
    CATEGORY,
    WEBSITE,
    ;

    companion object {
        fun fromRaw(raw: String?): BlockedAppType = when (raw?.uppercase()) {
            "CATEGORY" -> CATEGORY
            "WEBSITE" -> WEBSITE
            else -> APP
        }
    }
}
