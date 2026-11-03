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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Synchronous compatibility/bootstrap/test API. Production login uses saveCancellable. */
    fun save(account: EmailAccount, expectedIdentity: TokenStore.Snapshot = TokenStore.snapshot()) {
        TokenStore.assertPublicationAllowed()
        runBlocking {
            accountWrites.withLock {
                saveLocked(account, expectedIdentity) { scope -> TokenStore.publishAccountIfCurrent(expectedIdentity, account.token, scope) != null }
            }
        }
    }

    /** Disk stays on IO; cancellation while waiting for the actual identity lease rolls back. */
    suspend fun saveCancellable(account: EmailAccount, expectedIdentity: TokenStore.Snapshot = TokenStore.snapshot()) =
        withContext(Dispatchers.IO) {
            TokenStore.assertPublicationAllowed()
            accountWrites.withLock {
                currentCoroutineContext().ensureActive()
                saveLocked(account, expectedIdentity) { scope ->
                    TokenStore.publishAccountIfCurrentCancellable(expectedIdentity, account.token, scope) != null
                }
            }
        }

    private suspend fun saveLocked(account: EmailAccount, expectedIdentity: TokenStore.Snapshot,
        restoredEncoding: String? = null, publish: suspend (String) -> Boolean) {
        require(account.id.isNotBlank() && account.email.isNotBlank() && account.token.isNotBlank())
        if (TokenStore.snapshot() != expectedIdentity) throw EmailLoginIdentityChangedException()
        val previous = preferences.getString(ACCOUNT_KEY, null)
        val previousBindings = preferences.getString(OWNER_BINDINGS_KEY, null)
        val bindings = EmailAccountOwnerBindings.decode(json, previousBindings)
        previous?.let { encoded ->
            val cached = runCatching { json.decodeFromString(EmailAccount.serializer(), encoded) }.getOrNull()
            if (cached != null && cached.id.isNotBlank() && cached.email.isNotBlank() && cached.token.isNotBlank()) {
                val scope = if (cached.token == expectedIdentity.token) TokenStore.storageScope(expectedIdentity)
                    else EmailAccountOwnerBindings.legacyScope(cached)
                bindings.bind(cached, scope)
            }
        }
        val scope = bindings.bind(account, EmailAccountOwnerBindings.legacyScope(account))
        val encoded = restoredEncoding ?: json.encodeToString(EmailAccount.serializer(), account)
        try {
            if (!preferences.edit().putString(ACCOUNT_KEY, encoded)
                .putString(OWNER_BINDINGS_KEY, bindings.encoded(json)).commit()) throw IOException("Could not save account")
            if (!publish(scope)) throw EmailLoginIdentityChangedException()
        } catch (error: Exception) {
            // Synchronous rollback remains possible when the suspended publication was cancelled.
            try {
                val rollback = preferences.edit()
                if (previous == null) rollback.remove(ACCOUNT_KEY) else rollback.putString(ACCOUNT_KEY, previous)
                if (previousBindings == null) rollback.remove(OWNER_BINDINGS_KEY) else rollback.putString(OWNER_BINDINGS_KEY, previousBindings)
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
                require(account.id.isNotBlank() && account.email.isNotBlank() && account.token.isNotBlank())
                var publication: AccountRestorePublication? = null
                try {
                    saveLocked(account, identity, restoredEncoding = encoded) { scope ->
                        TokenStore.publishAccountIfCurrent(identity, account.token, scope)?.let {
                            publication = AccountRestorePublication(identity, it)
                            true
                        } ?: false
                    }
                } catch (_: EmailLoginIdentityChangedException) { return@withLock null }
                publication
            }
        }
    }

    companion object {
        const val FILE_NAME = "projectfocus_email_account_v1"
        private const val ACCOUNT_KEY = "account"
        private const val OWNER_BINDINGS_KEY = "history_owner_bindings_v1"
        private val accountWrites = Mutex()
    }
}
