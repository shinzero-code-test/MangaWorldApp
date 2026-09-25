package com.exapps.mangaworld.core.source.sync

import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Qualifier for the redirect-disabled client used by plugin distribution. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PluginSyncClient

/**
 * Phase 2B sync bindings. The distribution client follows NO redirects itself —
 * [OkHttpPluginFetcher] re-validates every hop against the manifest allow-list
 * before issuing it (plan §4).
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginSyncModule {

    @Provides
    @Singleton
    @PluginSyncClient
    fun provideSyncClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun providePluginFetcher(
        @PluginSyncClient client: OkHttpClient,
        settingsRepo: SettingsRepository,
        @com.exapps.mangaworld.core.di.IoDispatcher io: kotlinx.coroutines.CoroutineDispatcher
    ): PluginFetcher = OkHttpPluginFetcher(
        callFactory = client,
        io = io,
        allowInsecure = false,
        // Re-resolved per hop AFTER validation — never forwarded across hosts.
        cookieHeader = { url -> resolveCookieForUrl(settingsRepo, url) }
    )

    @Provides
    @Singleton
    fun provideEtagStore(store: PrefsEtagStore): EtagStore = store
}
