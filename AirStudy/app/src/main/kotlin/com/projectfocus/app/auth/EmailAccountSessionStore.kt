package com.projectfocus.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Own account persistence; one preference transaction domain for sync bootstrap and async login. */
@Singleton
class EmailAccountSessionStore internal constructor(private val preferences: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))
    private val accountWrites = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Synchronous compatibility/bootstrap/test API. Production login uses saveCancellable. */
    fun save(account: EmailAccount, expectedIdentity: TokenStore.Snapshot = TokenStore.snapshot()) {
        TokenStore.assertPublicationAllowed()
        runBlocking {
            accountWrites.withLock {
                saveLocked(account, expectedIdentity) { TokenStore.publishIfCurrent(expectedIdentity, account.token) != null }
            }
        }
    }

    /** Disk stays on IO; cancellation while waiting for the actual identity lease rolls back. */
    suspend fun saveCancellable(account: EmailAccount, expectedIdentity: TokenStore.Snapshot = TokenStore.snapshot()) =
        withContext(Dispatchers.IO) {
            TokenStore.assertPublicationAllowed()
            accountWrites.withLock {
                currentCoroutineContext().ensureActive()
                saveLocked(account, expectedIdentity) {
                    TokenStore.publishIfCurrentCancellable(expectedIdentity, account.token) != null
                }
            }
        }

    private suspend fun saveLocked(account: EmailAccount, expectedIdentity: TokenStore.Snapshot, publish: suspend () -> Boolean) {
        require(account.id.isNotEmpty() && account.email.isNotEmpty() && account.token.isNotEmpty())
        if (TokenStore.snapshot() != expectedIdentity) throw EmailLoginIdentityChangedException()
        val previous = preferences.getString(ACCOUNT_KEY, null)
        val encoded = json.encodeToString(EmailAccount.serializer(), account)
        try {
            if (!preferences.edit().putString(ACCOUNT_KEY, encoded).commit()) throw IOException("Could not save account")
            if (!publish()) throw EmailLoginIdentityChangedException()
        } catch (error: Exception) {
            // Synchronous rollback remains possible when the suspended publication was cancelled.
            try {
                val rollback = preferences.edit()
                if (previous == null) rollback.remove(ACCOUNT_KEY) else rollback.putString(ACCOUNT_KEY, previous)
                rollback.commit()
            } catch (_: Exception) { /* Preserve the original failure; never publish this account. */ }
            throw error
        }
    }

    /** Actual Application bootstrap calls this from its IO task. */
    fun restoreIfAnonymous(): AccountRestorePublication? {
        TokenStore.assertPublicationAllowed()
        return runBlocking {
            accountWrites.withLock {
                val identity = TokenStore.snapshot()
                if (!identity.token.isNullOrEmpty()) return@withLock null
                val encoded = preferences.getString(ACCOUNT_KEY, null) ?: return@withLock null
                val account = json.decodeFromString(EmailAccount.serializer(), encoded)
                require(account.id.isNotEmpty() && account.email.isNotEmpty() && account.token.isNotEmpty())
                TokenStore.publishIfCurrent(identity, account.token)?.let { AccountRestorePublication(identity, it) }
            }
        }
    }

    companion object {
        const val FILE_NAME = "projectfocus_email_account_v1"
        private const val ACCOUNT_KEY = "account"
    }
}
