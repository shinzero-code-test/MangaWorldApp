package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.data.remote.scraper.AreaScansScraper
import com.exapps.mangaworld.core.data.remote.scraper.Asq3Scraper
import com.exapps.mangaworld.core.data.remote.scraper.AzoraScraper
import com.exapps.mangaworld.core.data.remote.scraper.HijalaScraper
import com.exapps.mangaworld.core.data.remote.scraper.LavaScansScraper
import com.exapps.mangaworld.core.data.remote.scraper.LekMangaOnlineScraper
import com.exapps.mangaworld.core.data.remote.scraper.LekMangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.LikeMangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.LinkMangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.MangaLekoScraper
import com.exapps.mangaworld.core.data.remote.scraper.MangaLionzScraper
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.MangaSidScraper
import com.exapps.mangaworld.core.data.remote.scraper.MeshmangaScraper
import com.exapps.mangaworld.core.data.remote.scraper.OlympusScraper
import com.exapps.mangaworld.core.data.remote.scraper.ProComicScraper
import com.exapps.mangaworld.core.data.remote.scraper.StarzScraper
import com.exapps.mangaworld.core.data.remote.scraper.StellarSaberScraper
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val BUILTIN_ISSUED_AT = "2026-09-24T00:00:00Z"

/**
 * Shared descriptor factory for shipped sources. Identity, hosts and policy come from
 * the 2026-09-24 source audit (`tmp/analysis/`); display names stay in APK strings and
 * resolve through [SourceDisplayResolver] (descriptor `names` intentionally empty).
 */
private fun builtinDescriptor(
    source: MangaSource,
    engine: SourceEngine,
    allowedHosts: Set<String> = emptySet(),
    config: Map<String, String> = emptyMap()
): PluginManifest = PluginManifest(
    id = SourceId(source.id),
    version = 1,
    minAppVersion = BuildConfig.VERSION_NAME,
    issuedAt = BUILTIN_ISSUED_AT,
    bridgeApi = null,
    names = emptyMap(),
    logo = null,
    engine = engine,
    engineApi = 1,
    baseUrl = source.baseUrl,
    requiresVerification = source.requiresVerification,
    enabledByDefault = true,
    requiresPermission = false,
    config = config,
    apiPaths = emptyMap(),
    allowedHosts = allowedHosts.map { it.lowercase() }.toSet(),
    timeoutMs = 30_000,
    maxResponseMb = 10,
    scriptSha256 = null,
    signatureKeyId = "builtin"
)

private fun displayOf(source: MangaSource) = SourceDisplay(source.nameRes, source.logoRes)

// ─── Original sources ───────────────────────────────────────────────────────

@Singleton
class OlympusPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = OlympusScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.OLYMPUS)
    override val descriptor = builtinDescriptor(
        MangaSource.OLYMPUS, SourceEngine.CUSTOM,
        allowedHosts = setOf("olympustaff.com")
    )
}

@Singleton
class AzoraPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = AzoraScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.AZORA)
    override val descriptor = builtinDescriptor(
        MangaSource.AZORA, SourceEngine.ASTRO,
        allowedHosts = setOf("azorafly.com", "api.azorafly.com", "storage.azorafly.com")
    )
}

@Singleton
class StarzPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    // Live-verified working (metadata + chapters); the audit's 403s were egress-specific.
    override val scraper: MangaScraper = StarzScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.STARZ)
    override val descriptor = builtinDescriptor(
        MangaSource.STARZ, SourceEngine.MADARA,
        allowedHosts = setOf("starzmanga.com", "starz.starzmanga.com"),
        config = mapOf(PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.EMBEDDED)
    )
}

@Singleton
class MangaSidPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = MangaSidScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.MANGASID)
    override val descriptor = builtinDescriptor(
        MangaSource.MANGASID, SourceEngine.ASTRO,
        allowedHosts = setOf("mangasid.com", "api.mangasid.com", "img.mangasid.com")
    )
}

@Singleton
class MeshmangaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = MeshmangaScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.MESHMANGA)
    override val descriptor = builtinDescriptor(
        MangaSource.MESHMANGA, SourceEngine.API,
        allowedHosts = setOf("meshmanga.com", "appswat.com")
    )
}

// ─── Madara family ──────────────────────────────────────────────────────────

@Singleton
class Asq3Plugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = Asq3Scraper(client, settingsRepo)
    override val display = displayOf(MangaSource.ASQ3)
    override val descriptor = builtinDescriptor(
        MangaSource.ASQ3, SourceEngine.MADARA,
        allowedHosts = setOf("3asq.online"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.SLUG_AJAX,
            PluginConfigKeys.CHAPTER_AJAX_PATH to "/ajax/chapters/",
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class LekMangaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = LekMangaScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.LEKMANGA)
    override val descriptor = builtinDescriptor(
        MangaSource.LEKMANGA, SourceEngine.MADARA,
        allowedHosts = setOf("mangalik.net", "io.mangalik.net", "tempsolo.mangalik.net"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.EMBEDDED,
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class LekMangaOnlinePlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = LekMangaOnlineScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.LEKMANGAONLINE)
    override val descriptor = builtinDescriptor(
        MangaSource.LEKMANGAONLINE, SourceEngine.MADARA,
        allowedHosts = setOf("lekmanga.online", "sky.lekmanga.online", "tempmore.lekmanga.online"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.EMBEDDED,
            PluginConfigKeys.LIST_PATH to "/comics/",
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class LikeMangaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = LikeMangaScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.LIKEMANGA)
    override val descriptor = builtinDescriptor(
        MangaSource.LIKEMANGA, SourceEngine.MADARA,
        allowedHosts = setOf("like-manga.net", "likehua.like-manga.net", "templikey.like-manga.net"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.EMBEDDED,
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class LinkMangaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = LinkMangaScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.LINKMANGA)
    override val descriptor = builtinDescriptor(
        MangaSource.LINKMANGA, SourceEngine.MADARA,
        allowedHosts = setOf("link-manga.net", "link.link-manga.net", "tempdarko.link-manga.net"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.EMBEDDED,
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class MangaLekoPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = MangaLekoScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.MANGALEKO)
    override val descriptor = builtinDescriptor(
        MangaSource.MANGALEKO, SourceEngine.MADARA,
        allowedHosts = setOf("manga-leko.site", "moon.manga-leko.site", "templuner.manga-leko.site"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.NUMERIC_ACTION,
            PluginConfigKeys.CHAPTER_ACTION to "manga_get_chapters",
            PluginConfigKeys.LIST_PATH to "/manhwa/",
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

@Singleton
class MangaLionzPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = MangaLionzScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.MANGALIONZ)
    override val descriptor = builtinDescriptor(
        MangaSource.MANGALIONZ, SourceEngine.MADARA,
        allowedHosts = setOf("manga-lionz.org", "lionz.manga-lionz.org", "templeo.manga-lionz.org"),
        config = mapOf(
            PluginConfigKeys.CHAPTER_LIST_STRATEGY to ChapterListStrategy.NUMERIC_ACTION,
            PluginConfigKeys.CHAPTER_ACTION to "manga_get_chapters",
            PluginConfigKeys.IMAGE_REFERER_POLICY to ImageRefererPolicy.CHAPTER,
            PluginConfigKeys.SEARCH_ACTION to "wp-manga-search-manga"
        )
    )
}

// ─── MangaReader family + customs ───────────────────────────────────────────

@Singleton
class AreaScansPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository,
    @ApplicationContext context: android.content.Context
) : SourcePlugin {
    override val scraper: MangaScraper = AreaScansScraper(client, settingsRepo, context)
    override val display = displayOf(MangaSource.AREASCANS)
    override val descriptor = builtinDescriptor(
        MangaSource.AREASCANS, SourceEngine.CUSTOM,
        allowedHosts = setOf("ar.kenmanga.com", "i0.wp.com")
    )
}

@Singleton
class HijalaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = HijalaScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.HIJALA)
    override val descriptor = builtinDescriptor(
        MangaSource.HIJALA, SourceEngine.MANGAREADER,
        allowedHosts = setOf("hijala.com", "blogger.googleusercontent.com"),
        config = mapOf(PluginConfigKeys.LIST_PATH to "/manga/")
    )
}

@Singleton
class LavaScansPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = LavaScansScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.LAVASCANS)
    override val descriptor = builtinDescriptor(
        MangaSource.LAVASCANS, SourceEngine.MANGAREADER,
        allowedHosts = setOf("lavascans.com"),
        config = mapOf(PluginConfigKeys.LIST_PATH to "/browse-manga/")
    )
}

@Singleton
class StellarSaberPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository,
    keyStore: com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
) : SourcePlugin {
    override val scraper: MangaScraper = StellarSaberScraper(client, settingsRepo, keyStore)
    override val display = displayOf(MangaSource.STELLARSABER)
    override val descriptor = builtinDescriptor(
        MangaSource.STELLARSABER, SourceEngine.MANGAREADER,
        allowedHosts = setOf("stellarsaber.pro", "cdn-stellarsaber.com")
    )
}

@Singleton
class ProComicPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = ProComicScraper(client, settingsRepo)
    override val display = displayOf(MangaSource.PROCOMIC)
    override val descriptor = builtinDescriptor(
        MangaSource.PROCOMIC, SourceEngine.API,
        allowedHosts = setOf(
            "procomic.pro", "app.procomic.pro", "app.prochan.net",
            "app.procomic.net", "cdn2.procomic.pro"
        )
    )
}
