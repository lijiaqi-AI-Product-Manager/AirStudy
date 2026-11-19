package com.projectfocus.feature.record

import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.*
import com.projectfocus.service.focus.session.TodayStatsNotifier

internal fun testHistoryDeletion(dao: FlightHistoryDao, notifier: TodayStatsNotifier) = object : RecordDeletionOperations {
    override suspend fun captureCountdown(id: String): RecordDeletionTarget? {
        val identity = TokenStore.snapshot()
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        val row = dao.findById(id) ?: return null
        return RecordDeletionTarget(account, RecordedFlight.Countdown(row))
    }
    override suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult {
        if (!target.account.isCurrent()) return RecordDeletionResult.OBSOLETE
        val expected = (target.record as RecordedFlight.Countdown).record
        val current = dao.findById(expected.id) ?: return RecordDeletionResult.MISSING
        if (current != expected) return RecordDeletionResult.CHANGED
        if (current.status == "ONGOING") return RecordDeletionResult.PROTECTED
        dao.deleteById(expected.id)
        notifier.notifyChanged()
        return RecordDeletionResult.DELETED
    }
}
