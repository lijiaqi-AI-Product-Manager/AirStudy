package com.projectfocus.feature.sound


object AmbientPlaybackRegistry {

    private var active: AmbientPlaybackHandle? = null




    fun play(handle: AmbientPlaybackHandle, action: () -> Boolean): Boolean {
        val previous = active
        if (previous != null && previous !== handle) {
            previous.stop()
        }
        val ok = action()
        active = if (ok) handle else null
        return ok
    }

    fun stop(handle: AmbientPlaybackHandle) {
        if (active === handle) active = null
        handle.stop()
    }

    fun release(handle: AmbientPlaybackHandle) {
        if (active === handle) active = null
        handle.release()
    }

    /** 测试用：清空登记，不影响播放器自身。 */
    fun clearForTest() {
        active = null
    }

    fun isActiveForTest(handle: AmbientPlaybackHandle): Boolean = active === handle
}

/** 播放器可被登记表管理的最小接口；[AmbientSoundPlayer] 实现它。 */
interface AmbientPlaybackHandle {
    fun stop()
    fun release()
}
