package com.exapps.mangaworld.core.firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.exapps.mangaworld.domain.model.SourceDomainOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import com.exapps.mangaworld.core.di.IoDispatcher
import javax.inject.Singleton

private const val TAG = "RemoteConfig"

data class ScraperRuntimeConfig(
    val connectTimeoutSeconds: Int = 30,
    val readTimeoutSeconds: Int = 30,
    val writeTimeoutSeconds: Int = 15,
    val retryCount: Int = 1
)

@Singleton
class FirebaseRemoteConfigManager @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    /**
     * Provider (not direct) — the registry's plugins need SettingsRepository,
     * which needs this manager. Lazy lookup breaks the cycle.
     */
    private val registryProvider: javax.inject.Provider<com.exapps.mangaworld.core.source.plugins.SourceRegistry>
) {
    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

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

    /**
     * Phase 2A trust transport: rotation announcements (cross-signed by a
     * currently-trusted key — Remote Config alone can never introduce a key).
     * Raw JSON; merged with the APK-pinned set via [PluginTrust.resolveTrustedKeys].
     */
    private val _pluginRotationJson = MutableStateFlow("")
    val pluginRotationJson: StateFlow<String> = _pluginRotationJson.asStateFlow()

    /**
     * Phase 2B kill-switch transport (fail-closed direction only — installation
     * still requires signatures either way). Raw JSON for [PluginKillSwitch].
     */
    private val _pluginKillSwitchJson = MutableStateFlow("")
    val pluginKillSwitchJson: StateFlow<String> = _pluginKillSwitchJson.asStateFlow()

    fun pluginKillSwitchJson(): String? = _pluginKillSwitchJson.value.ifBlank { null }

    /** Effective verification keys: pinned always, RC additions only when cross-signed. */
    fun pluginTrustedKeys(): Map<String, ByteArray> =
        com.exapps.mangaworld.core.source.plugins.PluginTrust.resolveTrustedKeys(
            rcJson = _pluginRotationJson.value.ifBlank { null }
        )

    /**
     * Effective source base URLs (`source_<id>` → origin). The dashboard edits
     * these via Remote Config keys `source_<id>_base_url`; blank/invalid
     * values fall back to the enum defaults. Lets admins follow domain moves
     * (starz: manga-starz.net → starzmanga.com) without an app update.
     */
    private val _sourceBaseUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceBaseUrls: StateFlow<Map<String, String>> = _sourceBaseUrls.asStateFlow()

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
                    "engagement_tier_avid_ms" to 36000000L,
                    "plugin_key_rotation" to "",
                    "plugin_kill_switch" to ""
                ) + sourceDefaultEntries()
            ).await()
            applyState()
        }
    }

    /**
     * Per-source defaults straight from the plugin registry: enabled + base URL per
     * known source. Adding a source needs no Remote Config edit (and rockmanga's dead
     * keys simply stop being read once the plugin is gone).
     */
    private fun sourceDefaultEntries(): Map<String, Any> = buildMap {
        for (plugin in registryProvider.get().all()) {
            val id = plugin.descriptor.id.value
            put("source_${id}_enabled", true)
            put("source_${id}_base_url", plugin.descriptor.baseUrl)
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
        // Registry-driven: every known plugin gets kill-switch + domain keys.
        val plugins = registryProvider.get().all()
        val disabled = buildSet {
            for (plugin in plugins) {
                val id = plugin.descriptor.id.value
                if (!remoteConfig.getBoolean("source_${id}_enabled")) add(id)
            }
        }
        _disabledSourceIds.value = disabled

        // Per-source domains. Only non-blank, valid origins are kept — anything
        // else falls back to the descriptor default inside SourceDomainOverrides.
        val domainOverrides: Map<String, String> = buildMap<String, String> {
            for (plugin in plugins) {
                val id = plugin.descriptor.id.value
                val raw = remoteConfig.getString("source_${id}_base_url")
                SourceDomainOverrides.normalizeBaseUrl(raw)?.let { put(id, it) }
            }
        }
        _sourceBaseUrls.value = buildMap<String, String> {
            for (plugin in plugins) {
                val id = plugin.descriptor.id.value
                put(id, domainOverrides[id] ?: plugin.descriptor.baseUrl)
            }
        }
        SourceDomainOverrides.replaceAll(domainOverrides)

        val overridesJson = remoteConfig.getString("scraper_selector_overrides")
        _selectorOverridesJson.value = overridesJson
        _homeLayoutVariant.value = remoteConfig.getString("home_layout_variant").ifBlank { "default" }
        _bannedKeywords.value = remoteConfig.getString("community_banned_keywords")
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        _remoteAlertMessage.value = remoteConfig.getString("remote_alert_message")
        _pluginRotationJson.value = remoteConfig.getString("plugin_key_rotation")
        _pluginKillSwitchJson.value = remoteConfig.getString("plugin_kill_switch")
        _scraperRuntimeConfig.value = ScraperRuntimeConfig(
            connectTimeoutSeconds = remoteConfig.getLong("scraper_connect_timeout_seconds").toInt().coerceIn(5, 90),
            readTimeoutSeconds = remoteConfig.getLong("scraper_read_timeout_seconds").toInt().coerceIn(5, 120),
            writeTimeoutSeconds = remoteConfig.getLong("scraper_write_timeout_seconds").toInt().coerceIn(5, 90),
            retryCount = remoteConfig.getLong("scraper_retry_count").toInt().coerceIn(0, 3)
        )
        RemoteSelectorOverridesStore.replaceAll(parseOverrides(overridesJson))
        // A Remote Config typo must not invert tiers: enforce warming <= active <= avid.
        val tiers = listOf(
            remoteConfig.getLong("engagement_tier_warming_ms"),
            remoteConfig.getLong("engagement_tier_active_ms"),
            remoteConfig.getLong("engagement_tier_avid_ms")
        ).map { it.coerceAtLeast(0) }.sorted()
        _engagementWarmingMs.value = tiers[0]
        _engagementActiveMs.value = tiers[1]
        _engagementAvidMs.value = tiers[2]
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
    }.getOrElse { e ->
        // Malformed JSON must not silently wipe all selector overrides.
        android.util.Log.w("RemoteConfig", "parseOverrides failed, keeping previous: ${e.message}")
        RemoteSelectorOverridesStore.snapshot()
    }
}
