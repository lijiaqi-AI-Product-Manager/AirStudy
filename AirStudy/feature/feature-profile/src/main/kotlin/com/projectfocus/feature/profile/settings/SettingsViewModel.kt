package com.projectfocus.feature.profile.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.feature.profile.spec.PrefItemSpec
import com.projectfocus.feature.profile.spec.PrefSpecs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject



















































@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val keys: List<PrefKey<*>> = PrefSpecs.allKeys()







    private val stored: Flow<Map<String, Any?>> = settings
        .observeAllKeys(keys)
        .catch { e ->
            Timber.e(e, "读取设置失败，回退为默认值")
            emit(keys.associate { it.name to it.default })
        }

    /** 乐观更新的暂存区。key = PrefKey.name */
    private data class PendingValue(val version: Long, val value: Any?)
    private val writeVersion = AtomicLong()
    private val pending = MutableStateFlow<Map<String, PendingValue>>(emptyMap())

    /** 界面读这个：stored 上叠 pending */
    val values: StateFlow<Map<String, Any?>> = combine(stored, pending) { s, p ->
        if (p.isEmpty()) s else s + p.mapValues { it.value.value }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = keys.associate { it.name to it.default },
    )

    /** 一次性提示（Toast 级），消费后置 null */
    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    // ==================================================================
    // 读
    // ==================================================================









    @Suppress("UNCHECKED_CAST")
    fun <T> valueOf(key: PrefKey<T>): T = (values.value[key.name] as? T) ?: key.default

    /** 布尔项，给渲染层用，省一次强转 */
    fun isOn(key: PrefKey<Boolean>): Boolean = valueOf(key)












    fun isEnabled(item: PrefItemSpec, values: Map<String, Any?> = this.values.value): Boolean {
        if (!item.interactive) return false
        val gate = item.enabledWhen ?: return true
        return (values[gate.name] as? Boolean) ?: false
    }

    // ==================================================================
    // 写
    // ==================================================================

    fun <T> set(key: PrefKey<T>, value: T) {
        val version = writeVersion.incrementAndGet()
        pending.update { it + (key.name to PendingValue(version, value)) }
        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                settings.write(key, value)
                Timber.v("设置已保存：%s = %s", key.id, value)
            } catch (e: CancellationException) {

                //   写入可能已经落盘，回滚反而制造不一致。
                Timber.d(e, "设置写入被取消：%s", key.id)
                throw e
            } catch (e: Exception) {
                Timber.w(e, "设置写入失败：%s", key.id)
                _message.value = SettingsMessage.SaveFailed
            } finally {

                pending.update { current ->
                    if (current[key.name]?.version == version) current - key.name else current
                }
            }
        }
    }








    fun resetToDefaults() {
        val version = writeVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                settings.resetToDefaults()

                pending.update { current -> current.filterValues { it.version > version } }
                _message.value = SettingsMessage.ResetDone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "恢复默认失败")
                _message.value = SettingsMessage.ResetFailed
            }
        }
    }
}

/** 设置页的一次性提示 */
sealed interface SettingsMessage {
    data object SaveFailed : SettingsMessage
    data object ResetDone : SettingsMessage
    data object ResetFailed : SettingsMessage
}
