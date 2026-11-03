package com.projectfocus.app.auth

import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal class EmailAccountOwnerBindings private constructor(private val bindings: MutableMap<String, String>) {
    fun bind(account: EmailAccount, proposedScope: String): String {
        require(account.id.isNotBlank() && account.email.isNotBlank() && account.token.isNotBlank())
        bindings[account.id]?.let { return it }
        if (!validScope(proposedScope) || bindings.any { it.key != account.id && it.value == proposedScope })
            throw IOException("Account ownership binding is inconsistent")
        bindings[account.id] = proposedScope
        return proposedScope
    }

    fun encoded(json: Json): String = json.encodeToString(codec, bindings.toSortedMap())

    companion object {
        private val codec = MapSerializer(String.serializer(), String.serializer())

        fun decode(json: Json, encoded: String?): EmailAccountOwnerBindings {
            val values = if (encoded == null) emptyMap() else try {
                json.decodeFromString(codec, encoded)
            } catch (error: Exception) { throw IOException("Could not read account ownership bindings", error) }
            if (values.any { it.key.isBlank() || !validScope(it.value) } || values.values.toSet().size != values.size)
                throw IOException("Account ownership bindings are invalid")
            return EmailAccountOwnerBindings(values.toMutableMap())
        }

        fun legacyScope(account: EmailAccount): String = TokenStore.storageScope(TokenStore.Snapshot(account.token, 0L))
        private fun validScope(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
