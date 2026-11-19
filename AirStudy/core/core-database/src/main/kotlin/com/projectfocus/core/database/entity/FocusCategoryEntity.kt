package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "focus_categories",
    indices = [
        Index(value = ["name", "isDefault"], name = "index_focus_categories_name_isDefault", unique = true),
    ],
)
data class FocusCategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** isDefault=true 时是资源 key（career/code/cook/design/exercise/meditate/read/work/write） */
    @ColumnInfo(name = "name")
    val name: String,

    /** `#RRGGBB` */
    @ColumnInfo(name = "colorHex")
    val colorHex: String,

    @ColumnInfo(name = "emoji")
    val emoji: String,

    @ColumnInfo(name = "isDefault")
    val isDefault: Boolean,

    @ColumnInfo(name = "sortOrder")
    val sortOrder: Int,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)
