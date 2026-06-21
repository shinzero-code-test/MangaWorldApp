package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject

/** hijala.com — MangaReader theme, Arabic, Cloudflare protected */
class HijalaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.HIJALA, settingsRepo, pageSize = 30, searchPageSize = 10)

/** lavascans.com — MangaReader theme, Arabic, Cloudflare protected */
class LavaScansScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.LAVASCANS, settingsRepo, pageSize = 32, searchPageSize = 10) {
    override val listPath: String = "/manga/"
}

/** stellarsaber.pro — MangaReader/MangaStream theme, Arabic, Cloudflare protected */
class StellarSaberScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.STELLARSABER, settingsRepo, pageSize = 32, searchPageSize = 10)

/** www.umimanga.com — MangaReader theme, Arabic */
class UmiMangaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.UMIMANGA, settingsRepo, pageSize = 30, searchPageSize = 10)
