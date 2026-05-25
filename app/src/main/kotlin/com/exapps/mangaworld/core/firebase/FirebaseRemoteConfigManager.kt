package com.exapps.mangaworld.core.firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ScraperRuntimeConfig(
    val connectTimeoutSeconds: Int = 30,
    val readTimeoutSeconds: Int = 30,
    val writeTimeoutSeconds: Int = 15,
    val retryCount: Int = 1
)

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

    private val _bannedKeywords = MutableStateFlow<Set<String>>(emptySet())
    val bannedKeywords: StateFlow<Set<String>> = _bannedKeywords.asStateFlow()

    private val _scraperRuntimeConfig = MutableStateFlow(ScraperRuntimeConfig())
    val scraperRuntimeConfig: StateFlow<ScraperRuntimeConfig> = _scraperRuntimeConfig.asStateFlow()

    private val _mlTranslationEnabled = MutableStateFlow(true)
    val mlTranslationEnabled: StateFlow<Boolean> = _mlTranslationEnabled.asStateFlow()

    private val _mlSmartReplyEnabled = MutableStateFlow(true)
    val mlSmartReplyEnabled: StateFlow<Boolean> = _mlSmartReplyEnabled.asStateFlow()

    private val _mlCoverTaggingEnabled = MutableStateFlow(true)
    val mlCoverTaggingEnabled: StateFlow<Boolean> = _mlCoverTaggingEnabled.asStateFlow()

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
                "scraper_connect_timeout_seconds" to 30,
                "scraper_read_timeout_seconds" to 30,
                "scraper_write_timeout_seconds" to 15,
                "scraper_retry_count" to 1,
                "home_layout_variant" to "default",
                "community_banned_keywords" to "",
                "remote_alert_message" to "",
                "ml_translation_enabled" to true,
                "ml_smart_reply_enabled" to true,
                "ml_cover_tagging_enabled" to true
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

    fun currentScraperRuntimeConfig(): ScraperRuntimeConfig = scraperRuntimeConfig.value

    fun isMlTranslationEnabled(): Boolean = mlTranslationEnabled.value

    fun isMlSmartReplyEnabled(): Boolean = mlSmartReplyEnabled.value

    fun isMlCoverTaggingEnabled(): Boolean = mlCoverTaggingEnabled.value

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
        _bannedKeywords.value = remoteConfig.getString("community_banned_keywords")
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        _remoteAlertMessage.value = remoteConfig.getString("remote_alert_message")
        _scraperRuntimeConfig.value = ScraperRuntimeConfig(
            connectTimeoutSeconds = remoteConfig.getLong("scraper_connect_timeout_seconds").toInt().coerceIn(5, 90),
            readTimeoutSeconds = remoteConfig.getLong("scraper_read_timeout_seconds").toInt().coerceIn(5, 120),
            writeTimeoutSeconds = remoteConfig.getLong("scraper_write_timeout_seconds").toInt().coerceIn(5, 90),
            retryCount = remoteConfig.getLong("scraper_retry_count").toInt().coerceIn(0, 3)
        )
        _mlTranslationEnabled.value = remoteConfig.getBoolean("ml_translation_enabled")
        _mlSmartReplyEnabled.value = remoteConfig.getBoolean("ml_smart_reply_enabled")
        _mlCoverTaggingEnabled.value = remoteConfig.getBoolean("ml_cover_tagging_enabled")
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
