package com.exapps.mangaworld.core.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.exapps.mangaworld.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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

        val KEY_READER_MODE = stringPreferencesKey("reader_mode")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_KEEP_SCREEN = booleanPreferencesKey("keep_screen")
        val KEY_SHOW_PAGE_NUM = booleanPreferencesKey("show_page_num")
        val KEY_AUTO_WEBTOON = booleanPreferencesKey("auto_webtoon")

        fun cookieKey(domain: String) = stringPreferencesKey("cookie_$domain")
    }

    val appSettings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                theme = prefs[KEY_THEME]?.let { t -> AppTheme.values().firstOrNull { it.name == t } }
                    ?: AppTheme.DARK,
                downloadOnWifiOnly = prefs[KEY_DOWNLOAD_WIFI_ONLY] ?: true,
                autoDownloadNewChapters = prefs[KEY_AUTO_DOWNLOAD] ?: false,
                enableNotifications = prefs[KEY_NOTIFICATIONS] ?: true,
                enabledSources = prefs[KEY_ENABLED_SOURCES]
                    ?.split(",")?.toSet()
                    ?: MangaSource.values().map { it.id }.toSet(),
                onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false
            )
        }

    val readerSettings: Flow<ReaderSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            ReaderSettings(
                mode = prefs[KEY_READER_MODE]?.let { m -> ReaderMode.values().firstOrNull { it.name == m } }
                    ?: ReaderMode.VERTICAL_SCROLL,
                brightness = prefs[KEY_BRIGHTNESS] ?: 1.0f,
                keepScreenOn = prefs[KEY_KEEP_SCREEN] ?: true,
                showPageNumber = prefs[KEY_SHOW_PAGE_NUM] ?: true,
                autoWebtoonDetection = prefs[KEY_AUTO_WEBTOON] ?: true
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

    suspend fun toggleSource(sourceId: String, enabled: Boolean) = dataStore.edit { prefs ->
        val current = prefs[KEY_ENABLED_SOURCES]?.split(",")?.toMutableSet()
            ?: MangaSource.values().map { it.id }.toMutableSet()
        if (enabled) current.add(sourceId) else current.remove(sourceId)
        prefs[KEY_ENABLED_SOURCES] = current.joinToString(",")
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

    fun getCookies(domain: String): Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[cookieKey(domain)] }

    suspend fun saveCookies(domain: String, cookies: String) =
        dataStore.edit { it[cookieKey(domain)] = cookies }

    suspend fun clearCookies(domain: String) =
        dataStore.edit { it.remove(cookieKey(domain)) }
}
