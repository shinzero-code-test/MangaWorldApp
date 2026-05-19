package com.exapps.mangaworld.core.firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseRemoteConfigManager @Inject constructor() {
    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()

    private val _disabledSourceIds = MutableStateFlow<Set<String>>(emptySet())
    val disabledSourceIds: StateFlow<Set<String>> = _disabledSourceIds.asStateFlow()

    private val _selectorOverridesJson = MutableStateFlow("{}")
    val selectorOverridesJson: StateFlow<String> = _selectorOverridesJson.asStateFlow()

    private val _remoteAlertMessage = MutableStateFlow("")
    val remoteAlertMessage: StateFlow<String> = _remoteAlertMessage.asStateFlow()

    private val _homeLayoutVariant = MutableStateFlow("default")
    val homeLayoutVariant: StateFlow<String> = _homeLayoutVariant.asStateFlow()

    init {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600)
                .build()
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                "source_olympus_enabled" to true,
                "source_azora_enabled" to true,
                "source_starz_enabled" to true,
                "source_mangasid_enabled" to true,
                "source_meshmanga_enabled" to true,
                "scraper_selector_overrides" to "{}",
                "home_layout_variant" to "default",
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
        val overridesJson = remoteConfig.getString("scraper_selector_overrides")
        _selectorOverridesJson.value = overridesJson
        _homeLayoutVariant.value = remoteConfig.getString("home_layout_variant").ifBlank { "default" }
        _remoteAlertMessage.value = remoteConfig.getString("remote_alert_message")
        RemoteSelectorOverridesStore.replaceAll(parseOverrides(overridesJson))
    }

    private fun parseOverrides(json: String): Map<String, Map<String, String>> = runCatching {
        val root = JSONObject(json)
        buildMap {
            root.keys().forEach { sourceId ->
                val obj = root.optJSONObject(sourceId) ?: return@forEach
                val nested = buildMap<String, String> {
                    obj.keys().forEach { key -> put(key, obj.optString(key)) }
                }
                put(sourceId, nested)
            }
        }
    }.getOrDefault(emptyMap())
}
