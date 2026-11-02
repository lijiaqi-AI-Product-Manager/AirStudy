package com.projectfocus.app.freeflight

import android.content.Context
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.core.model.flight.freeflight.CompletedFreeFlight
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.FreeFlightStartAcknowledgement
import com.projectfocus.service.focus.freeflight.FreeFlightStartIdentity
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FreeFlightCompletionNotice(
    val result:CompletedFreeFlight,
    val owner:FreeFlightOwnerToken,
    val originSaved:Boolean,
)

@Singleton
class FreeFlightCompletionCoordinator @Inject constructor(
    @ApplicationContext private val context:Context,
    private val repository:FreeFlightSessionRepository,
    private val owners:SharedFlightRuntimeOwnership,
    private val runtime:FreeFlightRuntimeRegistry,
    @FreeFlightApplicationScope private val scope:CoroutineScope,
) {
    private data class Identity(val sessionId:String,val owner:FreeFlightOwnerToken)
    private data class Selection(val archive:FreeFlightArchive,val owner:FreeFlightOwnerToken)
    private val mutableNotice=MutableStateFlow<FreeFlightCompletionNotice?>(null)
    val notice=mutableNotice.asStateFlow()
    private val retry=MutableStateFlow(0L)
    private val advanced=ConcurrentHashMap.newKeySet<Identity>()
    private val dismissed=ConcurrentHashMap.newKeySet<Identity>()
    private val finishLock=Any()
    private var finishing:Pair<FreeFlightCompletionNotice,Deferred<Boolean>>?=null
    private val finishMutex=Mutex()
    @Volatile private var observedHost:FreeFlightOwnerToken?=null

    init {
        scope.launch(Dispatchers.IO) {
            combine(repository.state,owners.currentHosts,retry) { archive,owner,_ ->
                observedHost=owner
                if (archive?.status==FreeFlightStatus.COMPLETED && owner!=null &&
                    owner.ownerScope==archive.ownerScope && repository.recordedOwner(archive.sessionId)==owner)
                    Selection(archive,owner) else null
            }.collectLatest { selected ->
                if (selected==null) { mutableNotice.value=null;return@collectLatest }
                val identity=Identity(selected.archive.sessionId,selected.owner)
                if (identity in dismissed) { mutableNotice.value=null;return@collectLatest }
                if (mutableNotice.value?.let { it.result.sessionId!=identity.sessionId || it.owner!=identity.owner }==true)
                    mutableNotice.value=null
                val result=CompletedFreeFlight.from(selected.archive)
                var originSaved=identity in advanced
                if (!originSaved) {
                    try {
                        val account=owners.captureHostAccount(selected.owner) ?: return@collectLatest
                        originSaved=owners.withPreparationOwner(result.sessionId,selected.owner) {
                            if (repository.state.value!=selected.archive || repository.recordedOwner(result.sessionId)!=selected.owner)
                                return@withPreparationOwner false
                            withContext(Dispatchers.IO) {
                                HomeOriginSelection.advance(context.getSharedPreferences(HomeMapStyleSelection.FILE_NAME,Context.MODE_PRIVATE),
                                    result.destination,account.account)
                            }
                            true
                        }==true
                        if (originSaved) advanced.add(identity)
                    } catch (error:CancellationException) { throw error }
                    catch (_:Exception) { originSaved=false }
                }
                if (repository.state.value==selected.archive && owners.isHostCurrent(selected.owner) && identity !in dismissed) {
                    val previous=mutableNotice.value
                    if (previous==null || previous.result.archive!=selected.archive || previous.owner!=selected.owner || previous.originSaved!=originSaved)
                        mutableNotice.value=FreeFlightCompletionNotice(result,selected.owner,originSaved)
                }
            }
        }
    }

    fun retryOrigin() { retry.value=retry.value+1L }

    fun blocksPreparation():Boolean {
        val archive=repository.state.value ?: return false
        val owner=repository.recordedOwner(archive.sessionId) ?: return false
        return archive.status==FreeFlightStatus.COMPLETED && owner==observedHost &&
            archive.ownerScope==TokenStore.storageScope() && Identity(archive.sessionId,owner) !in dismissed
    }

    suspend fun finish(expected:FreeFlightCompletionNotice):Boolean {
        val deferred=synchronized(finishLock) {
            if (mutableNotice.value!==expected || !expected.originSaved) return false
            finishing?.takeIf { it.first===expected }?.second ?: scope.async(Dispatchers.IO,start=CoroutineStart.LAZY) {
                finishMutex.withLock {
                    if (mutableNotice.value!==expected || !owners.isHostCurrent(expected.owner)) return@withLock false
                    val result=expected.result
                    val identity=FreeFlightStartIdentity(expected.owner,result.sessionId)
                    if (repository.startAcknowledgements.value[identity] !is FreeFlightStartAcknowledgement.Recorded)
                        repository.retryStartAcknowledgement(expected.owner,result.sessionId)
                    repository.synchronizeRuntime(expected.owner,result.sessionId)
                    val key=runtime.observation.value.key
                    val ready=key!=null && key.sessionId==result.sessionId && key.owner==expected.owner && runtime.isStopped(key) &&
                        owners.retainedOwner(result.sessionId)==null &&
                        repository.startAcknowledgements.value[identity] is FreeFlightStartAcknowledgement.Recorded
                    if (ready && mutableNotice.value===expected && owners.isHostCurrent(expected.owner)) {
                        dismissed.add(Identity(result.sessionId,expected.owner))
                        mutableNotice.compareAndSet(expected,null)
                        true
                    } else false
                }
            }.also { finishing=expected to it }
        }
        deferred.start()
        return try { deferred.await() }
        finally { if (deferred.isCompleted) synchronized(finishLock) { if (finishing?.second===deferred) finishing=null } }
    }
}
