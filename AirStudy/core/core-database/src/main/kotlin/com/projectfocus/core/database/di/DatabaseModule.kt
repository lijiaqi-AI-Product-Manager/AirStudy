package com.projectfocus.core.database.di

import android.content.Context
import androidx.room.Room
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.dao.AppSettingDao
import com.projectfocus.core.database.dao.BlockedAppDao
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.dao.FreeFlightArchiveDao
import com.projectfocus.core.database.dao.FocusCategoryDao
import com.projectfocus.core.database.dao.StampDao
import com.projectfocus.core.database.migrations.Migrations
import com.projectfocus.core.database.seed.SeedCallback
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // 让 Room 在打开库时校验"代码里的 schema"与"库里的 schema"一致。
            // 这是防止漏写迁移的第二道闸（第一道是 MigrationRegistryTest）。
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

            .addMigrations(*Migrations.ALL.toTypedArray())
            // 建库后灌 9 个内置场景；每次打开补齐缺失的内置场景
            .addCallback(SeedCallback.create())
            .addCallback(SeedCallback.open())



        //   宁可让误用在开发期直接抛 IllegalStateException，
        //   也不要上线后在用户机上变成一次 ANR。
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideAirportDao(db: AppDatabase): AirportDao = db.airportDao()

    @Provides
    @Singleton
    fun provideFlightHistoryDao(db: AppDatabase): FlightHistoryDao = db.flightHistoryDao()

    @Provides
    @Singleton
    fun provideFreeFlightArchiveDao(db: AppDatabase): FreeFlightArchiveDao = db.freeFlightArchiveDao()

    @Provides
    @Singleton
    fun provideFocusCategoryDao(db: AppDatabase): FocusCategoryDao = db.focusCategoryDao()

    @Provides
    @Singleton
    fun provideBlockedAppDao(db: AppDatabase): BlockedAppDao = db.blockedAppDao()

    @Provides
    @Singleton
    fun provideStampDao(db: AppDatabase): StampDao = db.stampDao()

    @Provides
    @Singleton
    fun provideAppSettingDao(db: AppDatabase): AppSettingDao = db.appSettingDao()
}
















@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DatabaseDispatcher

/**
 * 数据库 IO 线程池大小。
 *
 * 抽成常量而不是散落的数字，方便日后按实测调（`PerformanceSmokeTest` 里
 * 会打日志观测排队时长）。
 */
object DatabaseThreading {
    const val POOL_SIZE: Int = 2
    const val KEEP_ALIVE_SECONDS: Long = 60L
}
