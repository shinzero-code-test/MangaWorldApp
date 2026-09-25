package com.exapps.mangaworld.core.data

import android.content.ContentResolver
import android.provider.OpenableColumns
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
    private val collectionManager: CollectionManager,
    private val bookmarkManager: BookmarkManager,
    private val settingsRepository: SettingsRepository,
    private val pluginIndex: com.exapps.mangaworld.core.source.plugins.PluginIndexStore
) {
    /** Outcome of a backup import — callers must surface non-success to the user. */
    sealed interface ImportResult {
        data class Success(
            val favorites: Int = 0,
            val history: Int = 0,
            val readChapters: Int = 0,
            val collections: Int = 0,
            val bookmarks: Int = 0,
            /** Plugin refs re-registered as AVAILABLE (payloads re-sync, never restored). */
            val plugins: Int = 0
        ) : ImportResult
        object Corrupt : ImportResult
        object TooLarge : ImportResult
        object NewerSchema : ImportResult
        object Empty : ImportResult
    }

    /**
     * Returns false when nothing was written (null output stream) — callers
     * must report failure instead of success.
     *
     * BK-3: the document is staged to a temp file first and only then streamed
     * to [uri] (whose open truncates it). A failure mid-copy deletes the
     * partial target instead of leaving a truncated backup behind.
     */
    suspend fun exportTo(uri: Uri): Boolean {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("favorites", JSONArray(favoriteDao.getAllLibraryEntries().map { it.toJson() }))
            put("history", JSONArray(historyDao.getAll().map { it.toJson() }))
            put("readChapters", JSONArray(readChapterDao.getAll().map { it.toJson() }))
            put("progress", JSONArray(progressDao.getAll().map { it.toJson() }))
            put("annotations", JSONArray(annotationDao.getAll().map { it.toJson() }))
            // BK-1: user-curated collections + bookmarks used to be lost on
            // every device migration — they now ride along in the backup.
            put("collections", JSONArray(collectionManager.snapshot().map { it.toJson() }))
            put("bookmarks", JSONArray(bookmarkManager.snapshot().map { (mangaId, items) ->
                JSONObject().put("mangaId", mangaId)
                    .put("items", JSONArray(items.map { it.toJson() }))
            }))
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
            // BK-4 (plan §11A): backups store plugin REFERENCES ({id, version,
            // origin}), never payloads. Restore re-syncs verified payloads from
            // distribution instead of resurrecting stale or revoked bytes.
            runCatching {
                put(
                    "pluginRefs",
                    JSONArray(
                        pluginIndex.getAll()
                            .filter { it.activeVersion != null }
                            // Revoked/quarantined/invalid installs must not
                            // resurrect through restore (F-review): the sync
                            // engine re-derives those states from live policy.
                            .filter {
                                it.status != com.exapps.mangaworld.core.source.plugins.PluginStatus.REVOKED &&
                                    it.status != com.exapps.mangaworld.core.source.plugins.PluginStatus.QUARANTINED &&
                                    it.status != com.exapps.mangaworld.core.source.plugins.PluginStatus.INVALID
                            }
                            .map { pluginRefToJson(it) }
                    )
                )
            }.onFailure { Log.w(TAG, "Plugin refs export skipped: ${it.message}") }
        }
        val text = root.toString(2)
        val tmp = runCatching {
            java.io.File.createTempFile("mangaworld-backup", ".json", context.cacheDir)
                .apply { writeText(text); deleteOnExit() }
        }.getOrNull() ?: return false
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(text)
                it.flush()
            } ?: return false
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Backup export failed — removing partial file: ${e.message}")
            runCatching { context.contentResolver.delete(uri, null, null) }
            return false
        } finally {
            tmp.delete()
        }
    }

    suspend fun importFrom(uri: Uri): ImportResult {
        // Size gate before readText(): a user-picked multi-hundred-MB file must
        // not OOM the process.
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        }.getOrDefault(-1L)
        if (size > MAX_BACKUP_BYTES) {
            Log.w(TAG, "Backup too large ($size bytes) — aborting import")
            return ImportResult.TooLarge
        }
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return ImportResult.Empty
        if (json.isBlank()) return ImportResult.Empty
        val root = runCatching { JSONObject(json) }.getOrElse {
            Log.w(TAG, "Backup is not valid JSON — aborting import")
            return ImportResult.Corrupt
        }
        val backupVersion = root.optInt("schemaVersion", 1)
        if (backupVersion > SCHEMA_VERSION) {
            Log.w(TAG, "Backup schema v$backupVersion is newer than supported v$SCHEMA_VERSION — aborting import")
            return ImportResult.NewerSchema
        }

        // One atomic unit: a crash mid-import must never leave a half-restored library.
        // Rows merge newest-wins so an old backup cannot clobber fresher local state.
        // Array caps bound a hostile 500k-row array to one bounded transaction.
        var favCount = 0
        var histCount = 0
        var readCount = 0
        database.withTransaction {
            root.optJSONArray("favorites")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toFavoriteEntity() }.getOrNull()?.let { mergeFavorite(it); favCount++ }
            }
            root.optJSONArray("history")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toHistoryEntity() }.getOrNull()?.let { mergeHistory(it); histCount++ }
            }
            // markRead uses OnConflictStrategy.IGNORE — a natural union of read chapters.
            root.optJSONArray("readChapters")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toReadChapterEntity() }.getOrNull()?.let { readChapterDao.markRead(it); readCount++ }
            }
            root.optJSONArray("progress")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toProgressEntity() }.getOrNull()?.let { mergeProgress(it) }
            }
            root.optJSONArray("annotations")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toAnnotationEntity() }.getOrNull()?.let { mergeAnnotation(it) }
            }
        }

        // BK-1: collections + bookmarks merge newest-wins / id-union outside
        // the Room transaction (they live in DataStore/SharedPreferences).
        var collectionCount = 0
        root.optJSONArray("collections")?.let { arr ->
            val parsed = mutableListOf<MangaCollection>()
            arr.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                runCatching { row.toMangaCollection() }.getOrNull()?.let { parsed.add(it) }
            }
            if (parsed.isNotEmpty()) {
                runCatching { collectionManager.restoreCollections(parsed) }
                    .onSuccess { collectionCount = parsed.size }
                    .onFailure { Log.w(TAG, "Collections restore skipped: ${it.message}") }
            }
        }
        var bookmarkCount = 0
        root.optJSONArray("bookmarks")?.let { arr ->
            val parsed = mutableMapOf<String, List<Bookmark>>()
            arr.forEachObjects(MAX_ROWS_PER_ARRAY) { entry ->
                val mangaId = entry.optString("mangaId").take(MAX_ID_LENGTH)
                if (mangaId.isBlank()) return@forEachObjects
                val items = mutableListOf<Bookmark>()
                entry.optJSONArray("items")?.forEachObjects(MAX_ROWS_PER_ARRAY) { row ->
                    runCatching { row.toBookmark(mangaId) }.getOrNull()?.let { items.add(it) }
                }
                if (items.isNotEmpty()) parsed[mangaId] = items
            }
            if (parsed.isNotEmpty()) {
                runCatching { bookmarkManager.restoreBookmarks(parsed) }
                    .onSuccess { bookmarkCount = parsed.values.sumOf { it.size } }
                    .onFailure { Log.w(TAG, "Bookmarks restore skipped: ${it.message}") }
            }
        }

        root.optJSONObject("appSettings")?.let { obj ->
            val parsed = obj.toAppSettings()
            // BK-2: a backup without enabledSources must not disable everything.
            val withSources = parsed.copy(
                enabledSources = obj.effectiveEnabledSources(settingsRepository.getStoredEnabledSources())
            )
            applyAppSettings(withSources)
        }
        root.optJSONObject("readerSettings")?.toReaderSettings()?.let { applyReaderSettings(it) }
        root.optJSONObject("readingStats")?.let { stats ->
            runCatching { readingStatsStore.restore(stats) }
                .onFailure { Log.w(TAG, "Stats restore skipped: ${it.message}") }
        }
        // BK-4: plugin refs restore as AVAILABLE records with no bytes — the sync
        // engine reconciles them (downloads verified payloads) instead of the
        // backup resurrecting payloads. Existing local records always win; a ref
        // never clobbers or downgrades local state.
        var pluginCount = 0
        root.optJSONArray("pluginRefs")?.let { arr ->
            val total = minOf(arr.length(), MAX_PLUGIN_REFS)
            for (i in 0 until total) {
                val ref = runCatching { arr.getJSONObject(i).toPluginRef() }.getOrNull() ?: continue
                runCatching {
                    if (pluginIndex.get(ref.id) == null) {
                        pluginIndex.put(
                            com.exapps.mangaworld.core.source.plugins.PluginIndexRecord(
                                id = ref.id,
                                activeVersion = null,
                                previousVersion = null,
                                origin = ref.origin,
                                status = com.exapps.mangaworld.core.source.plugins.PluginStatus.AVAILABLE,
                                manifestJson = null
                            )
                        )
                        pluginCount++
                    }
                }.onFailure { Log.w(TAG, "Plugin ref restore skipped: ${it.message}") }
            }
        }
        return ImportResult.Success(favCount, histCount, readCount, collectionCount, bookmarkCount, pluginCount)
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
        settingsRepository.setNotifyCommentsEnabled(settings.notifyComments)
        settingsRepository.setNotifyLikesEnabled(settings.notifyLikes)
        settingsRepository.setNotifyFollowersEnabled(settings.notifyFollowers)
        settingsRepository.setLastSourceId(settings.lastSourceId)
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
        put("durationMs", durationMs)
    }
    private fun ReadChapterEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterNumber", chapterNumber.toDouble()); put("readAt", readAt) }
    private fun ReadingProgressEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterNumber", chapterNumber.toDouble()); put("currentPage", currentPage); put("totalPages", totalPages); put("updatedAt", updatedAt) }
    private fun ReaderAnnotationEntity.toJson() = JSONObject().apply { put("mangaId", mangaId); put("chapterUrl", chapterUrl); put("pageIndex", pageIndex); put("note", note); put("isBookmarked", isBookmarked); put("updatedAt", updatedAt) }

    // Import bounds: crafted backups must not bloat Room rows or smuggle
    // NaN/Infinity into REAL PK columns (NaN != NaN breaks upsert matching
    // and accumulates ghost rows). Violations throw -> per-row runCatching
    // drops the row instead of the import.
    private fun JSONObject.reqId(key: String): String =
        getString(key).take(MAX_ID_LENGTH).also { require(it.isNotBlank()) { "blank $key" } }

    private fun JSONObject.capped(key: String, max: Int): String = optString(key).take(max)

    private fun JSONObject.finiteChapter(key: String): Float {
        val d = getDouble(key)
        require(d.isFinite()) { "non-finite $key" }
        return d.toFloat()
    }

    private fun JSONObject.toFavoriteEntity() = FavoriteEntity(
        mangaId = reqId("mangaId"),
        slug = capped("slug", MAX_TEXT_SHORT).ifBlank { reqId("mangaId") },
        title = capped("title", MAX_TEXT_TITLE).ifBlank { reqId("mangaId") },
        coverUrl = capped("coverUrl", MAX_URL_LENGTH),
        sourceId = capped("sourceId", MAX_TEXT_SHORT),
        addedAt = getLong("addedAt"),
        readChapters = optInt("readChapters"),
        totalChapters = optInt("totalChapters"),
        readingStatus = optString("readingStatus").take(MAX_TEXT_SHORT).takeIf { it.isNotBlank() },
        isFavorite = optBoolean("isFavorite", true)
    )
    private fun JSONObject.toHistoryEntity() = ReadingHistoryEntity(reqId("mangaId"), capped("slug", MAX_TEXT_SHORT), capped("title", MAX_TEXT_TITLE), capped("coverUrl", MAX_URL_LENGTH), capped("sourceId", MAX_TEXT_SHORT), finiteChapter("lastChapterNumber"), capped("lastChapterUrl", MAX_URL_LENGTH), getLong("lastReadAt"), optInt("readChapters"), optInt("totalChapters"), optLong("durationMs"))
    private fun JSONObject.toReadChapterEntity() = ReadChapterEntity(reqId("mangaId"), finiteChapter("chapterNumber"), getLong("readAt"))
    private fun JSONObject.toProgressEntity() = ReadingProgressEntity(reqId("mangaId"), finiteChapter("chapterNumber"), optInt("currentPage"), optInt("totalPages"), getLong("updatedAt"))
    private fun JSONObject.toAnnotationEntity() = ReaderAnnotationEntity(reqId("mangaId"), capped("chapterUrl", MAX_URL_LENGTH), getInt("pageIndex"), capped("note", MAX_NOTE_LENGTH), optBoolean("isBookmarked"), getLong("updatedAt"))

    /**
     * BK-2: absent key keeps the stored set (never blank the library);
     * present-but-empty honors the explicit "disable all".
     * Internal for unit tests.
     */
    internal fun JSONObject.effectiveEnabledSources(stored: Set<String>): Set<String> =
        if (has("enabledSources")) optJSONArray("enabledSources")?.toStringSet() ?: stored else stored

    private fun JSONObject.toMangaCollection(): MangaCollection {
        val id = reqId("id").take(MAX_TEXT_SHORT)
        val name = capped("name", MAX_TEXT_TITLE).also { require(it.isNotBlank()) { "blank name" } }
        val mangaIds = optJSONArray("mangaIds")?.let { arr ->
            (0 until minOf(arr.length(), MAX_COLLECTION_ITEMS)).mapNotNull { i ->
                arr.optString(i).take(MAX_ID_LENGTH).takeIf { it.isNotBlank() }
            }
        } ?: emptyList()
        return MangaCollection(
            id = id,
            name = name,
            description = capped("description", MAX_TEXT_DESC),
            mangaIds = mangaIds,
            isPublic = optBoolean("isPublic", false),
            createdAt = optLong("createdAt"),
            updatedAt = optLong("updatedAt")
        )
    }

    private fun JSONObject.toBookmark(fallbackMangaId: String): Bookmark {
        val mangaId = optString("mangaId").take(MAX_ID_LENGTH).takeIf { it.isNotBlank() } ?: fallbackMangaId
        return Bookmark(
            id = reqId("id").take(MAX_TEXT_SHORT),
            mangaId = mangaId,
            chapterUrl = capped("chapterUrl", MAX_URL_LENGTH),
            pageIndex = optInt("pageIndex"),
            note = capped("note", MAX_NOTE_LENGTH),
            createdAt = optLong("createdAt")
        )
    }

    private fun MangaCollection.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("mangaIds", JSONArray(mangaIds)); put("isPublic", isPublic)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun Bookmark.toJson() = JSONObject().apply {
        put("id", id); put("mangaId", mangaId); put("chapterUrl", chapterUrl)
        put("pageIndex", pageIndex); put("note", note); put("createdAt", createdAt)
    }

    private fun AppSettings.toJson() = JSONObject().apply {
        put("theme", theme.name); put("downloadOnWifiOnly", downloadOnWifiOnly); put("autoDownloadNewChapters", autoDownloadNewChapters)
        put("enableNotifications", enableNotifications); put("enabledSources", JSONArray(enabledSources.toList())); put("onboardingCompleted", onboardingCompleted)
        put("useDynamicColors", useDynamicColors); put("biometricLockEnabled", biometricLockEnabled); put("secureReaderEnabled", secureReaderEnabled)
        put("notificationDeliveryMode", notificationDeliveryMode.name); put("autoCleanupReadDownloads", autoCleanupReadDownloads); put("cleanupAfterHours", cleanupAfterHours)
        put("imageCacheLimitMb", imageCacheLimitMb); put("contentBlacklist", JSONArray(contentBlacklist.toList())); put("spoilerCollapseDefault", spoilerCollapseDefault); put("mutedUserIds", JSONArray(mutedUserIds.toList()))
        put("readingListStatus", readingListStatus); put("favoriteGenres", JSONArray(favoriteGenres))
        put("showLibraryPublic", showLibraryPublic)
        put("notifyComments", notifyComments); put("notifyLikes", notifyLikes); put("notifyFollowers", notifyFollowers)
        put("lastSourceId", lastSourceId)
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
        showLibraryPublic = optBoolean("showLibraryPublic", true),
        notifyComments = optBoolean("notifyComments", true),
        notifyLikes = optBoolean("notifyLikes", true),
        notifyFollowers = optBoolean("notifyFollowers", true),
        lastSourceId = optString("lastSourceId").takeIf { it.isNotBlank() } ?: "azora"
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
    private inline fun JSONArray.forEachObjects(limit: Int = MAX_ROWS_PER_ARRAY, block: (JSONObject) -> Unit) {
        for (i in 0 until minOf(length(), limit)) optJSONObject(i)?.let(block)
    }

    private companion object {
        const val TAG = "LocalBackupManager"

        /** Reject picked files larger than this before readText() (OOM guard). */
        const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L

        /** Rows parsed per array per import (ANR/journal guard). */
        const val MAX_ROWS_PER_ARRAY = 50_000

        const val MAX_ID_LENGTH = 512
        const val MAX_TEXT_SHORT = 256
        const val MAX_TEXT_TITLE = 500
        const val MAX_TEXT_DESC = 500
        const val MAX_URL_LENGTH = 2048
        const val MAX_NOTE_LENGTH = 2000

        /** Items parsed per collection / bookmark list (hostile-size guard). */
        const val MAX_COLLECTION_ITEMS = 5000

        /** Plugin refs parsed per import (hundreds of sources fit; fail-closed cap). */
        const val MAX_PLUGIN_REFS = 500

        /** v4: adds `pluginRefs` (references, never payloads); v1–v3 remain accepted. */
        const val SCHEMA_VERSION = 4
    }
}

// ─── Plugin references (§11A backup-by-reference) ────────────────────────────
// Top-level (no manager instance needed) so the codec is unit-testable without
// Room/Context. References only — payloads always re-sync from distribution.

internal data class PluginRef(
    val id: String,
    val version: Int,
    val origin: com.exapps.mangaworld.core.source.plugins.PluginOrigin
)

private val PLUGIN_REF_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

internal fun pluginRefToJson(
    record: com.exapps.mangaworld.core.source.plugins.PluginIndexRecord
): org.json.JSONObject = org.json.JSONObject().apply {
    put("id", record.id)
    put("version", record.activeVersion ?: 0)
    put("origin", record.origin.name)
}

internal fun org.json.JSONObject.toPluginRef(): PluginRef {
    // Overlong ids are rejected, never truncated: a truncated id would point
    // the restore at the WRONG source.
    val id = optString("id")
    if (id.length > 64 || !PLUGIN_REF_ID_REGEX.matches(id)) {
        throw IllegalArgumentException("bad plugin ref id")
    }
    val version = optInt("version", 0)
    if (version < 1) throw IllegalArgumentException("bad plugin ref version")
    val origin = runCatching {
        com.exapps.mangaworld.core.source.plugins.PluginOrigin.valueOf(optString("origin"))
    }.getOrElse { throw IllegalArgumentException("bad plugin ref origin") }
    return PluginRef(id, version, origin)
}
