package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.core.source.plugins.PluginConfigKeys
import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.model.SortBy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient

/**
 * Phase 3 generic descriptor runner: executes a signed MADARA/MANGAREADER
 * manifest through the shared theme engines — the deferred-2B piece that lets
 * NEW source ids serve without an app release.
 *
 * Composition, not inheritance: the manifest picks the theme base, and only
 * three deviation axes are honored —
 * - `baseUrl` (entry points, Referers, Jsoup base URIs),
 * - `config[listPath]` (archive path; validated shape, safe default),
 * - selector deviations via the manifest `config` map, consulted BEFORE the
 *   Remote Config override store (signed + versioned beats ambient).
 *
 * Deliberately NOT covered in v1: chapter-list strategy / ajax action / Referer
 * policy / search-action deviations (the theme bases resolve those internally
 * today; threading manifest config through them is engine work, not wiring —
 * tracked as future work). ASTRO/API have no generic theme implementation in
 * the app (builtins there are bespoke Kotlin), so remote manifests naming them
 * stay retained-but-unregistered until runners exist.
 */
class DescriptorScraper(
    manifest: PluginManifest,
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : MangaScraper {

    override val sourceId: String = manifest.id.value
    override val pluginDescriptor: PluginManifest = manifest

    private val delegate: MangaScraper = when (manifest.engine) {
        SourceEngine.MADARA -> object : MadaraBaseScraper(
            client = client,
            sourceId = manifest.id.value,
            defaultBaseUrl = manifest.baseUrl,
            settingsRepo = settingsRepo,
            pluginDescriptor = manifest
        ) {
            override val listPath: String = manifest.listPath()
            override fun remoteSelector(key: String, default: String): String =
                manifest.selector(key) ?: super.remoteSelector(key, default)
        }
        SourceEngine.MANGAREADER -> object : MangaReaderBaseScraper(
            client = client,
            sourceId = manifest.id.value,
            defaultBaseUrl = manifest.baseUrl,
            settingsRepo = settingsRepo,
            pluginDescriptor = manifest
        ) {
            override val listPath: String = manifest.listPath()
            override fun remoteSelector(key: String, default: String): String =
                manifest.selector(key) ?: super.remoteSelector(key, default)
        }
        else -> throw IllegalArgumentException("no descriptor runner for ${manifest.engine.serialName}")
    }

    override suspend fun getHomeData(): Result<HomeData> = delegate.getHomeData()
    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> = delegate.getMangaDetail(slug)
    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> =
        delegate.getChapterPages(chapterUrl)
    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> =
        delegate.searchManga(query, page)
    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> =
        delegate.getMangaByGenre(genre, page)
    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = delegate.browseManga(page, genre, status, type, sortBy)
    override suspend fun getPopularManga(): Result<List<MangaItem>> = delegate.getPopularManga()
    override suspend fun getGenres(): Result<List<String>> = delegate.getGenres()

    companion object {
        /**
         * Archive path override. Strict shape (`/…/`) — anything else falls back
         * to the theme default instead of building broken URLs.
         */
        internal fun PluginManifest.listPath(): String {
            val raw = config[PluginConfigKeys.LIST_PATH] ?: return "/manga/"
            if (!raw.startsWith("/") || !raw.endsWith("/") || raw.length > 64) return "/manga/"
            if (raw.any { it.isWhitespace() }) return "/manga/"
            return raw
        }

        /** Selector deviation: non-blank, bounded; null falls through to RC/default. */
        internal fun PluginManifest.selector(key: String): String? {
            val raw = config[key] ?: return null
            if (raw.isBlank() || raw.length > MAX_SELECTOR_CHARS) return null
            return raw
        }

        private const val MAX_SELECTOR_CHARS = 500
    }
}
