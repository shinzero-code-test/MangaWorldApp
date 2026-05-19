package com.exapps.mangaworld.core.firebase

import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseRemoteConfigManager @Inject constructor() {
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    private val _disabledSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val disabledSourceIds: StateFlow<Set<String>> = _disabledSourceIds.asStateFlow()

    private val _selectorOverridesJson = MutableStateFlow("{}")
    val selectorOverridesJson: StateFlow<String> = _selectorOverridesJson.asStateFlow()

    private val _remoteAlertMessage = MutableStateFlow("")
    val remoteAlertMessage: StateFlow<String> = _remoteAlertMessage.asStateFlow()

    init {
        remoteConfig.setConfigSettingsAsync(
            remoteConfigSettings {
                minimumFetchIntervalInSeconds = 3600
            }
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                "source_olympus_enabled" to true,
                "source_azora_enabled" to true,
                "source_starz_enabled" to true,
                "source_mangasid_enabled" to true,
                "source_meshmanga_enabled" to true,
                "scraper_selector_overrides" to "{}",
                "remote_alert_message" to ""
            )
        )
        applyState()
    }

    suspend fun refresh() {
        runCatching {
            remoteConfig.fetchAndActivate().await()
            applyState()
        }
    }

    private fun applyState() {
        val disabled = buildSet {
            if (!remoteConfig.getBoolean("source_olympus_enabled")) add("olympus")
            if (!remoteConfig.getBoolean("source_azora_enabled")) add("azora")
            if (!remoteConfig.getBoolean("source_starz_enabled")) add("starz")
            if (!remoteConfig.getBoolean("source_mangasid_enabled")) add("mangasid")
            if (!remoteConfig.getBoolean("source_meshmanga_enabled")) add("meshmanga")
        }
        _disabledSourceIds.value = disabled
        _selectorOverridesJson.value = remoteConfig.getString("scraper_selector_overrides")
        _remoteAlertMessage.value = remoteConfig.getString("remote_alert_message")
    }
}
