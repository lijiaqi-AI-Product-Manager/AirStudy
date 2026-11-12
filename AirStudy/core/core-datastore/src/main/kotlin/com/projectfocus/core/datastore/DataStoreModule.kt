package com.projectfocus.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    private const val SETTINGS_FILE = "pf_settings"

    /** DataStore 专用 IO scope。见类注释最后一段。 */
    @Provides
    @Singleton
    @DataStoreIoScope
    fun provideIoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @DataStoreIoScope scope: CoroutineScope,
    ): DataStore<Preferences> {
        val file = context.preferencesDataStoreFile(SETTINGS_FILE)
        return createWithRecovery(file, scope)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(store: DataStore<Preferences>): SettingsRepository =
        SettingsRepository(store)













    internal fun createWithRecovery(
        file: File,
        scope: CoroutineScope,
    ): DataStore<Preferences> {
        val handler = ReplaceFileCorruptionHandler { ex ->
            Timber.e(ex, "偏好文件损坏，已重置为默认值：%s", file.absolutePath)
            runCatching { backupCorruptFile(file) }
                .onFailure { Timber.w(it, "备份损坏文件失败") }
            emptyPreferences()
        }
        return PreferenceDataStoreFactory.create(
            corruptionHandler = handler,
            scope = scope,
            produceFile = { file },
        )
    }

    private fun backupCorruptFile(file: File) {
        if (!file.exists()) return
        val dir = file.parentFile ?: return
        // 清掉更早的备份，只留一份
        dir.listFiles { f -> f.name.startsWith("${file.name}.corrupt.") }
            ?.forEach { runCatching { it.delete() } }

        val backup = File(dir, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (file.renameTo(backup)) {
            Timber.w("已备份损坏的偏好文件到 %s", backup.name)
        }
    }
}








@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DataStoreIoScope
