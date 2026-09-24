package com.exapps.mangaworld.core.data.remote.scraper

import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

/** hijala.com — MangaReader theme, Arabic, Cloudflare protected.
 *  Individual manga pages use direct slug URLs: /{slug}/ (not /manga/{slug}/).
 *  Browse page uses /manga/?order=..., search uses /?s=... */
class HijalaScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.HIJALA, settingsRepo, pageSize = 30, searchPageSize = 10) {
    override val listPath: String = "/manga/"

    /**
     * Ownership gate (audit 2026-09-24): Hijala served a chapter row belonging to a
     * different series, and `/lookism-1/` redirects to `/lookism-10/` — chapter URLs
     * are opaque and must never be derived. Accept only rows whose final segment
     * belongs to this series slug; the base empty-fallback keeps healthy lists intact.
     */
    override fun isValidChapterUrl(seriesSlug: String, chapterUrl: String): Boolean =
        hijalaOwnsChapter(seriesSlug, chapterUrl)
}

/**
 * Hijala chapter-ownership rule, extracted pure for unit tests (audit 2026-09-24).
 */
internal fun hijalaOwnsChapter(seriesSlug: String, chapterUrl: String): Boolean {
    val segment = chapterUrl.trimEnd('/').substringAfterLast("/")
    return segment == seriesSlug || segment.startsWith("$seriesSlug-")
}

/** lavascans.com — MangaReader theme, Arabic, Cloudflare protected.
 *  Browse listing is at /browse-manga/ not /manga/. */
class LavaScansScraper @Inject constructor(client: OkHttpClient, settingsRepo: SettingsRepository) :
    MangaReaderBaseScraper(client, MangaSource.LAVASCANS, settingsRepo, pageSize = 32, searchPageSize = 10) {
    override val listPath: String = "/browse-manga/"
}

/** stellarsaber.pro — Flavor theme + encrypted CDN reader (AES-128-GCM, live-verified).
 *
 * Reader flow (report 16): chapter HTML exposes `img.reader__page[data-cdn-url]` slots
 * (transparent-GIF placeholders) plus `flavorReaderData` (`chapterId`, `cdnNonce`).
 * POST `flavor_cdn_get_key` returns a base64 16-byte key; each `.bin` object is
 * 12-byte IV + ciphertext + 16-byte tag. Decryption happens lazily in
 * [com.exapps.mangaworld.core.coil.StellarCdnFetcher] from the per-chapter key;
 * this override only fetches the key and returns the `.bin` slots. Key and nonces
 * are volatile — never persisted, never reused across chapters.
 */
class StellarSaberScraper @Inject constructor(
    client: OkHttpClient,
    settingsRepo: SettingsRepository,
    private val keyStore: com.exapps.mangaworld.core.data.remote.scraper.StellarKeyStore
) : MangaReaderBaseScraper(client, MangaSource.STELLARSABER, settingsRepo, pageSize = 32, searchPageSize = 10) {

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> = runCatching {
        val doc = fetchDocument(chapterUrl, extraHeaders = mapOf("Referer" to "$resolvedBaseUrl/"))
        val slots = doc.select("img.reader__page[data-cdn-url]")
        if (slots.isEmpty()) {
            // Not a Flavor encrypted reader (or markup changed) — base path as fallback.
            return@runCatching super.getChapterPages(chapterUrl).getOrThrow()
        }
        val scripts = doc.select("script").map { it.html() }
        val chapterId = scripts.firstNotNullOfOrNull { html ->
            Regex("""chapterId\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
        } ?: doc.selectFirst("[data-chapter-id]")?.attr("data-chapter-id")?.ifBlank { null }
        val cdnNonce = scripts.firstNotNullOfOrNull { html ->
            Regex("""cdnNonce\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        }
        val ajaxNonce = scripts.firstNotNullOfOrNull { html ->
            Regex("""["']nonce["']\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        }
        if (!chapterId.isNullOrBlank() && !cdnNonce.isNullOrBlank()) {
            // Failure here must not fail the chapter: fall back to the base path below.
            runCatching {
                keyStore.put(chapterUrl, fetchCdnKey(chapterId, cdnNonce, ajaxNonce, chapterUrl))
            }
        }
        slots.mapIndexedNotNull { index, img ->
            val cdnUrl = img.attr("abs:data-cdn-url").ifEmpty { img.attr("data-cdn-url") }
            if (cdnUrl.isBlank()) return@mapIndexedNotNull null
            val url = if (cdnUrl.startsWith("http")) cdnUrl else cdnUrl.absoluteUrl()
            ChapterPage(index = index, url = url, headers = buildImageHeaders(url, chapterUrl))
        }.ifEmpty { super.getChapterPages(chapterUrl).getOrThrow() }
    }

    /**
     * `POST admin-ajax.php action=flavor_cdn_get_key` → base64 16-byte key.
     * Throws on any deviation (caught by the outer runCatching → base-path fallback):
     * volatile nonces and keys must never hard-fail the chapter.
     */
    private suspend fun fetchCdnKey(
        chapterId: String,
        cdnNonce: String,
        ajaxNonce: String?,
        chapterUrl: String
    ): ByteArray = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("action", "flavor_cdn_get_key")
            .addFormDataPart("chapter_id", chapterId)
            .addFormDataPart("nonce", cdnNonce)
        if (!ajaxNonce.isNullOrBlank()) form.addFormDataPart("_ajax_nonce", ajaxNonce)
        val request = okhttp3.Request.Builder()
            .url("$resolvedBaseUrl/wp-admin/admin-ajax.php")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", chapterUrl.encodeForHeader())
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form.build())
            .build()
        val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
            ?: error("stellar: key endpoint returned non-JSON")
        if (!json.optBoolean("success", false)) error("stellar: key endpoint refused")
        val keyB64 = json.optJSONObject("data")?.optString("key").orEmpty()
        val key = runCatching { java.util.Base64.getDecoder().decode(keyB64) }.getOrNull()
        require(key != null && key.size == StellarCrypto.KEY_BYTES) { "stellar: bad key bytes" }
        key
    }
}
