package com.projectfocus.core.network.content

import com.projectfocus.core.network.api.FocusApi
import com.projectfocus.core.network.di.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Own-account adapter. The original a1 resolver's identity semantics remain unproven. */
@Singleton
class SceneAccessResolver @Inject constructor(private val api: FocusApi) {
    suspend fun maximumEnabledScenes(): Int {
        val token = TokenStore.peek()?.takeIf(String::isNotBlank) ?: return FREE_LIMIT
        val response = api.getMe()
        if (TokenStore.peek() != token) throw CancellationException("Account changed during scene access lookup")
        check(response.error == null && response.success != false) { "Scene access lookup failed" }
        val profile = checkNotNull(response.data) { "Scene access profile missing" }
        check(profile.id.isNotBlank()) { "Scene access identity missing" }
        return if (profile.isPremium) PREMIUM_LIMIT else FREE_LIMIT
    }

    companion object {
        // Original ARSC free_user_max_focus_scenes / premium_user_max_focus_scenes.
        const val FREE_LIMIT = 5
        const val PREMIUM_LIMIT = 127
    }
}
