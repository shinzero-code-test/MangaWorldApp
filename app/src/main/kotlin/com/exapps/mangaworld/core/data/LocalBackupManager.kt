package com.exapps.mangaworld.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.exapps.mangaworld.core.data.local.MangaDatabase
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import com.exapps.mangaworld.core.data.local.entity.ReadChapterEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingHistoryEntity
import com.exapps.mangaworld.core.data.local.entity.ReaderAnnotationEntity
import com.exapps.mangaworld.core.data.local.entity.ReadingProgressEntity
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.ReaderImageFilter
import com.exapps.mangaworld.domain.model.ReaderMode
import com.exapps.mangaworld.domain.model.ReaderSettings
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val readChapterDao: ReadChapterDao,
    private val progressDao: ReadingProgressDao,
    private val annotationDao: ReaderAnnotationDao,
    private val readingStatsStore: ReadingStatsStore,
    private val settingsRepository: SettingsRepository
) {
    suspend fun exportTo(uri: Uri) {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("favorites", JSONArray(favoriteDao.getAllLibraryEntries().map { it.toJson() }))
            put("history", JSONArray(historyDao.getAll().map { it.toJson() }))
            put("readChapters", JSONArray(readChapterDao.getAll().map { it.toJson() }))
            put("progress", JSONArray(progressDao.getAll().map { it.toJson() }))
            put("annotations", JSONArray(annotationDao.getAll().map { it.toJson() }))
            // Persist the RAW stored source set: getAppSettings() applies a Remote-Config filter
            // whose filtered view must never be written back, or a temporarily server-disabled
            // source would become permanently disabled after a backup round-trip.
            put(
                "appSettings",
                settingsRepository.getAppSettings().first()
                    .copy(enabledSources = settingsRepository.getStoredEnabledSources())
                    .toJson()
            )
            put("readerSettings", settingsRepository.getReaderSettings().first().toJson())
            runCatching { put("readingStats", readingStatsStore.snapshot()) }
                .onFailure { Log.w(TAG, "Stats export skipped: ${it.message}") }
        }
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
    }

    suspend fun importFrom(uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val root = JSONObject(json)
        val backupVersion = root.optInt("schemaVersion", 1)
        if (backupVersion > SCHEMA_VERSION) {
            Log.w(TAG, "Backup schema v$backupVersion is newer than supported v$SCHEMA_VERSION — aborting import")
            return
        }

        // One atomic unit: a crash mid-import must never leave a half-restored library.
        // Rows merge newest-wins so an old backup cannot clobber fresher local state.
        database.withTransaction {
            root.optJSONArray("favorites")?.forEachObjects { row ->
                runCatching { row.toFavoriteEntity() }.getOrNull()?.let { mergeFavorite(it) }
            }
            root.optJSONArray("history")?.forEachObjects { row ->
                runCatching { row.toHistoryEntity() }.getOrNull()?.let { mergeHistory(it) }
            }
            // markRead uses OnConflictStrategy.IGNORE — a natural union of read chapters.
            root.optJSONArray("readChapters")?.forEachObjects { row ->
                runCatching { row.toReadChapterEntity() }.getOrNull()?.let { readChapterDao.markRead(it) }
            }
            root.optJSONArray("progress")?.forEachObjects { row ->
                runCatching { row.toProgressEntity() }.getOrNull()?.let { mergeProgress(it) }
            }
            root.optJSONArray("annotations")?.forEachObjects { row ->
                runCatching { row.toAnnotationEntity() }.getOrNull()?.let { mergeAnnotation(it) }
            }
        }

        root.optJSONObject("appSettings")?.toAppSettings()?.let { applyAppSettings(it) }
        root.optJSONObject("readerSettings")?.toReaderSettings()?.let { applyReaderSettings(it) }
        root.optJSONObject("readingStats")?.let { stats ->
            runCatching { readingStatsStore.restore(stats) }
                .onFailure { Log.w(TAG, "Stats restore skipped: ${it.message}") }
        }
    }

    private suspend fun mergeFavorite(backup: FavoriteEntity) {
        val existing = favoriteDao.getById(backup.mangaId)
        if (existing == null || backup.addedAt >= existing.addedAt) favoriteDao.insert(backup)
    }

    private suspend fun mergeHistory(backup: ReadingHistoryEntity) {
        val existing = historyDao.getByMangaId(backup.mangaId)
        if (existing == null || backup.lastReadAt >= existing.lastReadAt) historyDao.insertOrUpdate(backup)
    }

    private suspend fun mergeProgress(backup: ReadingProgressEntity) {
        val existing = progressDao.get(backup.mangaId, backup.chapterNumber)
        if (existing == null || backup.updatedAt >= existing.updatedAt) progressDao.save(backup)
    }

    private suspend fun mergeAnnotation(backup: ReaderAnnotationEntity) {
        val existing = annotationDao.get(backup.mangaId, backup.chapterUrl, backup.pageIndex)
        if (existing == null || backup.updatedAt >= existing.updatedAt) annotationDao.upsert(backup)
    }

    private suspend fun applyAppSettings(settings: AppSettings) {
        settingsRepository.updateTheme(settings.theme)
        settingsRepository.setDownloadOnWifiOnly(settings.downloadOnWifiOnly)
        settingsRepository.setAutoDownloadNewChapters(settings.autoDownloadNewChapters)
        settingsRepository.setNotificationsEnabled(settings.enableNotifications)
        settingsRepository.setEnabledSources(settings.enabledSources)
        settingsRepository.setDynamicColors(settings.useDynamicColors)
        settingsRepository.setBiometricLock(settings.biometricLockEnabled)
        settingsRepository.setSecureReader(settings.secureReaderEnabled)
        settingsRepository.setNotificationDeliveryMode(settings.notificationDeliveryMode)
        settingsRepository.setAutoCleanupReadDownloads(settings.autoCleanupReadDownloads)
        settingsRepository.setCleanupAfterHours(settings.cleanupAfterHours)
        settingsRepository.setImageCacheLimitMb(settings.imageCacheLimitMb)
        settingsRepository.setContentBlacklist(settings.contentBlacklist)
        settingsRepository.setSpoilerCollapseDefault(settings.spoilerCollapseDefault)
        settingsRepository.setMutedUserIds(settings.mutedUserIds)
        settingsRepository.setOnboardingCompleted(settings.onboardingCompleted)
        settingsRepository.setReadingListStatus(settings.readingListStatus)
        settingsRepository.setFavoriteGenres(settings.favoriteGenres)
        settingsRepository.setShowLibraryPublic(settings.showLibraryPublic)
    }

    private suspend fun applyReaderSettings(settings: ReaderSettings) {
        settingsRepository.updateReaderMode(settings.mode)
        settingsRepository.updateBrightness(settings.brightness)
        settingsRepository.updateKeepScreenOn(settings.keepScreenOn)
        settingsRepository.updatePageSpacing(settings.pageSpacing)
        settingsRepository.updateShowPageNumber(settings.showPageNumber)
        settingsRepository.updateAutoWebtoon(settings.autoWebtoonDetection)
        settingsRepository.updateIncognitoMode(settings.incognitoMode)
        settingsRepository.updateSmartPrefetch(settings.smartPrefetchEnabled)
        settingsRepository.updateReaderHaptics(settings.hapticsEnabled)
        settingsRepository.updateImageFilter(settings.imageFilter)
        settingsRepository.updateAutoOpenNextChapter(settings.autoOpenNextChapter)
        settingsRepository.updateShowLiveReadersOverlay(settings.showLiveReadersOverlay)
        settingsRepository.updateShowReactionOverlay(settings.showReactionOverlay)
        settingsRepository.updateDualPageLandscape(settings.dualPageLandscape)
        settingsRepository.updateWebtoonAutoStitch(settings.webtoonAutoStitch)
        settingsRepository.updateVolumeButtonPageTurn(settings.volumeButtonPageTurn)
        settingsRepository.updateDoubleTapZoom(settings.doubleTapZoom)
        settingsRepository.updateTapActions(
            settings.tapLeftAction,
            settings.tapRightAction,
            settings.tapMiddleAction
        )
    }

    private fun FavoriteEntity.toJson() = JSONObject().apply {
        put("mangaId", mangaId); put("slug", slug); put("title", title); put("coverUrl", coverUrl)
        put("sourceId", sourceId); put("addedAt", addedAt); put("readChapters", readChapters); put("totalChapters", totalChapters)
        put("readingStatus", readingStatus); put("isFavorite", isFavorite)
    }
    private fun ReadingHistoryEntity.toJson() = JSONObject().apply {
        put("mangaId", mangaId); put("slug", slug); put("title", title); put("coverUrl", coverUrl)
        put("sourceId", sourceId); put("lastChapterNumber", lastChapterNumber.toDouble()); put("lastChapterUrl", lastChapterUrl)
        put("lastReadAt", lastReadAt); put("readChapters", readChapters); put("totalChapters", totalChapters)
    }
    private fun ReadChapterEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterNumber", chapterNumber.toDouble()); put("readAt", readAt) }
    private fun ReadingProgressEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterNumber", chapterNumber.toDouble()); put("currentPage", currentPage); put("totalPages", totalPages); put("updatedAt", updatedAt) }
    private fun ReaderAnnotationEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterUrl", chapterUrl); put("pageIndex", pageIndex); put("note", note); put("isBookmarked", isBookmarked); put("updatedAt", updatedAt) }

    private fun JSONObject.toFavoriteEntity() = FavoriteEntity(
        mangaId = getString("mangaId"),
        slug = getString("slug"),
        title = getString("title"),
        coverUrl = getString("coverUrl"),
        sourceId = getString("sourceId"),
        addedAt = getLong("addedAt"),
        readChapters = optInt("readChapters"),
        totalChapters = optInt("totalChapters"),
        readingStatus = optString("readingStatus").takeIf { it.isNotBlank() },
        isFavorite = optBoolean("isFavorite", true)
    )
    private fun JSONObject.toHistoryEntity() = ReadingHistoryEntity(getString("mangaId"), getString("slug"), getString("title"), getString("coverUrl"), getString("sourceId"), getDouble("lastChapterNumber").toFloat(), optString("lastChapterUrl"), getLong("lastReadAt"), optInt("readChapters"), optInt("totalChapters"))
    private fun JSONObject.toReadChapterEntity() = ReadChapterEntity(getString("mangaId"), getDouble("chapterNumber").toFloat(), getLong("readAt"))
    private fun JSONObject.toProgressEntity() = ReadingProgressEntity(getString("mangaId"), getDouble("chapterNumber").toFloat(), optInt("currentPage"), optInt("totalPages"), getLong("updatedAt"))
    private fun JSONObject.toAnnotationEntity() = ReaderAnnotationEntity(getString("mangaId"), getString("chapterUrl"), getInt("pageIndex"), optString("note"), optBoolean("isBookmarked"), getLong("updatedAt"))

    private fun AppSettings.toJson() = JSONObject().apply {
        put("theme", theme.name); put("downloadOnWifiOnly", downloadOnWifiOnly); put("autoDownloadNewChapters", autoDownloadNewChapters)
        put("enableNotifications", enableNotifications); put("enabledSources", JSONArray(enabledSources.toList())); put("onboardingCompleted", onboardingCompleted)
        put("useDynamicColors", useDynamicColors); put("biometricLockEnabled", biometricLockEnabled); put("secureReaderEnabled", secureReaderEnabled)
        put("notificationDeliveryMode", notificationDeliveryMode.name); put("autoCleanupReadDownloads", autoCleanupReadDownloads); put("cleanupAfterHours", cleanupAfterHours)
        put("imageCacheLimitMb", imageCacheLimitMb); put("contentBlacklist", JSONArray(contentBlacklist.toList())); put("spoilerCollapseDefault", spoilerCollapseDefault); put("mutedUserIds", JSONArray(mutedUserIds.toList()))
        put("readingListStatus", readingListStatus); put("favoriteGenres", JSONArray(favoriteGenres))
        put("showLibraryPublic", showLibraryPublic)
    }
    private fun ReaderSettings.toJson() = JSONObject().apply {
        put("mode", mode.name); put("brightness", brightness.toDouble()); put("pageSpacing", pageSpacing); put("keepScreenOn", keepScreenOn)
        put("showPageNumber", showPageNumber); put("autoWebtoonDetection", autoWebtoonDetection); put("incognitoMode", incognitoMode)
        put("smartPrefetchEnabled", smartPrefetchEnabled); put("hapticsEnabled", hapticsEnabled); put("imageFilter", imageFilter.name)
        put("autoOpenNextChapter", autoOpenNextChapter); put("showLiveReadersOverlay", showLiveReadersOverlay); put("showReactionOverlay", showReactionOverlay)
        put("dualPageLandscape", dualPageLandscape); put("webtoonAutoStitch", webtoonAutoStitch)
        put("volumeButtonPageTurn", volumeButtonPageTurn); put("doubleTapZoom", doubleTapZoom)
        put("tapLeftAction", tapLeftAction.name); put("tapRightAction", tapRightAction.name); put("tapMiddleAction", tapMiddleAction.name)
    }

    private fun JSONObject.toAppSettings(): AppSettings = AppSettings(
        theme = enumValue(optString("theme"), com.exapps.mangaworld.domain.model.AppTheme.SYSTEM),
        downloadOnWifiOnly = optBoolean("downloadOnWifiOnly", true),
        autoDownloadNewChapters = optBoolean("autoDownloadNewChapters", false),
        enableNotifications = optBoolean("enableNotifications", true),
        enabledSources = optJSONArray("enabledSources")?.toStringSet() ?: emptySet(),
        onboardingCompleted = optBoolean("onboardingCompleted", false),
        useDynamicColors = optBoolean("useDynamicColors", true),
        biometricLockEnabled = optBoolean("biometricLockEnabled", false),
        secureReaderEnabled = optBoolean("secureReaderEnabled", false),
        notificationDeliveryMode = enumValue(optString("notificationDeliveryMode"), com.exapps.mangaworld.domain.model.NotificationDeliveryMode.INSTANT),
        autoCleanupReadDownloads = optBoolean("autoCleanupReadDownloads", false),
        cleanupAfterHours = optInt("cleanupAfterHours", 24),
        imageCacheLimitMb = optInt("imageCacheLimitMb", 250),
        contentBlacklist = optJSONArray("contentBlacklist")?.toStringSet() ?: emptySet(),
        spoilerCollapseDefault = optBoolean("spoilerCollapseDefault", true),
        mutedUserIds = optJSONArray("mutedUserIds")?.toStringSet() ?: emptySet(),
        readingListStatus = optString("readingListStatus").takeIf { it.isNotBlank() },
        favoriteGenres = optJSONArray("favoriteGenres")?.toStringList() ?: emptyList(),
        showLibraryPublic = optBoolean("showLibraryPublic", true)
    )

    private fun JSONObject.toReaderSettings(): ReaderSettings = ReaderSettings(
        mode = enumValue(optString("mode"), ReaderMode.VERTICAL_SCROLL),
        brightness = optDouble("brightness", 1.0).toFloat(),
        pageSpacing = optInt("pageSpacing", 0),
        keepScreenOn = optBoolean("keepScreenOn", true),
        showPageNumber = optBoolean("showPageNumber", true),
        autoWebtoonDetection = optBoolean("autoWebtoonDetection", true),
        incognitoMode = optBoolean("incognitoMode", false),
        smartPrefetchEnabled = optBoolean("smartPrefetchEnabled", true),
        hapticsEnabled = optBoolean("hapticsEnabled", true),
        imageFilter = enumValue(optString("imageFilter"), ReaderImageFilter.NONE),
        autoOpenNextChapter = optBoolean("autoOpenNextChapter", false),
        showLiveReadersOverlay = optBoolean("showLiveReadersOverlay", true),
        showReactionOverlay = optBoolean("showReactionOverlay", true),
        dualPageLandscape = optBoolean("dualPageLandscape", false),
        webtoonAutoStitch = optBoolean("webtoonAutoStitch", true),
        volumeButtonPageTurn = optBoolean("volumeButtonPageTurn", false),
        doubleTapZoom = optBoolean("doubleTapZoom", true),
        tapLeftAction = enumValue(optString("tapLeftAction"), com.exapps.mangaworld.domain.model.TapAction.PREV_PAGE),
        tapRightAction = enumValue(optString("tapRightAction"), com.exapps.mangaworld.domain.model.TapAction.NEXT_PAGE),
        tapMiddleAction = enumValue(optString("tapMiddleAction"), com.exapps.mangaworld.domain.model.TapAction.TOGGLE_CONTROLS)
    )

    private inline fun <reified T : Enum<T>> enumValue(name: String, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default
    private fun JSONArray.toStringSet(): Set<String> = (0 until length()).mapNotNull { idx -> optString(idx).takeIf { it.isNotBlank() } }.toSet()
    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { idx -> optString(idx).takeIf { it.isNotBlank() } }
    private inline fun JSONArray.forEachObjects(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private companion object {
        const val TAG = "LocalBackupManager"

        /** v3: adds `readingStats` and `showLibraryPublic`; imports of v1/v2 remain accepted. */
        const val SCHEMA_VERSION = 3
    }
}
