package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.script.ScriptContextFactory
import com.exapps.mangaworld.core.source.script.ScriptLogger
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 3 canary gate: the promoted `manonga` script payload verifies and
 * executes through the REAL engine on JVM CI (plan §11 — fixture smoke).
 *
 * Reads the dashboard staging copy (source of truth pre-promote) plus the
 * promoted public copy and asserts they agree where they must:
 * - `source.js` bytes identical staging ↔ public (drift fails loudly);
 * - promoted `plugin.json` verifies against the PINNED key via the real
 *   `ManifestParser` (same code path as devices) on production capabilities;
 * - `scriptSha256` pins the exact promoted bytes (checked again at build);
 * - all five entries + `genres` execute against CAPTURED fixtures through a
 *   fake fetcher (no sockets) with shaped assertions.
 *
 * Paths resolve like `BundledPilotTest`: unit-test workdir is the `:app`
 * module dir, with a repo-root fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptCanaryTest {

    private fun dashboardFile(vararg parts: String): File {
        val rel = "dashboard/" + parts.joinToString("/")
        // Robust to any test workdir: walk UP from user.dir (canonicalized, so
        // symlinks and `..` cannot mislead) looking for the dashboard tree,
        // plus the plain relative path as a last resort.
        val start = runCatching { File(System.getProperty("user.dir")).canonicalFile }
            .getOrElse { File(".").absoluteFile }
        val chain = generateSequence(start) { it.parentFile }.take(6).toList()
        val candidates = chain.map { File(it, rel) } + File(rel)
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "canary file missing: $rel (start=$start " +
                    "children=${start.list()?.take(12)} tried ${candidates.map { it.path }})"
            )
    }

    private fun staging(name: String): ByteArray =
        dashboardFile("plugins", "_incoming", "manonga", name).readBytes()

    private fun published(name: String): ByteArray =
        dashboardFile("plugins", "manonga", "v1", name).readBytes()

    private fun fixture(name: String): ByteArray =
        dashboardFile("plugins", "_incoming", "manonga", "fixtures", name).readBytes()

    @Test
    fun stagedAndPromotedPayloadsAgree() {
        // source.js must be byte-identical staging ↔ public; plugin.json differs
        // ONLY by the promote-time fields (scriptSha256 pin + signature).
        assertTrue(
            staging("source.js").contentEquals(published("source.js"))
        )
        val staged = String(staging("plugin.json"), Charsets.UTF_8)
        val public = String(published("plugin.json"), Charsets.UTF_8)
        assertTrue(public.contains("\"signature\": \"ed25519:official-1:"))
        assertTrue(!staged.contains("ed25519:official-1:"))
    }

    private fun verifiedManifest(): com.exapps.mangaworld.core.source.plugins.PluginManifest {
        val bytes = published("plugin.json")
        val parser = ManifestParser(
            trustedKeys = PluginTrust.pinnedKeys(),
            host = PluginTrust.productionCapabilities("9.1.0")
        )
        val result = parser.parseAndVerify(bytes)
        assertTrue(
            "promoted canary must verify: $result",
            result is ManifestResult.Valid
        )
        result as ManifestResult.Valid
        assertEquals("manonga", result.manifest.id.value)
        assertEquals(1, result.manifest.version)
        // The pin binds the exact promoted bytes (checked again at build).
        assertEquals(
            result.manifest.scriptSha256,
            ScriptPluginLoader.sha256Hex(published("source.js")).lowercase()
        )
        return result.manifest
    }

    private fun runner(): com.exapps.mangaworld.core.data.remote.scraper.MangaScraper {
        val manifest = verifiedManifest()
        val factory = ScriptRunnerFactory(
            ScriptContextFactory(),
            ScriptTestSupport.FakeFetcher(
                mutableMapOf(
                    "https://manonga.online/" to fixture("home.html"),
                    "https://manonga.online/manga-list?page=1" to fixture("browse-p1.html"),
                    "https://manonga.online/search?query=naruto" to fixture("search-naruto.json"),
                    "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/" to
                        fixture("detail-a-dimwitted-monk.html"),
                    "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44" to
                        fixture("pages-ch44.html"),
                    "https://manonga.online/manga-list" to fixture("browse-p1.html")
                )
            ),
            ScriptLogger { _, _, _ -> },
            RecordingPluginTelemetry(),
            Dispatchers.Unconfined
        )
        return factory.create(manifest, published("source.js")).getOrThrow()
    }

    @Test
    fun homeServes() = runTest {
        val home = runner().getHomeData().getOrThrow()
        assertTrue(home.featured.isNotEmpty())
        assertEquals("One Piece", home.featured.first().title)
        assertEquals("one-piece-", home.featured.first().slug)
        assertTrue(home.latestChapters.isNotEmpty())
        val latest = home.latestChapters.first()
        assertEquals("one-piece-", latest.mangaSlug)
        assertTrue(latest.chapterUrl.startsWith("https://manonga.online/"))
    }

    @Test
    fun browseServes() = runTest {
        val items = runner().browseManga(
            page = 1, genre = null, status = null, type = null,
            sortBy = com.exapps.mangaworld.domain.model.SortBy.LATEST
        ).getOrThrow()
        assertTrue(items.isNotEmpty())
        assertEquals("2-jikanme-hoken-taiiku", items.first().slug)
        assertTrue(items.first().coverUrl.startsWith("https://www.onma.top/"))
    }

    @Test
    fun searchServes() = runTest {
        val items = runner().searchManga("naruto").getOrThrow()
        assertTrue(items.any { it.title == "Naruto" && it.slug == "naruto_" })
    }

    @Test
    fun detailServes() = runTest {
        val detail = runner().getMangaDetail("a-dimwitted-monk-fell-from-heaven").getOrThrow()
        assertEquals("A \"Dimwitted\" Monk fell from Heaven", detail.title)
        assertEquals(44, detail.chapters.size)
        assertEquals(44f, detail.chapters.first().number)
        assertTrue(detail.chapters.first().url.startsWith("https://manonga.online/"))
        assertTrue(detail.genres.contains("كوميدي"))
    }

    @Test
    fun pagesServeDeduped() = runTest {
        val pages = runner().getChapterPages(
            "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44"
        ).getOrThrow()
        // 9 imgs in fixture, one duplicate + one logo: 8 unique page images.
        assertEquals(8, pages.size)
        assertTrue(pages.all { it.url.startsWith("https://www.onma.top/uploads/manga/") })
        assertEquals(
            "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44",
            pages.first().headers["Referer"]
        )
    }

    @Test
    fun genresServe() = runTest {
        val genres = runner().getGenres().getOrThrow()
        assertTrue(genres.contains("أكشن"))
        assertTrue(genres.size >= 30)
    }
}
