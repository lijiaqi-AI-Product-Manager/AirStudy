package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.projectfocus.core.database.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow












@Dao
abstract class AppSettingDao {

    /** 读一个键；不存在（或 value 为 null 的删除标记）时返回 null */
    @Query("SELECT `value` FROM `app_setting` WHERE `key` = :key LIMIT 1")
    abstract suspend fun get(key: String): String?

    /** 写入或覆盖。"删除标记"用 value=null 表达，语义与同步协议一致 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(setting: AppSettingEntity)

    /** 物理删除（本地清理用；同步语义请用 value=null） */
    @Query("DELETE FROM `app_setting` WHERE `key` = :key")
    abstract suspend fun delete(key: String)

    /** 全量快照，供导出/诊断用 */
    @Query("SELECT * FROM `app_setting` ORDER BY `key`")
    abstract fun observeAll(): Flow<List<AppSettingEntity>>
}
