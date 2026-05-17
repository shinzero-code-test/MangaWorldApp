package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Base scraper interface — one implementation per source
 */
interface MangaScraper {
    val source: MangaSource

    suspend fun getHomeData(): Result<HomeData>
    suspend fun getMangaDetail(slug: String): Result<MangaDetail>
    suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>>
    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaItem>>
    suspend fun getMangaByGenre(genre: String, page: Int = 1): Result<List<MangaItem>>
    suspend fun getPopularManga(): Result<List<MangaItem>>
    suspend fun getGenres(): Result<List<String>>
}

class CloudflareChallengeException(
    val domain: String,
    val targetUrl: String,
    message: String = "Cloudflare challenge required for $domain"
) : RuntimeException(message)

/**
 * Base implementation with OkHttp + Jsoup helpers
 */
abstract class BaseScraperImpl(
    protected val client: OkHttpClient,
    override val source: MangaSource,
    protected val settingsRepo: SettingsRepository
) : MangaScraper {

    protected suspend fun fetchDocument(url: String, extraHeaders: Map<String, String> = emptyMap()): Document =
        withContext(Dispatchers.IO) {
            val domain = java.net.URI(url).host ?: source.baseUrl.removePrefix("https://").removePrefix("http://")
            val cookies = settingsRepo.getCookies(domain).first()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en;q=0.9")
                .header("Referer", source.baseUrl + "/")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }

            if (!cookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code
            response.close()
            val doc = Jsoup.parse(body, url)
            val title = doc.title().lowercase()
            val isCf = code == 403 ||
                title.contains("just a moment") ||
                title.contains("attention required") ||
                body.contains("cf-chl", ignoreCase = true)
            if (isCf) {
                throw CloudflareChallengeException(source.baseUrl.removePrefix("https://").removePrefix("http://"), url)
            }
            doc
        }

    protected fun String.absoluteUrl(base: String = source.baseUrl): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$base$this"
            else -> "$base/$this"
        }
    }

    protected fun String.toChapterId() = replace("[^a-zA-Z0-9._-]".toRegex(), "_")

    protected fun String.cleanText() = trim().replace("\\s+".toRegex(), " ")

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }
}
