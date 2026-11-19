package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.projectfocus.core.database.entity.FocusCategoryEntity
import kotlinx.coroutines.flow.Flow


@Dao
abstract class FocusCategoryDao {

    /** 全部分类，`sortOrder` 升序 + `name` 升序 */
    @Query("SELECT * FROM focus_categories ORDER BY sortOrder ASC, name ASC")
    abstract fun observeAll(): Flow<List<FocusCategoryEntity>>

    @Query("SELECT * FROM focus_categories ORDER BY sortOrder ASC, name ASC")
    abstract suspend fun getAll(): List<FocusCategoryEntity>

    /** 用户自建分类（排除内置） */
    @Query("SELECT * FROM focus_categories WHERE isDefault = 0 ORDER BY sortOrder ASC")
    abstract suspend fun customCategories(): List<FocusCategoryEntity>

    @Query("SELECT * FROM focus_categories WHERE id = :id LIMIT 1")
    abstract suspend fun findById(id: Long): FocusCategoryEntity?

    /** 内置分类数量（用于判断是否需要灌种子数据） */
    @Query("SELECT COUNT(*) FROM focus_categories WHERE isDefault = 1")
    abstract suspend fun defaultCount(): Int

    @Query("SELECT COUNT(*) FROM focus_categories WHERE isDefault = 0")
    abstract suspend fun customCount(): Int

    /**
     * 删除用户分类。内置分类删不掉 —— 条件里带 `AND isDefault = 0`。
     *
     * 返回受影响行数：0 表示这条是内置分类（或不存在），调用方据此提示用户。
     */
    @Query("DELETE FROM focus_categories WHERE id = :id AND isDefault = 0")
    abstract suspend fun deleteCustomById(id: Long): Int

    /** 更新排序（拖拽排序用） */
    @Query("UPDATE focus_categories SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateSortOrder(id: Long, sortOrder: Int, updatedAt: Long)

    // ── 写入 ───────────────────────────────────────────────

    /** 插入并返回自增 ID */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entity: FocusCategoryEntity): Long

    /** 灌种子数据（内置分类）。REPLACE 保证幂等，可重复调用 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<FocusCategoryEntity>)

    @Update
    abstract suspend fun update(entity: FocusCategoryEntity)

    @Query("DELETE FROM focus_categories")
    abstract suspend fun deleteAll()
}
