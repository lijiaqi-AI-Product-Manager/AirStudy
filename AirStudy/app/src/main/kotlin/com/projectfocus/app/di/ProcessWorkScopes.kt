package com.projectfocus.app.di

import com.projectfocus.core.datastore.DataStoreIoScope
import com.projectfocus.core.network.di.NetworkModule.NetworkScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/** Actual process-owned workers, separate from screen/service waiting Jobs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProcessWorkScopes {
    @NetworkScope fun network(): CoroutineScope
    @DataStoreIoScope fun settings(): CoroutineScope
}
