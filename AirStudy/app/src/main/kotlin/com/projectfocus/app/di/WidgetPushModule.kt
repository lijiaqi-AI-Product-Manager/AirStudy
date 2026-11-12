package com.projectfocus.app.di

import android.content.Context
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.service.focus.session.WidgetMirror
import com.projectfocus.service.focus.session.WidgetPush
import com.projectfocus.widget.glance.WidgetStateStore
import com.projectfocus.widget.glance.TodayWidgetUpdater
import com.projectfocus.widget.snapshot.TodayWidgetRefresher
import com.projectfocus.widget.snapshot.WidgetSnapshot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton




























@Module
@InstallIn(SingletonComponent::class)
object WidgetPushModule {

    @Provides
    @Singleton
    fun provideWidgetPush(
        @ApplicationContext context: Context,
        historyDao: FlightHistoryDao,
    ): WidgetPush = WidgetPushImpl(
        context = context,
        todayRefresher = TodayWidgetRefresher(historyDao) { stats, epochDay ->

            TodayWidgetUpdater.push(context, stats, epochDay)
        },
    )
}







internal class WidgetPushImpl(
    private val context: Context,
    private val todayRefresher: TodayWidgetRefresher,
) : WidgetPush {

    override suspend fun push(mirror: WidgetMirror) {
        WidgetStateStore.pushFlying(context, mirror.toSnapshot())
    }








    override suspend fun onTodayChanged() {
        todayRefresher.refresh()
    }
}







internal fun WidgetMirror.toSnapshot(): WidgetSnapshot = when (this) {
    WidgetMirror.Idle -> WidgetSnapshot.Idle
    is WidgetMirror.Flying -> WidgetSnapshot.Flying(
        remainingMs = remainingMs,
        totalMs = totalMs,
        destinationCode = destinationCode,
        scenarioLabel = scenarioLabel,
    )
    is WidgetMirror.Paused -> WidgetSnapshot.Paused(
        elapsedMs = elapsedMs,
        totalMs = totalMs,
        scenarioLabel = scenarioLabel,
    )
}
