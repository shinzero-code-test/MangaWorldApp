package com.exapps.mangaworld.core.source.plugins

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase 2A bindings: index-store port → Room implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginStoreModule {

    @Binds @Singleton
    abstract fun bindPluginIndexStore(impl: RoomPluginIndexStore): PluginIndexStore

    /** Phase 3 health counters port → SharedPreferences implementation. */
    @Binds @Singleton
    abstract fun bindHealthStore(impl: PrefsHealthStore): HealthStore

    /** Phase 3.5 Gate 0 fleet-telemetry port → Crashlytics implementation. */
    @Binds @Singleton
    abstract fun bindPluginTelemetry(impl: FirebasePluginTelemetry): PluginTelemetry
}
