package com.projectfocus.service.focus.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton





































































@Singleton
open class TodayStatsNotifier @Inject constructor() {

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )

    /** 供 app 模块订阅。只读类型，外部无法 `tryEmit`。 */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()




































    open fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {







        private const val BUFFER = 8
    }
}
