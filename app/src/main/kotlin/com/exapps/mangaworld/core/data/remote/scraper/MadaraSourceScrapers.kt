package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject

/** 3asq.online — Madara theme, Arabic */
class Asq3Scraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.ASQ3, settingsRepo, datePattern = "d MMMM، yyyy")

/** mangalik.net — Madara theme, Arabic */
class LekMangaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.LEKMANGA, settingsRepo)

/** lekmanga.online — Madara theme, Arabic — URLs use /comics/{slug}/ */
class LekMangaOnlineScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.LEKMANGAONLINE, settingsRepo) {
    override val listPath: String = "/comics/"
}

/** like-manga.net — Madara theme, Arabic */
class LikeMangaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.LIKEMANGA, settingsRepo)

/** link-manga.net — Madara theme, Arabic */
class LinkMangaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.LINKMANGA, settingsRepo)

/** manga-leko.site — Madara theme, Arabic — URLs use /manhwa/{slug}/ */
class MangaLekoScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.MANGALEKO, settingsRepo) {
    override val listPath: String = "/manhwa/"
}

/** manga-lionz.org — Madara theme, Arabic */
class MangaLionzScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MadaraBaseScraper(client, MangaSource.MANGALIONZ, settingsRepo)
