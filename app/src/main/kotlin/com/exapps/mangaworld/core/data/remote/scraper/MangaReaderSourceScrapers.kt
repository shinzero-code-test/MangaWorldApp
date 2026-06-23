package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject

/** hijala.com — MangaReader theme, Arabic, Cloudflare protected.
 *  Individual manga pages use direct slug URLs: /{slug}/ (not /manga/{slug}/).
 *  Browse page uses /manga/?order=..., search uses /?s=... */
class HijalaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.HIJALA, settingsRepo, pageSize = 30, searchPageSize = 10) {
    override val listPath: String = "/manga/"
}

/** lavascans.com — MangaReader theme, Arabic, Cloudflare protected.
 *  Browse listing is at /browse-manga/ not /manga/. */
class LavaScansScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.LAVASCANS, settingsRepo, pageSize = 32, searchPageSize = 10) {
    override val listPath: String = "/browse-manga/"
}

/** stellarsaber.pro — MangaReader/MangaStream theme, Arabic, Cloudflare protected */
class StellarSaberScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.STELLARSABER, settingsRepo, pageSize = 32, searchPageSize = 10)
