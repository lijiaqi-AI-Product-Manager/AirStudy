package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.projectfocus.core.database.entity.BlockedAppEntity
import kotlinx.coroutines.flow.Flow


@Dao
abstract class BlockedAppDao {

    /** 全部规则（高频路径，务必走 Repository 缓存） */
    @Query("SELECT * FROM blocked_apps")
    abstract fun observeAll(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps")
    abstract suspend fun getAll(): List<BlockedAppEntity>

    /** 某分类下的规则 */
    @Query("SELECT * FROM blocked_apps WHERE categoryId = :categoryId")
    abstract fun observeByCategory(categoryId: Long): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps WHERE categoryId = :categoryId")
    abstract suspend fun byCategory(categoryId: Long): List<BlockedAppEntity>


    @Query("SELECT packageName FROM blocked_apps")
    abstract suspend fun allPackageNames(): List<String>

    /** 删某分类全部规则（外键 CASCADE 也会做同样的事，这条留给显式清理） */
    @Query("DELETE FROM blocked_apps WHERE categoryId = :categoryId")
    abstract suspend fun deleteByCategory(categoryId: Long)

    @Query("DELETE FROM blocked_apps WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /** 同一分类下同一包名只允许一条（防止用户重复添加） */
    @Query("SELECT * FROM blocked_apps WHERE categoryId = :categoryId AND packageName = :packageName LIMIT 1")
    abstract suspend fun find(categoryId: Long, packageName: String): BlockedAppEntity?

    // ── 写入 ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entity: BlockedAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<BlockedAppEntity>)

    @Query("DELETE FROM blocked_apps")
    abstract suspend fun deleteAll()
}
