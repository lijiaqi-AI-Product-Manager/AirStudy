package com.projectfocus.app.testing

import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.di.ProcessWorkScopes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.robolectric.util.ReflectionHelpers

/**
 * End the real per-test Hilt application's owned work before Robolectric resets
 * its SDK-specific native SQLite pointer registry. No replacement App/DB/seed,
 * exception suppression, global-executor shutdown or production test branch.
 */
internal fun shutdownRealApplicationAfterTest(app: ProjectFocusApp) {
    val startup = app.admissionApplicationScope
    val widgets = ReflectionHelpers.getField<CoroutineScope>(app.todayWidgetBridge, "appScope")
    // Bound against the actual Room runtime's public JVM method in the local

    val room = ReflectionHelpers.callInstanceMethod<CoroutineScope>(app.database, "getCoroutineScope")
    val actual = EntryPointAccessors.fromApplication(app, ProcessWorkScopes::class.java)
    val jobs = listOf(startup, widgets, actual.network()).map { checkNotNull(it.coroutineContext[Job]) }.distinct()
    val settingsJob = checkNotNull(actual.settings().coroutineContext[Job])
    val roomJob = checkNotNull(room.coroutineContext[Job])
    var waitingFor = "cancellation"
    try { runBlocking(Dispatchers.IO) {
        // Native initialization can still be unwinding during the first Application shutdown.
        withTimeout(60_000) {
            jobs.forEach { it.cancel() }
            jobs.forEachIndexed { index, job -> waitingFor = listOf("application", "widgets", "network")[index];job.join() }
            // Finish producers while settings remain available to their real in-flight cleanup.
            settingsJob.cancel()
            waitingFor = "settings"
            settingsJob.join()
            // Quiesce Room BEFORE closing its actual SQLite connections or resetting SDK state.
            roomJob.cancel()
            waitingFor = "Room"
            roomJob.join()
            app.database.close()
        }
    } } catch (error: TimeoutCancellationException) {
        System.err.println("Actual Application cleanup timed out at $waitingFor; ownedJobs=" +
            (jobs + settingsJob + roomJob).map { job -> "$job children=${job.children.take(8).toList()}" })
        Thread.getAllStackTraces().entries.filter { (thread, _) ->
            thread.name.startsWith("SDK ") || thread.name.startsWith("DefaultDispatcher") || thread.name.contains("arch_disk_io")
        }.take(12).forEach { (thread, stack) ->
            System.err.println("Actual cleanup thread=${thread.name} state=${thread.state}\n" + stack.take(18).joinToString("\n"))
        }
        throw error
    }
    check(jobs.all { it.isCompleted } && settingsJob.isCompleted && roomJob.isCompleted)
    check(!app.database.isOpen)
    app.releaseOwnedDebugLogging()
}
