package com.projectfocus.core.network.di

import okhttp3.Interceptor












object NetworkCoreTestFactory {

    fun authInterceptor(
        baseUrl: String,
        token: () -> String? = { TokenStore.peek() },
    ): Interceptor = AuthInterceptor(baseUrl) { token() }
}
