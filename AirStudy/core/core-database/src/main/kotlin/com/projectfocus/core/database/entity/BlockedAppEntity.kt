package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "blocked_apps",
    foreignKeys = [
        ForeignKey(
            entity = FocusCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onUpdate = ForeignKey.NO_ACTION,

            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["categoryId"], name = "index_blocked_apps_categoryId"),

        //   没有它，upsertAll 的 REPLACE 只在自增主键上冲突，重复添加同一 App 会堆出多行。
        Index(
            value = ["categoryId", "packageName"],
            name = "index_blocked_apps_categoryId_packageName",
            unique = true,
        ),
    ],
)
data class BlockedAppEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** NOT NULL —— 每条规则必须归属一个分类 */
    @ColumnInfo(name = "categoryId")
    val categoryId: Long,

    /** APP / CATEGORY 时是包名；WEBSITE 时是域名 */
    @ColumnInfo(name = "packageName")
    val packageName: String,

    /** 冗余存的应用显示名 —— 拦截时省掉 PackageManager IPC 开销 */
    @ColumnInfo(name = "appName")
    val appName: String,

    /** APP / CATEGORY / WEBSITE，存枚举名字符串 */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
