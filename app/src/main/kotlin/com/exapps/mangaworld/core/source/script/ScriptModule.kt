package com.exapps.mangaworld.core.source.script

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.asCoroutineDispatcher

/** Dedicated pool for script execution — never the shared IO pool (plan §5). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ScriptDispatcher

/**
 * Phase 3 script bindings.
 *
 * - One shared [ScriptContextFactory] (thread-safe; contexts stay per-call).
 * - One daemon pool: a wedged script thread can never outlive the process, and
 *   leaked threads cannot keep the JVM alive in tests.
 * - Logging routes to logcat with the `script:<id>` tag; tests inject a recorder.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScriptModule {

    @Provides
    @Singleton
    fun provideScriptContextFactory(): ScriptContextFactory = ScriptContextFactory()

    @Provides
    @Singleton
    @ScriptDispatcher
    fun provideScriptDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        Executors.newFixedThreadPool(ScriptContract.SCRIPT_THREADS) { runnable ->
            Thread(runnable, "script").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    @Provides
    @Singleton
    fun provideScriptLogger(): ScriptLogger = ScriptLogger { level, tag, msg ->
        when (level) {
            "warn" -> Log.w(tag, msg)
            "error" -> Log.e(tag, msg)
            "debug" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
    }

    @Provides
    @Singleton
    fun provideScriptFetcher(impl: OkHttpScriptFetcher): ScriptFetcher = impl

    @Provides
    @Singleton
    fun provideScriptCookieJar(impl: SettingsScriptCookieJar): ScriptCookieJar = impl
}
