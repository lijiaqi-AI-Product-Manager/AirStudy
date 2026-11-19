package com.projectfocus.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.projectfocus.core.database.converter.Converters
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.dao.AppSettingDao
import com.projectfocus.core.database.dao.BlockedAppDao
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.dao.FreeFlightArchiveDao
import com.projectfocus.core.database.dao.FocusCategoryDao
import com.projectfocus.core.database.dao.StampDao
import com.projectfocus.core.database.entity.AchievementEntity
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.database.entity.BlockedAppEntity
import com.projectfocus.core.database.entity.DailyStatEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FlightRecordSceneEntity
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.database.entity.FreeFlightPositionEntity
import com.projectfocus.core.database.entity.FocusCategoryEntity
import com.projectfocus.core.database.entity.RoomEntity
import com.projectfocus.core.database.entity.RoomMessageEntity
import com.projectfocus.core.database.entity.RoomParticipantEntity
import com.projectfocus.core.database.entity.StampEntity


@Database(
    entities = [

        AirportEntity::class,
        FlightHistoryEntity::class,
        FocusCategoryEntity::class,
        BlockedAppEntity::class,
        StampEntity::class,

        RoomEntity::class,
        RoomParticipantEntity::class,
        RoomMessageEntity::class,
        AchievementEntity::class,
        DailyStatEntity::class,
        AppSettingEntity::class,
        FreeFlightArchiveEntity::class,
        FreeFlightPositionEntity::class,
        FlightRecordSceneEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun airportDao(): AirportDao

    abstract fun flightHistoryDao(): FlightHistoryDao

    abstract fun historyReadDao(): com.projectfocus.core.database.dao.HistoryReadDao

    abstract fun flightRecordSceneDao(): com.projectfocus.core.database.dao.FlightRecordSceneDao

    abstract fun historyDeletionDao(): com.projectfocus.core.database.dao.HistoryDeletionDao


    abstract fun freeFlightArchiveDao(): FreeFlightArchiveDao

    abstract fun focusCategoryDao(): FocusCategoryDao

    abstract fun blockedAppDao(): BlockedAppDao

    abstract fun stampDao(): StampDao

    /** v20 引入的 `app_setting` 表；此前没有 DAO，机场库导入的版本标记需要它 */
    abstract fun appSettingDao(): AppSettingDao

    companion object {

        const val NAME: String = "projectfocus.db"


        const val VERSION: Int = 27
    }
}
