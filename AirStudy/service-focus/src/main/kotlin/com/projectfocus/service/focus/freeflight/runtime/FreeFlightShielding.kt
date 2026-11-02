package com.projectfocus.service.focus.freeflight.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.projectfocus.core.database.dao.BlockedAppDao
import com.projectfocus.core.database.dao.FocusCategoryDao
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.service.focus.R
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseGuard
import com.projectfocus.service.focus.monitor.BlockRule
import com.projectfocus.service.focus.monitor.BlockRuleAssembler
import com.projectfocus.service.focus.monitor.BlockedRow
import com.projectfocus.service.focus.monitor.EnforcementDecision
import com.projectfocus.service.focus.monitor.FocusEnforcer
import com.projectfocus.service.focus.monitor.FocusPermissions
import com.projectfocus.service.focus.monitor.UsageStatsPoller
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class FreeFlightShielding @Inject constructor(
    private val blocked: BlockedAppDao,
    private val categories: FocusCategoryDao,
    private val settings: SettingsRepository,
    private val assembler: BlockRuleAssembler,
    private val owners: FreeFlightRuntimeOwners,
) {
    private data class Rules(val enabled: Boolean, val values: List<BlockRule>)
    fun start(context: Context, scope: CoroutineScope, initial: FreeFlightRuntimeTarget, registry: FreeFlightRuntimeRegistry): List<Job> {
        val rules = AtomicReference<Rules?>()
        val rulesJob = scope.launch {
            try {
                combine(blocked.observeAll(), settings.observe(PrefKey.BlacklistEnabled)) { rows, enabled -> rows to enabled }
                    .collect { (all, enabled) ->
                        val target = registry.resolve(initial.key.sessionId, initial.key.driverId) ?: return@collect
                        val selected = target.archive.shieldingCategoryId?.let { id -> all.filter { it.categoryId == id } } ?: all
                        val labels = categories.getAll().associate { it.id to it.name }
                        rules.set(Rules(enabled, assembler.assemble(selected.map { BlockedRow(it.type, it.packageName, labels[it.categoryId]) })))
                    }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { rules.set(null); registry.shield(initial.key, FreeFlightShieldState.RULES_FAILED, error.message) }
        }
        val pollJob = scope.launch {
            val poller = UsageStatsPoller(context)
            val enforcer = FocusEnforcer()
            var lastRules: Rules? = null
            var lastBlock = 0L
            try {
                while (isActive) {
                    val target = registry.resolve(initial.key.sessionId, initial.key.driverId) ?: break
                    val current = rules.get()
                    try {
                        when {
                            current == null -> { enforcer.enabled = false; registry.shield(initial.key, FreeFlightShieldState.CHECKING) }
                            !current.enabled -> { enforcer.enabled = false; registry.shield(initial.key, FreeFlightShieldState.DISABLED) }
                            !FocusPermissions.hasUsageStats(context) -> { enforcer.enabled = false; registry.shield(initial.key, FreeFlightShieldState.MISSING_USAGE) }
                            !FocusPermissions.hasOverlay(context) -> { enforcer.enabled = false; registry.shield(initial.key, FreeFlightShieldState.MISSING_OVERLAY) }
                            else -> {
                                if (current !== lastRules) { enforcer.configure(context.packageName, current.values); lastRules = current }
                                enforcer.enabled = true
                                enforcer.paused = target.archive.clock.pause != null
                                poller.selfInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                val decision = enforcer.evaluate(poller.poll(context.packageName), SystemClock.elapsedRealtime())
                                if (decision is EnforcementDecision.Block && SystemClock.elapsedRealtime() - lastBlock >= 1_500L) {
                                    val applied = owners.withCurrentOwner(target.key.sessionId, target.key.owner) {
                                        FlightRuntimeLeaseGuard.current().check()
                                        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        try { context.startActivity(launch) }
                                        catch (_: Exception) {
                                            // Passive return-to-app notification; never the old countdown-only give-up Activity.
                                            val pending = PendingIntent.getActivity(context, 2003, launch,
                                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                            val channel = requireNotNull(context.getSystemService(NotificationManager::class.java))
                                            channel.notify(2003, Notification.Builder(context, "projectfocus_freeflight_runtime")
                                                .setSmallIcon(R.drawable.ic_focus_notification)
                                                .setContentTitle(context.getString(R.string.freeflight_runtime_return_title))
                                                .setContentText(assembler.labelOf(decision.packageName)).setContentIntent(pending).setAutoCancel(true).build())
                                        }
                                        true
                                    } ?: false
                                    if (!applied) break
                                    lastBlock = SystemClock.elapsedRealtime()
                                }
                                registry.shield(initial.key, FreeFlightShieldState.ACTIVE)
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { enforcer.enabled = false; registry.shield(initial.key, FreeFlightShieldState.FAILED, error.message) }
                    // Permission/rules failure degrades this loop only. The timer is another Job.
                    delay(500L)
                }
            } finally { poller.reset() }
        }
        return listOf(rulesJob, pollJob)
    }
}
