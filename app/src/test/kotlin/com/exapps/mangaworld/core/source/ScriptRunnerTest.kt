package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.model.SortBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 fixture harness (plan §11): a demo `source.js` drives the REAL
 * runner (compile → sandbox → bridge → validation) on JVM CI. The script uses
 * inline HTML plus one bridge `fetch` through the fake — no sockets, fully
 * deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptRunnerTest {

    private val source = """
        function home(ctx) {
          var doc = parse('<div class="feat"><a href="/manga/a/">Alpha</a></div>');
          var titles = selectText(doc, 'a');
          var hrefs = selectAttr(doc, 'a', 'href');
          log('info', 'home for ' + titles.length);
          return {
            featured: [{id: 'a', slug: 'a', title: titles[0], coverUrl: 'https://script.example/a.jpg'}],
            latest: [{mangaSlug: 'a', mangaTitle: 'Alpha', chapterNumber: 12, chapterUrl: hrefs[0], timeAgo: 'now'}],
            trending: []
          };
        }
        function detail(ctx) {
          if (ctx.slug === 'bad') { return {oops: 1}; }
          return {
            id: 'a', slug: ctx.slug, title: 'Alpha', coverUrl: '',
            description: 'demo', genres: ['Action'], status: 'مستمر', type: 'manhwa', rating: 4.5,
            chapters: [{number: 12, url: 'https://script.example/manga/a/12/', title: 'Twelve'}]
          };
        }
        function pages(ctx) {
          if (ctx.chapterUrl === 'fn') { return function(){}; }
          if (ctx.chapterUrl === 'crlf') {
            return [{url: 'https://cdn.script.example/p1.jpg', headers: {'Referer': 'a\r\nb'}}];
          }
          if (ctx.chapterUrl === 'plain') {
            return [{url: 'http://cdn.script.example/p1.jpg'}];
          }
          return [{url: 'https://cdn.script.example/p1.jpg', headers: {Referer: 'https://script.example/'}}];
        }
        function search(ctx) {
          var body = fetch('https://script.example/search?q=' + ctx.query);
          var doc = parse(body);
          return selectText(doc, 'a').map(function(t, i) {
            return {id: 's' + i, slug: 's' + i, title: t};
          });
        }
        function browse(ctx) {
          var title = 'B' + ctx.page + (ctx.genre ? '-' + ctx.genre : '');
          return [{id: 'b', slug: 'b', title: title}];
        }
    """.trimIndent()

    private val searchHtml =
        "<html><body><a href=\"/manga/x/\">Xen</a><a href=\"/manga/y/\">Yen</a></body></html>"

    private fun manifestFor(script: String, timeoutMs: Int = 15_000) =
        ScriptTestSupport.manifest(
            timeoutMs = timeoutMs,
            scriptSha256 = com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader.sha256Hex(
                script.toByteArray(Charsets.UTF_8)
            )
        )

    private fun runner(
        script: String = source,
        bodies: Map<String, ByteArray> = mapOf(
            "https://script.example/search?q=one" to searchHtml.toByteArray()
        )
    ) = ScriptRunnerFactory(
        ScriptTestSupport.sandbox,
        ScriptTestSupport.FakeFetcher(bodies.toMutableMap()),
        ScriptTestSupport.logger,
        Dispatchers.Unconfined
    ).create(manifestFor(script), script.toByteArray(Charsets.UTF_8)).getOrThrow()

    @Test
    fun homeMaps() = runTest {
        val home = runner().getHomeData().getOrThrow()
        assertEquals("Alpha", home.featured.single().title)
        assertEquals("scriptpilot", home.featured.single().source.value)
        assertEquals("https://script.example/a.jpg", home.featured.single().coverUrl)
        val latest = home.latestChapters.single()
        assertEquals(12f, latest.chapterNumber)
        assertEquals("https://script.example/manga/a/", latest.chapterUrl)
        assertTrue(home.trending.isEmpty())
    }

    @Test
    fun detailMaps() = runTest {
        val detail = runner().getMangaDetail("a").getOrThrow()
        assertEquals("Alpha", detail.title)
        assertEquals(listOf("Action"), detail.genres)
        assertEquals(MangaStatus.ONGOING, detail.status)
        assertEquals(MangaType.MANHWA, detail.type)
        assertEquals(4.5f, detail.rating)
        val ch = detail.chapters.single()
        assertEquals(12f, ch.number)
        assertEquals("scriptpilot_a", ch.mangaId)
        assertEquals("Twelve", ch.title)
    }

    @Test
    fun malformedDetailFails() = runTest {
        val result = runner().getMangaDetail("bad")
        assertTrue(result.isFailure)
    }

    @Test
    fun pagesMapWithHeaders() = runTest {
        val pages = runner().getChapterPages("https://script.example/manga/a/12/").getOrThrow()
        val page = pages.single()
        assertEquals(0, page.index)
        assertEquals("https://cdn.script.example/p1.jpg", page.url)
        assertEquals("https://script.example/", page.headers["Referer"])
    }

    @Test
    fun crlfHeaderRejected() = runTest {
        assertTrue(runner().getChapterPages("crlf").isFailure)
    }

    @Test
    fun plainHttpPageRejected() = runTest {
        assertTrue(runner().getChapterPages("plain").isFailure)
    }

    @Test
    fun hostObjectReturnRejected() = runTest {
        // A function value crossing back is a leak shape — fail loudly.
        assertTrue(runner().getChapterPages("fn").isFailure)
    }

    @Test
    fun searchRunsFetchThroughBridge() = runTest {
        val items = runner().searchManga("one").getOrThrow()
        assertEquals(listOf("Xen", "Yen"), items.map { it.title })
        assertEquals("scriptpilot", items.first().source.value)
    }

    @Test
    fun browsePassesFilters() = runTest {
        val items = runner().browseManga(
            page = 2, genre = "Action", status = null, type = null, sortBy = SortBy.LATEST
        ).getOrThrow()
        assertEquals("B2-Action", items.single().title)
    }

    @Test
    fun genresAbsentMeansEmpty() = runTest {
        // Optional extension: no `genres` entry → success with no index.
        assertEquals(emptyList<String>(), runner().getGenres().getOrThrow())
    }

    @Test
    fun genresPresentMaps() = runTest {
        val genreScript = source + "\nfunction genres(ctx){ return ['A', 'B']; }"
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(
            manifestFor(genreScript),
            genreScript.toByteArray(Charsets.UTF_8)
        ).getOrThrow()
        assertEquals(listOf("A", "B"), r.getGenres().getOrThrow())
    }

    @Test
    fun missingEntryFails() = runTest {
        val missingEntryScript =
            "function home(ctx){ return {featured: [], latest: [], trending: []}; }"
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(
            manifestFor(missingEntryScript),
            missingEntryScript.toByteArray(Charsets.UTF_8)
        ).getOrThrow()
        assertTrue(r.getHomeData().isSuccess)
        assertTrue(r.getChapterPages("x").isFailure)
    }

    @Test
    fun syntaxErrorFailsAtCreate() {
        val result = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(
            manifestFor("function broken( {"),
            "function broken( {".toByteArray(Charsets.UTF_8)
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun nanNumberRejected() = runTest {
        val script = """
            function home(ctx) {
              return {featured: [], latest: [{mangaSlug: 'a', mangaTitle: 'A', chapterNumber: NaN, chapterUrl: 'https://script.example/m/a/1/'}], trending: []};
            }
            function detail(ctx){ return {id:'a', slug:'a', title:'A'}; }
            function pages(ctx){ return []; }
            function search(ctx){ return []; }
            function browse(ctx){ return []; }
        """.trimIndent()
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(manifestFor(script), script.toByteArray(Charsets.UTF_8)).getOrThrow()
        assertTrue(r.getHomeData().isFailure)
    }

    @Test
    fun handlesDoNotLeakAcrossCalls() = runTest {
        // Double isolation, both structural: the script is re-executed per call
        // (globals like `saved` reset to null on entry), AND the document
        // session is fresh per call (a smuggled handle would be unknown).
        // Either layer failing still fails the call — never leaks a document.
        val script = """
            var saved = null;
            function pages(ctx) {
              if (ctx.chapterUrl === 'second') {
                // Stale handle from the previous call: the fresh per-call
                // session must not know it.
                return selectText(saved, 'a').length;
              }
              saved = parse('<a>x</a>');
              return [{url: 'https://cdn.script.example/p.jpg'}];
            }
            function home(ctx){ return {featured: [], latest: [], trending: []}; }
            function detail(ctx){ return {id:'a', slug:'a', title:'A'}; }
            function search(ctx){ return []; }
            function browse(ctx){ return []; }
        """.trimIndent()
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(manifestFor(script), script.toByteArray(Charsets.UTF_8)).getOrThrow()
        assertTrue(r.getChapterPages("first").isSuccess)
        // Second call reuses the saved handle → unknown-handle failure.
        assertTrue(r.getChapterPages("second").isFailure)
    }

    @Test
    fun instructionBudgetBackstopsAStuckCall() = runTest {
        // Virtual time never advances while the test thread is parked inside
        // the sandbox, so the wall clock CANNOT fire here by construction —
        // the instruction budget must stop the loop instead (failure, not hang).
        val script = """
            function home(ctx){ while(true){ var x = 1 + 1; } }
            function detail(ctx){ return {id:'a', slug:'a', title:'A'}; }
            function pages(ctx){ return []; }
            function search(ctx){ return []; }
            function browse(ctx){ return []; }
        """.trimIndent()
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(manifestFor(script, 200), script.toByteArray(Charsets.UTF_8)).getOrThrow()
        val result = r.getHomeData()
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is com.exapps.mangaworld.core.source.script.ScriptQuotaExceededException
        )
    }

    @Test
    fun cancellationsAreNeverSwallowedIntoResults() = runTest {
        // A fetcher-level cancellation (its own timeout, scope teardown) must
        // propagate out of getHomeData as CancellationException — through the
        // bridge AND the runner — never be wrapped into a Result failure.
        // Deterministic by construction: the fake throws immediately, no
        // parking, no threads, no timing.
        val fetcher = object : com.exapps.mangaworld.core.source.script.ScriptFetcher {
            override suspend fun fetch(
                url: String,
                headers: Map<String, String>,
                maxBytes: Long,
                timeoutMs: Int,
                allowedHosts: Set<String>
            ): com.exapps.mangaworld.core.source.script.ScriptFetcher.Response =
                throw kotlinx.coroutines.TimeoutCancellationException("boom")
        }
        val script = """
            function home(ctx){ fetch('https://script.example/'); return {featured: [], latest: [], trending: []}; }
            function detail(ctx){ return {id:'a', slug:'a', title:'A'}; }
            function pages(ctx){ return []; }
            function search(ctx){ return []; }
            function browse(ctx){ return []; }
        """.trimIndent()
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            fetcher,
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        ).create(manifestFor(script), script.toByteArray(Charsets.UTF_8)).getOrThrow()
        try {
            r.getHomeData()
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected: TimeoutCancellationException propagates.
        }
    }

    @Test
    fun executionHonorsInjectedDispatcher() = runTest {
        // The runner must execute on the dedicated pool, never the caller's
        // thread by accident: a counting dispatcher proves the wiring.
        var dispatched = 0
        val counting = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatched++
                block.run()
            }
        }
        val r = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            counting
        ).create(manifestFor(source), source.toByteArray(Charsets.UTF_8)).getOrThrow()
        assertTrue(r.getHomeData().isSuccess)
        assertTrue("script never ran on the injected dispatcher", dispatched > 0)
    }

    @Test
    fun coldAndWarmCallTimingsLogged() = runTest {
        val factory = ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        )
        val bytes = source.toByteArray(Charsets.UTF_8)
        val t0 = System.currentTimeMillis()
        val r = factory.create(manifestFor(source), bytes).getOrThrow()
        val tCompile = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        r.getHomeData().getOrThrow()
        val tCold = System.currentTimeMillis() - t1
        val t2 = System.currentTimeMillis()
        r.getHomeData().getOrThrow()
        val tWarm = System.currentTimeMillis() - t2
        println("script-perf compile=${tCompile}ms cold=${tCold}ms warm=${tWarm}ms scriptBytes=${bytes.size}")
        assertTrue(tCompile < 30_000)
    }
}
