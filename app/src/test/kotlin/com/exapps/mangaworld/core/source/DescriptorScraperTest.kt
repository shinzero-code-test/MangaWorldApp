package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.data.remote.scraper.DescriptorScraper
import com.exapps.mangaworld.core.source.plugins.PluginConfigKeys
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.domain.model.SortBy
import com.exapps.mangaworld.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 descriptor execution: a signed MADARA/MANGAREADER manifest drives the
 * shared theme engines — baseUrl, archive path, and selector deviations — with
 * zero Kotlin per source. HTTP is stubbed at the interceptor (no sockets).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DescriptorScraperTest {

    // No solver cookies on JVM: the resolver reads a null-cookie flow.
    private val settings: SettingsRepository = mockk {
        every { getCookies(any()) } returns flowOf(null)
    }

    private class Stub(
        val handler: (Request) -> Pair<Int, String>,
        val seen: MutableList<String> = mutableListOf()
    ) {
        val client: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                seen += req.url.toString()
                val (code, body) = handler(req)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("stub")
                    .body(body.toByteArray(Charsets.UTF_8).toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun manifest(
        engine: SourceEngine = SourceEngine.MANGAREADER,
        baseUrl: String = "https://desc.example",
        config: Map<String, String> = emptyMap()
    ) = ScriptTestSupport.manifest(
        id = "desc",
        engine = engine,
        baseUrl = baseUrl,
        allowedHosts = setOf("desc.example"),
        bridgeApi = null,
        scriptSha256 = null,
        config = config
    )

    private fun scraper(
        stub: Stub,
        engine: SourceEngine = SourceEngine.MANGAREADER,
        baseUrl: String = "https://desc.example",
        config: Map<String, String> = emptyMap()
    ) = DescriptorScraper(manifest(engine, baseUrl, config), stub.client, settings)

    @Test
    fun browseUsesManifestBaseUrlAndListPath() = runTest {
        val stub = Stub({ 200 to "<html><body></body></html>" })
        val s = scraper(
            stub,
            config = mapOf(PluginConfigKeys.LIST_PATH to "/custom-list/")
        )
        assertTrue(s.browseManga(page = 1, genre = null, status = null, type = null, sortBy = SortBy.LATEST).isSuccess)
        assertEquals(
            "https://desc.example/custom-list/?order=update&page=1",
            stub.seen.single()
        )
    }

    @Test
    fun badListPathFallsBackToThemeDefault() = runTest {
        val stub = Stub({ 200 to "<html><body></body></html>" })
        val s = scraper(stub, config = mapOf(PluginConfigKeys.LIST_PATH to "no-slashes"))
        s.browseManga(page = 1, genre = null, status = null, type = null, sortBy = SortBy.LATEST)
        assertEquals(
            "https://desc.example/manga/?order=update&page=1",
            stub.seen.single()
        )
    }

    @Test
    fun madaraChapterSelectorOverrideParsesCustomRows() = runTest {
        val detailHtml = """
            <html><body>
            <div class="summary_image"><img src="https://desc.example/c.jpg"></div>
            <h1 class="entry-title">Demo Manga</h1>
            <ul class="chapters">
              <li class="ch"><a href="https://desc.example/manga/demo/3/">Chapter 3</a></li>
              <li class="ch"><a href="https://desc.example/manga/demo/2/">Chapter 2</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val stub = Stub({ req ->
            if (req.method == "POST") 200 to "" else 200 to detailHtml
        })
        val s = scraper(
            stub,
            engine = SourceEngine.MADARA,
            config = mapOf("chapterListSelector" to "ul.chapters li.ch")
        )
        val detail = s.getMangaDetail("demo").getOrThrow()
        assertEquals("Demo Manga", detail.title)
        assertEquals(listOf(3f, 2f), detail.chapters.map { it.number })
        assertEquals("desc_demo", detail.chapters.first().mangaId)
    }

    @Test
    fun madaraControlWithoutOverrideFindsNoCustomRows() = runTest {
        val detailHtml = """
            <html><body>
            <div class="summary_image"><img src="https://desc.example/c.jpg"></div>
            <h1 class="entry-title">Demo Manga</h1>
            <ul class="chapters">
              <li class="ch"><a href="https://desc.example/manga/demo/3/">Chapter 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val stub = Stub({ req ->
            if (req.method == "POST") 200 to "" else 200 to detailHtml
        })
        val s = scraper(stub, engine = SourceEngine.MADARA)
        val detail = s.getMangaDetail("demo").getOrThrow()
        // Theme default selector matches nothing in custom markup.
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun passthroughIdentity() {
        val stub = Stub({ 200 to "" })
        val s = scraper(stub)
        assertEquals("desc", s.sourceId)
        assertEquals("https://desc.example", s.pluginDescriptor.baseUrl)
    }

    @Test
    fun unsupportedEngineThrows() {
        val stub = Stub({ 200 to "" })
        try {
            scraper(stub, engine = SourceEngine.ASTRO)
            fail("expected no-runner failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no descriptor runner"))
        }
    }
}
