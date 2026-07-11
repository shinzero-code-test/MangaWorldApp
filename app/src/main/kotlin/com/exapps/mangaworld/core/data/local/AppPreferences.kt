package com.exapps.mangaworld.core.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.exapps.mangaworld.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mangaworld_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val KEY_AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications")
        val KEY_ENABLED_SOURCES = stringPreferencesKey("enabled_sources")
        val KEY_DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val KEY_BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val KEY_SECURE_READER = booleanPreferencesKey("secure_reader")
        val KEY_NOTIFICATION_MODE = stringPreferencesKey("notification_mode")
        val KEY_AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup_downloads")
        val KEY_CLEANUP_HOURS = intPreferencesKey("cleanup_hours")
        val KEY_IMAGE_CACHE_LIMIT_MB = intPreferencesKey("image_cache_limit_mb")
        val KEY_CONTENT_BLACKLIST = stringPreferencesKey("content_blacklist")
        val KEY_SPOILER_COLLAPSE_DEFAULT = booleanPreferencesKey("spoiler_collapse_default")
        val KEY_MUTED_USERS = stringPreferencesKey("muted_users")
        val KEY_READING_LIST_STATUS = stringPreferencesKey("reading_list_status")
        val KEY_FAVORITE_GENRES = stringPreferencesKey("favorite_genres")
        val KEY_SYNC_TOMBSTONES = stringPreferencesKey("sync_tombstones")

        val KEY_READER_MODE = stringPreferencesKey("reader_mode")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_KEEP_SCREEN = booleanPreferencesKey("keep_screen")
        val KEY_SHOW_PAGE_NUM = booleanPreferencesKey("show_page_num")
        val KEY_AUTO_WEBTOON = booleanPreferencesKey("auto_webtoon")
        val KEY_INCOGNITO = booleanPreferencesKey("reader_incognito")
        val KEY_SMART_PREFETCH = booleanPreferencesKey("smart_prefetch")
        val KEY_HAPTICS = booleanPreferencesKey("reader_haptics")
        val KEY_IMAGE_FILTER = stringPreferencesKey("reader_image_filter")
        val KEY_AUTO_OPEN_NEXT = booleanPreferencesKey("reader_auto_next")
        val KEY_SHOW_LIVE_READERS = booleanPreferencesKey("reader_show_live_readers")
        val KEY_SHOW_REACTIONS = booleanPreferencesKey("reader_show_reactions")
        val KEY_DUAL_PAGE = booleanPreferencesKey("reader_dual_page")
        val KEY_WEBTOON_STITCH = booleanPreferencesKey("reader_webtoon_stitch")
        val KEY_PAGE_SPACING = intPreferencesKey("reader_page_spacing")
        val KEY_VOLUME_BUTTON = booleanPreferencesKey("reader_volume_button")
        val KEY_DOUBLE_TAP_ZOOM = booleanPreferencesKey("reader_double_tap_zoom")
        val KEY_TAP_LEFT_ACTION = stringPreferencesKey("reader_tap_left_action")
        val KEY_TAP_RIGHT_ACTION = stringPreferencesKey("reader_tap_right_action")
        val KEY_TAP_MIDDLE_ACTION = stringPreferencesKey("reader_tap_middle_action")

        fun cookieKey(domain: String) = stringPreferencesKey("cookie_$domain")
        fun sourceNotificationKey(sourceId: String) = booleanPreferencesKey("notif_source_$sourceId")
    }

    val appSettings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                theme = prefs[KEY_THEME]?.let { t -> AppTheme.entries.firstOrNull { it.name == t } }
                    ?: AppTheme.DARK,
                downloadOnWifiOnly = prefs[KEY_DOWNLOAD_WIFI_ONLY] ?: true,
                autoDownloadNewChapters = prefs[KEY_AUTO_DOWNLOAD] ?: false,
                enableNotifications = prefs[KEY_NOTIFICATIONS] ?: true,
                enabledSources = prefs[KEY_ENABLED_SOURCES]
                    ?.split(",")?.toSet()
                    ?: MangaSource.entries.map { it.id }.toSet(),
                onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
                useDynamicColors = prefs[KEY_DYNAMIC_COLORS] ?: true,
                biometricLockEnabled = prefs[KEY_BIOMETRIC_LOCK] ?: false,
                secureReaderEnabled = prefs[KEY_SECURE_READER] ?: false,
                notificationDeliveryMode = prefs[KEY_NOTIFICATION_MODE]
                    ?.let { name -> NotificationDeliveryMode.entries.firstOrNull { it.name == name } }
                    ?: NotificationDeliveryMode.INSTANT,
                autoCleanupReadDownloads = prefs[KEY_AUTO_CLEANUP] ?: false,
                cleanupAfterHours = prefs[KEY_CLEANUP_HOURS] ?: 24,
                imageCacheLimitMb = prefs[KEY_IMAGE_CACHE_LIMIT_MB] ?: 250,
                contentBlacklist = prefs[KEY_CONTENT_BLACKLIST]
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet(),
                spoilerCollapseDefault = prefs[KEY_SPOILER_COLLAPSE_DEFAULT] ?: true,
                mutedUserIds = prefs[KEY_MUTED_USERS]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet(),
                readingListStatus = prefs[KEY_READING_LIST_STATUS],
                favoriteGenres = prefs[KEY_FAVORITE_GENRES]
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            )
        }

    val readerSettings: Flow<ReaderSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            ReaderSettings(
                mode = prefs[KEY_READER_MODE]?.let { m -> ReaderMode.entries.firstOrNull { it.name == m } }
                    ?: ReaderMode.VERTICAL_SCROLL,
                brightness = prefs[KEY_BRIGHTNESS] ?: 1.0f,
                keepScreenOn = prefs[KEY_KEEP_SCREEN] ?: true,
                showPageNumber = prefs[KEY_SHOW_PAGE_NUM] ?: true,
                autoWebtoonDetection = prefs[KEY_AUTO_WEBTOON] ?: true,
                incognitoMode = prefs[KEY_INCOGNITO] ?: false,
                smartPrefetchEnabled = prefs[KEY_SMART_PREFETCH] ?: true,
                hapticsEnabled = prefs[KEY_HAPTICS] ?: true,
                imageFilter = prefs[KEY_IMAGE_FILTER]
                    ?.let { name -> ReaderImageFilter.entries.firstOrNull { it.name == name } }
                    ?: ReaderImageFilter.NONE,
                autoOpenNextChapter = prefs[KEY_AUTO_OPEN_NEXT] ?: false,
                showLiveReadersOverlay = prefs[KEY_SHOW_LIVE_READERS] ?: true,
                showReactionOverlay = prefs[KEY_SHOW_REACTIONS] ?: true,
                dualPageLandscape = prefs[KEY_DUAL_PAGE] ?: false,
                webtoonAutoStitch = prefs[KEY_WEBTOON_STITCH] ?: true,
                pageSpacing = prefs[KEY_PAGE_SPACING] ?: 0,
                volumeButtonPageTurn = prefs[KEY_VOLUME_BUTTON] ?: false,
                doubleTapZoom = prefs[KEY_DOUBLE_TAP_ZOOM] ?: true,
                tapLeftAction = prefs[KEY_TAP_LEFT_ACTION]
                    ?.let { TapAction.entries.firstOrNull { action -> action.name == it } }
                    ?: TapAction.PREV_PAGE,
                tapRightAction = prefs[KEY_TAP_RIGHT_ACTION]
                    ?.let { TapAction.entries.firstOrNull { action -> action.name == it } }
                    ?: TapAction.NEXT_PAGE,
                tapMiddleAction = prefs[KEY_TAP_MIDDLE_ACTION]
                    ?.let { TapAction.entries.firstOrNull { action -> action.name == it } }
                    ?: TapAction.TOGGLE_CONTROLS
            )
        }

    suspend fun setTheme(theme: AppTheme) =
        dataStore.edit { it[KEY_THEME] = theme.name }

    suspend fun setOnboardingDone(done: Boolean) =
        dataStore.edit { it[KEY_ONBOARDING_DONE] = done }

    suspend fun setDownloadWifiOnly(v: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_WIFI_ONLY] = v }

    suspend fun setAutoDownload(v: Boolean) =
        dataStore.edit { it[KEY_AUTO_DOWNLOAD] = v }

    suspend fun setNotifications(v: Boolean) =
        dataStore.edit { it[KEY_NOTIFICATIONS] = v }

    suspend fun setDynamicColors(v: Boolean) =
        dataStore.edit { it[KEY_DYNAMIC_COLORS] = v }

    suspend fun setBiometricLock(v: Boolean) =
        dataStore.edit { it[KEY_BIOMETRIC_LOCK] = v }

    suspend fun setSecureReader(v: Boolean) =
        dataStore.edit { it[KEY_SECURE_READER] = v }

    suspend fun setNotificationMode(v: NotificationDeliveryMode) =
        dataStore.edit { it[KEY_NOTIFICATION_MODE] = v.name }

    suspend fun setAutoCleanup(v: Boolean) =
        dataStore.edit { it[KEY_AUTO_CLEANUP] = v }

    suspend fun setCleanupHours(v: Int) =
        dataStore.edit { it[KEY_CLEANUP_HOURS] = v }

    suspend fun setImageCacheLimitMb(v: Int) =
        dataStore.edit { it[KEY_IMAGE_CACHE_LIMIT_MB] = v }

    suspend fun setContentBlacklist(values: Set<String>) =
        dataStore.edit { it[KEY_CONTENT_BLACKLIST] = values.joinToString("\n") }

    suspend fun setSpoilerCollapseDefault(value: Boolean) =
        dataStore.edit { it[KEY_SPOILER_COLLAPSE_DEFAULT] = value }

    suspend fun setMutedUsers(values: Set<String>) =
        dataStore.edit { it[KEY_MUTED_USERS] = values.joinToString(",") }

    suspend fun setReadingListStatus(value: String?) = dataStore.edit {
        if (value.isNullOrBlank()) it.remove(KEY_READING_LIST_STATUS)
        else it[KEY_READING_LIST_STATUS] = value
    }

    suspend fun setFavoriteGenres(values: List<String>) =
        dataStore.edit { it[KEY_FAVORITE_GENRES] = values.joinToString("\n") }

    suspend fun getSyncTombstones(): List<SyncTombstone> =
        decodeSyncTombstones(dataStore.data.first()[KEY_SYNC_TOMBSTONES])

    suspend fun markSyncTombstone(collection: String, documentId: String, deletedAt: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val tombstones = decodeSyncTombstones(prefs[KEY_SYNC_TOMBSTONES]).associateBy { it.key }.toMutableMap()
            val tombstone = SyncTombstone(collection, documentId, deletedAt)
            val existing = tombstones[tombstone.key]
            if (existing == null || existing.deletedAt < deletedAt) tombstones[tombstone.key] = tombstone
            prefs[KEY_SYNC_TOMBSTONES] = encodeSyncTombstones(tombstones.values)
        }
    }

    suspend fun clearSyncTombstone(collection: String, documentId: String) {
        dataStore.edit { prefs ->
            val key = "$collection|$documentId"
            val remaining = decodeSyncTombstones(prefs[KEY_SYNC_TOMBSTONES]).filterNot { it.key == key }
            if (remaining.isEmpty()) prefs.remove(KEY_SYNC_TOMBSTONES)
            else prefs[KEY_SYNC_TOMBSTONES] = encodeSyncTombstones(remaining)
        }
    }

    suspend fun toggleSource(sourceId: String, enabled: Boolean) = dataStore.edit { prefs ->
        val current = prefs[KEY_ENABLED_SOURCES]?.split(",")?.toMutableSet()
            ?: MangaSource.entries.map { it.id }.toMutableSet()
        if (enabled) current.add(sourceId) else current.remove(sourceId)
        prefs[KEY_ENABLED_SOURCES] = current.joinToString(",")
    }

    suspend fun setEnabledSources(sourceIds: Set<String>) = dataStore.edit { prefs ->
        prefs[KEY_ENABLED_SOURCES] = sourceIds.joinToString(",")
    }

    suspend fun setReaderMode(mode: ReaderMode) =
        dataStore.edit { it[KEY_READER_MODE] = mode.name }

    suspend fun setBrightness(v: Float) =
        dataStore.edit { it[KEY_BRIGHTNESS] = v }

    suspend fun setKeepScreen(v: Boolean) =
        dataStore.edit { it[KEY_KEEP_SCREEN] = v }

    suspend fun setShowPageNum(v: Boolean) =
        dataStore.edit { it[KEY_SHOW_PAGE_NUM] = v }

    suspend fun setAutoWebtoon(v: Boolean) =
        dataStore.edit { it[KEY_AUTO_WEBTOON] = v }

    suspend fun setIncognito(v: Boolean) =
        dataStore.edit { it[KEY_INCOGNITO] = v }

    suspend fun setSmartPrefetch(v: Boolean) =
        dataStore.edit { it[KEY_SMART_PREFETCH] = v }

    suspend fun setReaderHaptics(v: Boolean) =
        dataStore.edit { it[KEY_HAPTICS] = v }

    suspend fun setImageFilter(v: ReaderImageFilter) =
        dataStore.edit { it[KEY_IMAGE_FILTER] = v.name }

    suspend fun setAutoOpenNext(v: Boolean) =
        dataStore.edit { it[KEY_AUTO_OPEN_NEXT] = v }

    suspend fun setShowLiveReaders(v: Boolean) =
        dataStore.edit { it[KEY_SHOW_LIVE_READERS] = v }

    suspend fun setShowReactions(v: Boolean) =
        dataStore.edit { it[KEY_SHOW_REACTIONS] = v }

    suspend fun setDualPage(v: Boolean) =
        dataStore.edit { it[KEY_DUAL_PAGE] = v }

    suspend fun setWebtoonStitch(v: Boolean) =
        dataStore.edit { it[KEY_WEBTOON_STITCH] = v }

    suspend fun setPageSpacing(v: Int) =
        dataStore.edit { it[KEY_PAGE_SPACING] = v }

    suspend fun setVolumeButton(v: Boolean) =
        dataStore.edit { it[KEY_VOLUME_BUTTON] = v }

    suspend fun setDoubleTapZoom(v: Boolean) =
        dataStore.edit { it[KEY_DOUBLE_TAP_ZOOM] = v }

    suspend fun setTapActions(left: TapAction, right: TapAction, middle: TapAction) =
        dataStore.edit {
            it[KEY_TAP_LEFT_ACTION] = left.name
            it[KEY_TAP_RIGHT_ACTION] = right.name
            it[KEY_TAP_MIDDLE_ACTION] = middle.name
        }

    private fun decodeSyncTombstones(serialized: String?): List<SyncTombstone> = runCatching {
        val array = JSONArray(serialized ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                SyncTombstone(
                    collection = item.optString("collection"),
                    documentId = item.optString("documentId"),
                    deletedAt = item.optLong("deletedAt")
                ).takeIf { it.collection.isNotBlank() && it.documentId.isNotBlank() && it.deletedAt > 0L }
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeSyncTombstones(tombstones: Collection<SyncTombstone>): String =
        JSONArray().apply {
            tombstones.forEach { tombstone ->
                put(JSONObject().apply {
                    put("collection", tombstone.collection)
                    put("documentId", tombstone.documentId)
                    put("deletedAt", tombstone.deletedAt)
                })
            }
        }.toString()

    fun getCookies(domain: String): Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[cookieKey(domain)] }

    suspend fun saveCookies(domain: String, cookies: String) =
        dataStore.edit { it[cookieKey(domain)] = cookies }

    suspend fun clearCookies(domain: String) =
        dataStore.edit { it.remove(cookieKey(domain)) }

    // Source-specific notification settings (default: enabled)
    fun isSourceNotificationEnabled(sourceId: String): Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[sourceNotificationKey(sourceId)] ?: true }

    suspend fun setSourceNotification(sourceId: String, enabled: Boolean) =
        dataStore.edit { it[sourceNotificationKey(sourceId)] = enabled }
}
