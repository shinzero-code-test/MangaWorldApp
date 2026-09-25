package com.exapps.mangaworld.core.source.script

import com.exapps.mangaworld.core.source.plugins.PluginManifest
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.domain.model.Chapter
import com.exapps.mangaworld.domain.model.ChapterPage
import com.exapps.mangaworld.domain.model.HomeData
import com.exapps.mangaworld.domain.model.LatestChapterItem
import com.exapps.mangaworld.domain.model.MangaDetail
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.model.MangaStatus
import com.exapps.mangaworld.domain.model.MangaType
import com.exapps.mangaworld.domain.model.SortBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `MangaScraper` over a sandboxed `source.js` (plan §5).
 *
 * Execution shape per call (all inside [withTimeout] on the dedicated script
 * dispatcher — never the main thread, never the shared IO pool):
 * 1. Fresh scope (`initStandardObjects`) + per-call [ScriptBridgeSession].
 * 2. Host-object names deleted, bridge globals installed, cached compiled
 *    script executed, entry function invoked with a plain-data `ctx`.
 * 3. The return value is converted to Kotlin structures ([toKotlin]) and then
 *    validated field-by-field into domain models. Scripts can NEVER emit a
 *    malformed model downstream: wrong shapes, non-https page URLs, CRLF-bearing
 *    headers, NaN numbers, or leaked host objects all fail the call with
 *    [ScriptResultException] (per-call quarantine upstream).
 *
 * Entry mapping: `home` → home/popular, `detail` → detail, `pages` → chapter
 * pages, `search` → search, `browse` → browse/genre, optional `genres` →
 * genres (absent = empty list, still success).
 *
 * Constructed only via [ScriptRunnerFactory.create] (compiles once, fails fast
 * on syntax errors). Pure JVM-safe apart from the injected dispatcher/fetcher.
 */
class ScriptScraper internal constructor(
    override val sourceId: String,
    override val pluginDescriptor: PluginManifest,
    private val script: Script,
    private val sandbox: ScriptContextFactory,
    private val fetcher: ScriptFetcher,
    private val logger: ScriptLogger,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher
) : MangaScraper {

    private val manifest: PluginManifest get() = pluginDescriptor
    private val source get() = manifest.id

    // ─── MangaScraper surface ────────────────────────────────────────────────

    override suspend fun getHomeData(): Result<HomeData> =
        invoke(ScriptContract.ENTRY_HOME, mapOf(ARG_BASE to manifest.baseUrl), ::mapHome)

    override suspend fun getMangaDetail(slug: String): Result<MangaDetail> =
        invoke(
            ScriptContract.ENTRY_DETAIL,
            mapOf(ARG_BASE to manifest.baseUrl, "slug" to slug),
            ::mapDetail
        )

    override suspend fun getChapterPages(chapterUrl: String): Result<List<ChapterPage>> =
        invoke(
            ScriptContract.ENTRY_PAGES,
            mapOf(ARG_BASE to manifest.baseUrl, "chapterUrl" to chapterUrl),
            ::mapPages
        )

    override suspend fun searchManga(query: String, page: Int): Result<List<MangaItem>> =
        invoke(
            ScriptContract.ENTRY_SEARCH,
            mapOf(ARG_BASE to manifest.baseUrl, "query" to query, "page" to page),
            ::mapMangaList
        )

    override suspend fun browseManga(
        page: Int,
        genre: String?,
        status: MangaStatus?,
        type: MangaType?,
        sortBy: SortBy
    ): Result<List<MangaItem>> = invoke(
        ScriptContract.ENTRY_BROWSE,
        buildMap {
            put(ARG_BASE, manifest.baseUrl)
            put("page", page)
            genre?.let { put("genre", it) }
            status?.let { put("status", it.name) }
            type?.let { put("type", it.name) }
            put("sort", sortBy.name)
        },
        ::mapMangaList
    )

    override suspend fun getMangaByGenre(genre: String, page: Int): Result<List<MangaItem>> =
        browseManga(page = page, genre = genre, status = null, type = null, sortBy = SortBy.LATEST)

    override suspend fun getPopularManga(): Result<List<MangaItem>> =
        invoke(ScriptContract.ENTRY_HOME, mapOf(ARG_BASE to manifest.baseUrl)) { raw ->
            mapHome(raw).featured
        }

    override suspend fun getGenres(): Result<List<String>> {
        // Optional extension: scripts without `genres` simply have no genre index.
        val probe = runCatching { hasEntry(ScriptContract.ENTRY_GENRES) }.getOrDefault(false)
        if (!probe) return Result.success(emptyList())
        return invoke(ScriptContract.ENTRY_GENRES, mapOf(ARG_BASE to manifest.baseUrl), ::mapGenres)
    }

    // ─── Execution core ──────────────────────────────────────────────────────

    private suspend fun hasEntry(entry: String): Boolean {
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isCancelled == true }
        return withTimeout(manifest.timeoutMs.toLong()) {
            withContext(dispatcher) {
                sandbox.run(ScriptContract.INSTRUCTION_BUDGET, cancelled) { cx ->
                    val scope = cx.initStandardObjects()
                    script.exec(cx, scope)
                    val fn = scope.get(entry, scope)
                    fn !== Scriptable.NOT_FOUND && fn is Function
                }
            }
        }
    }

    private suspend fun <T> invoke(
        entry: String,
        ctx: Map<String, Any?>,
        map: (Any?) -> T
    ): Result<T> {
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isCancelled == true }
        return try {
            val raw = withTimeout(manifest.timeoutMs.toLong()) {
                withContext(dispatcher) {
                    sandbox.run(ScriptContract.INSTRUCTION_BUDGET, cancelled) { cx ->
                        execute(cx, entry, ctx)
                    }
                }
            }
            // Engine-owned validation runs outside the sandbox on plain data.
            Result.success(map(raw))
        } catch (e: CancellationException) {
            // Timeouts/cancellation stay cancellations (retry/backoff upstream),
            // never silent Result.failures. TimeoutCancellationException is a
            // CancellationException — rethrow before the generic catch.
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun execute(cx: Context, entry: String, ctx: Map<String, Any?>): Any? {
        val scope = cx.initStandardObjects()
        val session = ScriptBridgeSession(manifest, fetcher, logger)
        ScriptBridge.install(cx, scope, session)
        script.exec(cx, scope)
        val fn = scope.get(entry, scope)
        if (fn === Scriptable.NOT_FOUND || fn !is Function) {
            throw ScriptResultException("missing entry '$entry'")
        }
        val ctxObj = cx.newObject(scope)
        ctx.forEach { (k, v) -> ScriptableObject.putProperty(ctxObj, k, v) }
        return toKotlin(fn.call(cx, scope, scope, arrayOf(ctxObj)), depth = 0)
    }

    // ─── JS → Kotlin conversion (plain data only) ────────────────────────────

    internal fun toKotlin(value: Any?, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw ScriptResultException("result too deep")
        return when {
            value == null || value === Undefined.instance || value === Scriptable.NOT_FOUND -> null
            value is String -> value
            value is CharSequence -> value.toString() // ConsString & friends
            value is Boolean -> value
            value is Number -> value
            value is NativeArray -> {
                val n = value.length
                if (n > MAX_ARRAY) throw ScriptResultException("result array too large")
                (0 until n).map { toKotlin(value.get(it.toInt(), value), depth + 1) }
            }
            value is NativeObject -> {
                value.ids.associate { id ->
                    val key = id.toString()
                    if (key.length > MAX_KEY_CHARS) throw ScriptResultException("result key too long")
                    @Suppress("UNCHECKED_CAST")
                    val prop: Any? = when (id) {
                        is Int -> value.get(id, value)
                        else -> value.get(key, value)
                    }
                    key to toKotlin(prop, depth + 1)
                }
            }
            // Anything else (functions, host objects, Map/Set, Dates) must never
            // cross back: fail loudly so a leak can never become silent coercion.
            else -> throw ScriptResultException(
                "unsupported script value (${value.javaClass.simpleName})"
            )
        }
    }

    // ─── Result validators (engine-owned mapping) ────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun asMap(raw: Any?, what: String): Map<String, Any?> =
        (raw as? Map<String, Any?>) ?: throw ScriptResultException("$what must be an object")

    private fun Map<String, Any?>.reqString(key: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ScriptResultException("'$key' must be a non-blank string")

    private fun Map<String, Any?>.optString(key: String, max: Int = 2_048): String? =
        (this[key] as? String)?.take(max)

    private fun Map<String, Any?>.reqFinite(key: String): Double {
        val n = (this[key] as? Number)?.toDouble()
            ?: throw ScriptResultException("'$key' must be a number")
        if (!n.isFinite()) throw ScriptResultException("'$key' must be finite")
        return n
    }

    private fun Map<String, Any?>.optFinite(key: String): Double? =
        (this[key] as? Number)?.toDouble()?.also {
            if (!it.isFinite()) throw ScriptResultException("'$key' must be finite")
        }

    private fun Map<String, Any?>.optBool(key: String): Boolean = this[key] as? Boolean ?: false

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.reqList(key: String): List<Any?> =
        (this[key] as? List<Any?>) ?: throw ScriptResultException("'$key' must be an array")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.optList(key: String): List<Any?> =
        (this[key] as? List<Any?>) ?: emptyList()

    private fun httpsOrBlank(url: String?, what: String): String {
        if (url.isNullOrBlank()) return ""
        if (!url.startsWith("https://")) throw ScriptResultException("$what must be https")
        return url.take(MAX_URL)
    }

    private fun httpsRequired(url: Any?, what: String): String {
        val s = (url as? String)?.takeIf { it.isNotBlank() }
            ?: throw ScriptResultException("$what must be a non-blank string")
        if (!s.startsWith("https://")) throw ScriptResultException("$what must be https")
        return s.take(MAX_URL)
    }

    private fun mapManga(m: Map<String, Any?>): MangaItem {
        val id = m.reqString("id").take(MAX_ID)
        val title = m.reqString("title").take(MAX_TITLE)
        return MangaItem(
            id = id,
            slug = (m.optString("slug", MAX_ID))?.takeIf { it.isNotBlank() } ?: id,
            title = title,
            coverUrl = httpsOrBlank(m.optString("coverUrl"), "coverUrl"),
            source = source,
            genres = m.optStringList("genres", MAX_GENRES),
            status = MangaStatus.from(m.optString("status")),
            type = MangaType.from(m.optString("type")),
            rating = m.optFinite("rating")?.toFloat(),
            latestChapter = m.optFinite("latestChapter")?.toInt(),
            totalChapters = m.optFinite("totalChapters")?.toInt(),
            lastUpdated = m.optFinite("lastUpdated")?.toLong(),
            isNew = m.optBool("isNew"),
            url = (m.optString("url", MAX_URL)).orEmpty(),
            description = (m.optString("description", MAX_DESC)).orEmpty()
        )
    }

    private fun Map<String, Any?>.optStringList(key: String, cap: Int): List<String> =
        optList(key).take(cap).map {
            (it as? String)?.take(MAX_GENRE)?.takeIf { s -> s.isNotBlank() }
                ?: throw ScriptResultException("'$key' must be strings")
        }

    private fun mapMangaList(raw: Any?): List<MangaItem> {
        val list = (raw as? List<Any?>) ?: throw ScriptResultException("expected an array")
        return list.take(MAX_ITEMS).map {
            mapManga(asMap(it, "manga"))
        }
    }

    private fun mapHome(raw: Any?): HomeData {
        val m = asMap(raw, "home")
        return HomeData(
            featured = m.optList("featured").take(MAX_ITEMS).map { mapManga(asMap(it, "manga")) },
            latestChapters = m.optList("latest").take(MAX_ITEMS).map { mapLatest(asMap(it, "chapter")) },
            trending = m.optList("trending").take(MAX_ITEMS).map { mapManga(asMap(it, "manga")) }
        )
    }

    private fun mapLatest(m: Map<String, Any?>): LatestChapterItem {
        val slug = m.reqString("mangaSlug").take(MAX_ID)
        return LatestChapterItem(
            mangaId = (m.optString("mangaId", MAX_ID))?.takeIf { it.isNotBlank() } ?: slug,
            mangaSlug = slug,
            mangaTitle = m.reqString("mangaTitle").take(MAX_TITLE),
            coverUrl = httpsOrBlank(m.optString("coverUrl"), "coverUrl"),
            chapterNumber = m.reqFinite("chapterNumber").toFloat(),
            chapterTitle = m.optString("chapterTitle", MAX_TITLE),
            chapterUrl = httpsRequired(m["chapterUrl"], "chapterUrl"),
            timeAgo = (m.optString("timeAgo", 64)).orEmpty(),
            publishedAt = m.optFinite("publishedAt")?.toLong(),
            source = source,
            isNew = m.optBool("isNew")
        )
    }

    private fun mapDetail(raw: Any?): MangaDetail {
        val m = asMap(raw, "detail")
        val slug = (m.optString("slug", MAX_ID))?.takeIf { it.isNotBlank() }
            ?: m.reqString("id").take(MAX_ID)
        val id = (m.optString("id", MAX_ID))?.takeIf { it.isNotBlank() } ?: slug
        val mangaId = "${source.value}_$slug"
        return MangaDetail(
            id = id,
            slug = slug,
            title = m.reqString("title").take(MAX_TITLE),
            coverUrl = httpsOrBlank(m.optString("coverUrl"), "coverUrl"),
            source = source,
            alternativeTitles = m.optStringList("alternativeTitles", MAX_GENRES),
            authorName = m.optString("authorName", MAX_TITLE),
            artistName = m.optString("artistName", MAX_TITLE),
            description = (m.optString("description", MAX_DESC)).orEmpty(),
            genres = m.optStringList("genres", MAX_GENRES),
            tags = m.optStringList("tags", MAX_GENRES),
            status = MangaStatus.from(m.optString("status")),
            type = MangaType.from(m.optString("type")),
            rating = m.optFinite("rating")?.toFloat(),
            totalChapters = m.optFinite("totalChapters")?.toInt() ?: 0,
            views = m.optString("views", 64),
            lastUpdated = m.optString("lastUpdated", 64),
            chapters = m.optList("chapters").take(MAX_ITEMS).map { mapChapter(asMap(it, "chapter"), mangaId) },
            relatedManga = m.optList("relatedManga").take(MAX_ITEMS).map { mapManga(asMap(it, "manga")) },
            url = (m.optString("url", MAX_URL)).orEmpty()
        )
    }

    private fun mapChapter(m: Map<String, Any?>, mangaId: String): Chapter {
        val number = m.reqFinite("number").toFloat()
        return Chapter(
            id = (m.optString("id", MAX_ID))?.takeIf { it.isNotBlank() } ?: "$mangaId#$number",
            mangaId = mangaId,
            number = number,
            title = m.optString("title", MAX_TITLE),
            url = httpsRequired(m["url"], "chapter url"),
            coverUrl = httpsOrBlank(m.optString("coverUrl"), "coverUrl"),
            date = m.optFinite("date")?.toLong(),
            dateText = m.optString("dateText", 64),
            views = m.optFinite("views")?.toInt()
        )
    }

    private fun mapPages(raw: Any?): List<ChapterPage> {
        val list = (raw as? List<Any?>) ?: throw ScriptResultException("pages must be an array")
        return list.take(MAX_ITEMS).mapIndexed { index, item ->
            val m = asMap(item, "page")
            ChapterPage(
                index = index,
                url = httpsRequired(m["url"], "page url"),
                headers = pageHeaders(m)
            )
        }
    }

    private fun pageHeaders(m: Map<String, Any?>): Map<String, String> {
        val raw = m["headers"] ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val map = (raw as? Map<String, Any?>) ?: throw ScriptResultException("headers must be an object")
        if (map.size > MAX_PAGE_HEADERS) throw ScriptResultException("too many page headers")
        return map.entries.associate { (k, v) ->
            if (k.length > MAX_HEADER || '\r' in k || '\n' in k) {
                throw ScriptResultException("bad page header name")
            }
            val value = (v as? String) ?: throw ScriptResultException("page header must be strings")
            if (value.length > MAX_HEADER || '\r' in value || '\n' in value) {
                throw ScriptResultException("bad page header value")
            }
            k to value
        }
    }

    private fun mapGenres(raw: Any?): List<String> {
        val list = (raw as? List<Any?>) ?: throw ScriptResultException("genres must be an array")
        return list.take(MAX_GENRES).map {
            (it as? String)?.take(MAX_GENRE)?.takeIf { s -> s.isNotBlank() }
                ?: throw ScriptResultException("genres must be strings")
        }
    }

    companion object {
        private const val ARG_BASE = "baseUrl"
        private const val MAX_DEPTH = 25
        private const val MAX_ARRAY = 10_000
        private const val MAX_KEY_CHARS = 128
        private const val MAX_ITEMS = 500
        private const val MAX_ID = 256
        private const val MAX_TITLE = 500
        private const val MAX_URL = 2_048
        private const val MAX_DESC = 20_000
        private const val MAX_GENRE = 64
        private const val MAX_GENRES = 100
        private const val MAX_HEADER = 1_024
        private const val MAX_PAGE_HEADERS = 20
    }
}

/**
 * Builds [ScriptScraper]s: compiles `source.js` once (syntax errors fail here,
 * before any scope exists) and wires the sandbox + fetcher + dispatcher.
 */
@Singleton
class ScriptRunnerFactory @Inject constructor(
    private val sandbox: ScriptContextFactory,
    private val fetcher: ScriptFetcher,
    private val logger: ScriptLogger,
    @ScriptDispatcher private val dispatcher: kotlinx.coroutines.CoroutineDispatcher
) {

    fun create(manifest: PluginManifest, scriptBytes: ByteArray): Result<MangaScraper> {
        if (manifest.engine != com.exapps.mangaworld.core.source.plugins.SourceEngine.SCRIPT) {
            return Result.failure(IllegalArgumentException("not a script manifest"))
        }
        if (scriptBytes.size > ScriptContract.SCRIPT_MAX_BYTES) {
            return Result.failure(ScriptException("source.js exceeds ${ScriptContract.SCRIPT_MAX_BYTES} bytes"))
        }
        val source = try {
            scriptBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return Result.failure(ScriptCompileException("source.js is not UTF-8"))
        }
        // Compile once, execute per call with a fresh scope (stateless scripts
        // stay safe across mid-session swaps).
        val compiled = try {
            sandbox.run { cx ->
                // compileString takes ScriptableObject (not the interface):
                // cast once at the single compile site.
                val scope = cx.initStandardObjects() as ScriptableObject
                cx.compileString(scope, source, "<source.js>", 1, null)
            }
        } catch (e: ScriptException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(ScriptCompileException(e.message?.take(200) ?: "compile failed"))
        }
        return Result.success(
            ScriptScraper(
                sourceId = manifest.id.value,
                pluginDescriptor = manifest,
                script = compiled,
                sandbox = sandbox,
                fetcher = fetcher,
                logger = logger,
                dispatcher = dispatcher
            )
        )
    }
}
