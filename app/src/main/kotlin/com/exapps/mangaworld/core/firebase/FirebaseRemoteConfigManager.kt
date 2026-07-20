package com.exapps.mangaworld.core.firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteConfig"

data class ScraperRuntimeConfig(
    val connectTimeoutSeconds: Int = 30,
    val readTimeoutSeconds: Int = 30,
    val writeTimeoutSeconds: Int = 15,
    val retryCount: Int = 1
)

@Singleton
class FirebaseRemoteConfigManager @Inject constructor() {
    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Engagement tier thresholds (configurable via Remote Config)
    private val _engagementWarmingMs = MutableStateFlow(900_000L)
    val engagementWarmingMs: StateFlow<Long> = _engagementWarmingMs.asStateFlow()
    private val _engagementActiveMs = MutableStateFlow(3_600_000L)
    val engagementActiveMs: StateFlow<Long> = _engagementActiveMs.asStateFlow()
    private val _engagementAvidMs = MutableStateFlow(36_000_000L)
    val engagementAvidMs: StateFlow<Long> = _engagementAvidMs.asStateFlow()

    init {
        scope.launch {
            // Await both async operations so applyState() reads correct defaults
            remoteConfig.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(3600)
                    .build()
            ).await()
            remoteConfig.setDefaultsAsync(
                mapOf(
                    "source_olympus_enabled" to true,
                    "source_azora_enabled" to true,
                    "source_starz_enabled" to true,
                    "source_mangasid_enabled" to true,
                    "source_meshmanga_enabled" to true,
                    "source_asq3_enabled" to true,
                    "source_lekmanga_enabled" to true,
                    "source_lekmangaonline_enabled" to true,
                    "source_likemanga_enabled" to true,
                    "source_linkmanga_enabled" to true,
                    "source_mangaleko_enabled" to true,
                    "source_mangalionz_enabled" to true,
                    "source_areascans_enabled" to true,
                    "source_hijala_enabled" to true,
                    "source_lavascans_enabled" to true,
                    "source_stellarsaber_enabled" to true,
                    "source_procomic_enabled" to true,
                    "source_rockmanga_enabled" to true,
                    "scraper_selector_overrides" to "{}",
                    "scraper_connect_timeout_seconds" to 30,
                    "scraper_read_timeout_seconds" to 30,
                    "scraper_write_timeout_seconds" to 15,
                    "scraper_retry_count" to 1,
                    "home_layout_variant" to "default",
                    "community_banned_keywords" to "",
                    "remote_alert_message" to "",
                    "engagement_tier_warming_ms" to 900000L,
                    "engagement_tier_active_ms" to 3600000L,
                    "engagement_tier_avid_ms" to 36000000L
                )
            ).await()
            applyState()
        }
    }

    suspend fun refresh() {
        runCatching {
            remoteConfig.fetchAndActivate().await()
            applyState()
        }.onFailure { e ->
            Log.w(TAG, "Remote config fetch failed: ${e.message}", e)
        }
    }

    fun currentScraperRuntimeConfig(): ScraperRuntimeConfig = scraperRuntimeConfig.value

    private fun applyState() {
        val disabled = buildSet {
            if (!remoteConfig.getBoolean("source_olympus_enabled")) add("olympus")
            if (!remoteConfig.getBoolean("source_azora_enabled")) add("azora")
            if (!remoteConfig.getBoolean("source_starz_enabled")) add("starz")
            if (!remoteConfig.getBoolean("source_mangasid_enabled")) add("mangasid")
            if (!remoteConfig.getBoolean("source_meshmanga_enabled")) add("meshmanga")
            if (!remoteConfig.getBoolean("source_asq3_enabled")) add("asq3")
            if (!remoteConfig.getBoolean("source_lekmanga_enabled")) add("lekmanga")
            if (!remoteConfig.getBoolean("source_lekmangaonline_enabled")) add("lekmangaonline")
            if (!remoteConfig.getBoolean("source_likemanga_enabled")) add("likemanga")
            if (!remoteConfig.getBoolean("source_linkmanga_enabled")) add("linkmanga")
            if (!remoteConfig.getBoolean("source_mangaleko_enabled")) add("mangaleko")
            if (!remoteConfig.getBoolean("source_mangalionz_enabled")) add("mangalionz")
            if (!remoteConfig.getBoolean("source_areascans_enabled")) add("areascans")
            if (!remoteConfig.getBoolean("source_hijala_enabled")) add("hijala")
            if (!remoteConfig.getBoolean("source_lavascans_enabled")) add("lavascans")
            if (!remoteConfig.getBoolean("source_stellarsaber_enabled")) add("stellarsaber")
            if (!remoteConfig.getBoolean("source_procomic_enabled")) add("procomic")
            if (!remoteConfig.getBoolean("source_rockmanga_enabled")) add("rockmanga")
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
        RemoteSelectorOverridesStore.replaceAll(parseOverrides(overridesJson))
        _engagementWarmingMs.value = remoteConfig.getLong("engagement_tier_warming_ms").coerceAtLeast(0)
        _engagementActiveMs.value = remoteConfig.getLong("engagement_tier_active_ms").coerceAtLeast(0)
        _engagementAvidMs.value = remoteConfig.getLong("engagement_tier_avid_ms").coerceAtLeast(0)
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
