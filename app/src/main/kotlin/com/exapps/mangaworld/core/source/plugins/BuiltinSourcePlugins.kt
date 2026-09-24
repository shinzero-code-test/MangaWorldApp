package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.R
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
    id: String,
    baseUrl: String,
    requiresVerification: Boolean,
    engine: SourceEngine,
    allowedHosts: Set<String> = emptySet(),
    config: Map<String, String> = emptyMap()
): PluginManifest = PluginManifest(
    id = SourceId(id),
    version = 1,
    minAppVersion = BuildConfig.VERSION_NAME,
    issuedAt = BUILTIN_ISSUED_AT,
    bridgeApi = null,
    names = emptyMap(),
    logo = null,
    engine = engine,
    engineApi = 1,
    baseUrl = baseUrl,
    requiresVerification = requiresVerification,
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

// ─── Original sources ───────────────────────────────────────────────────────

@Singleton
class OlympusPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = OlympusScraper(client, settingsRepo)
    override val display = SourceDisplay(R.string.source_olympus, R.drawable.olympustaff_com_logo)
    override val descriptor = builtinDescriptor(
        "olympus", "https://olympustaff.com", true, SourceEngine.CUSTOM,
        allowedHosts = setOf("olympustaff.com")
    )
}

@Singleton
class AzoraPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = AzoraScraper(client, settingsRepo)
    override val display = SourceDisplay(R.string.source_azora, R.drawable.azoramoon_com_logo)
    override val descriptor = builtinDescriptor(
        "azora", "https://azorafly.com", false, SourceEngine.ASTRO,
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
    override val display = SourceDisplay(R.string.source_starz, R.drawable.manga_starz_net_logo)
    override val descriptor = builtinDescriptor(
        "starz", "https://starzmanga.com", true, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_mangasid, R.drawable.mangasid_com_logo)
    override val descriptor = builtinDescriptor(
        "mangasid", "https://mangasid.com", false, SourceEngine.ASTRO,
        allowedHosts = setOf("mangasid.com", "api.mangasid.com", "img.mangasid.com")
    )
}

@Singleton
class MeshmangaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = MeshmangaScraper(client, settingsRepo)
    override val display = SourceDisplay(R.string.source_meshmanga, R.drawable.meshmanga_com_logo)
    override val descriptor = builtinDescriptor(
        "meshmanga", "https://meshmanga.com", false, SourceEngine.API,
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
    override val display = SourceDisplay(R.string.source_asq3, R.drawable.asq3_org_logo)
    override val descriptor = builtinDescriptor(
        "asq3", "https://3asq.online", true, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_lekmanga, R.drawable.lek_manga_net_logo)
    override val descriptor = builtinDescriptor(
        "lekmanga", "https://mangalik.net", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_lekmangaonline, R.drawable.lekmanga_online_logo)
    override val descriptor = builtinDescriptor(
        "lekmangaonline", "https://lekmanga.online", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_likemanga, R.drawable.like_manga_net_logo)
    override val descriptor = builtinDescriptor(
        "likemanga", "https://like-manga.net", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_linkmanga, R.drawable.link_manga_net_logo)
    override val descriptor = builtinDescriptor(
        "linkmanga", "https://link-manga.net", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_mangaleko, R.drawable.manga_leko_site_logo)
    override val descriptor = builtinDescriptor(
        "mangaleko", "https://manga-leko.site", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_mangalionz, R.drawable.manga_lionz_org_logo)
    override val descriptor = builtinDescriptor(
        "mangalionz", "https://manga-lionz.org", false, SourceEngine.MADARA,
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
    override val display = SourceDisplay(R.string.source_areascans, R.drawable.ar_kenmanga_com_logo)
    override val descriptor = builtinDescriptor(
        "areascans", "https://ar.kenmanga.com", false, SourceEngine.CUSTOM,
        allowedHosts = setOf("ar.kenmanga.com", "i0.wp.com")
    )
}

@Singleton
class HijalaPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = HijalaScraper(client, settingsRepo)
    override val display = SourceDisplay(R.string.source_hijala, R.drawable.hijala_com_logo)
    override val descriptor = builtinDescriptor(
        "hijala", "https://hijala.com", true, SourceEngine.MANGAREADER,
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
    override val display = SourceDisplay(R.string.source_lavascans, R.drawable.lavascans_com_logo)
    override val descriptor = builtinDescriptor(
        "lavascans", "https://lavascans.com", true, SourceEngine.MANGAREADER,
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
    override val display = SourceDisplay(R.string.source_stellarsaber, R.drawable.stellarsaber_pro_logo)
    override val descriptor = builtinDescriptor(
        "stellarsaber", "https://stellarsaber.pro", true, SourceEngine.MANGAREADER,
        allowedHosts = setOf("stellarsaber.pro", "cdn-stellarsaber.com")
    )
}

@Singleton
class ProComicPlugin @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository
) : SourcePlugin {
    override val scraper: MangaScraper = ProComicScraper(client, settingsRepo)
    override val display = SourceDisplay(R.string.source_procomic, R.drawable.procomic_pro_logo)
    override val descriptor = builtinDescriptor(
        "procomic", "https://procomic.pro", true, SourceEngine.API,
        allowedHosts = setOf(
            "procomic.pro", "app.procomic.pro", "app.prochan.net",
            "app.procomic.net", "cdn2.procomic.pro"
        )
    )
}
