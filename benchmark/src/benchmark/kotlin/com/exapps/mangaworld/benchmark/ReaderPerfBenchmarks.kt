package com.exapps.mangaworld.benchmark

import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device measure gate for the Stage-2 perf work (U-26 / U-27).
 *
 * Spec: `tmp/review/stage2/perf.md` §"Measure spec" (MB-1 … MB-5).
 * The JVM suites (`SmartCropScannerTest`, `ReaderImageConfigTest`,
 * `LocalPushFingerprintTest`, `MangaPagingSourceDedupTest`) prove the
 * *logic*; these tests prove the *numbers on a real device*.
 *
 * ── Reading the results ────────────────────────────────────────────────────
 * Every test is black-box: the harness APK is a separate, self-instrumenting
 * APK (MacrobenchmarkRule refuses anything else), so it cannot call into the
 * app. Measurements therefore come from the platform (frame timings, /proc,
 * dumpsys netstats, dumpsys gfxinfo) — see benchmark/README.md §"Fidelity"
 * for the exact-vs-proxy table and for the app-side hooks that would turn a
 * proxy into the exact assertion of the spec.
 *
 * A scenario that cannot reach its fixture (site unreachable, empty list,
 * Cloudflare wall, anonymous session) reports a JUnit **skip**, never a
 * green number: a benchmark that measured nothing must not look like a pass.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderPerfBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * No `:baselineprofile` module exists in this repo and creating one is
     * out of scope for this lane, so `Partial(BaselineProfileMode.Require)`
     * cannot be used: it would fail the run instead of measuring anything.
     *
     * `None()` performs NO pre-compilation: the app's saved ART profile is
     * reset and the app runs JIT-only, so every iteration starts from the
     * same cold-compilation state (the honest default for a repo that ships
     * no baseline profile). `androidx.profileinstaller` IS already a
     * dependency of the app, so adding a profile later is a pure addition.
     */
    private val compilationMode = CompilationMode.None()

    // ── scenario sizes ──────────────────────────────────────────────────────

    /** MB-1: "scroll 20 pages" (spec). */
    private val pagesToScroll = 20

    /** MB-2: "200-strip vertical scroll" (spec). */
    private val strips = 200

    /** MB-3: pages scrolled per filter mode. */
    private val pagesPerFilterMode = 10

    /** MB-5: "browse 5 pages" (spec). */
    private val browsePages = 5

    /** Time given to the reader's prefetcher to warm the next pages. */
    private val prefetchSettleMs = 8_000L

    /** Time given to the app to go quiet after a navigation. */
    private val settleMs = 6_000L

    // ── budgets ─────────────────────────────────────────────────────────────

    /** Bytes the display phase may move: the pages were prefetched. */
    private val displayNetBudgetBytes = 64L * 1024L

    /** CPU per displayed page; a re-decode of a 4096px page costs far more. */
    private val displayCpuBudgetMsPerPage = 400L

    /** Hard stop for the long-scroll memory guard. */
    private val maxPssGrowthKb = 1024L * 1024L

    /** Jank budget: SMART_CROP may not be meaningfully worse than NONE. */
    private val jankMargin = 0.15

    // ── tests ───────────────────────────────────────────────────────────────

    /**
     * Harness smoke test: the app must install, launch and be measurable.
     *
     * Not one of MB-1…MB-5 — it exists so that a broken harness (or a
     * broken app build) is distinguishable from a broken measurement.
     */
    @Test
    fun harnessColdStartSmoke() {
        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(StartupTimingMetric()),
            iterations = 3,
            compilationMode = compilationMode,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() }
        )
    }

    /**
     * MB-1 (U-26): the reader's prefetch must feed the DISPLAY path.
     *
     * The prefetcher warms the next pages through the shared ImageLoader;
     * the display path then renders them. With one loader and one cache
     * keyspace, the display is served from the memory cache: no download,
     * and no second decode.
     *
     * Black-box signals:
     *  * network bytes moved while the pages are displayed (must be ~0 —
     *    the prefetched pages must not be fetched again);
     *  * CPU burnt per displayed page (a memory-cache hit renders without
     *    decoding; a cache miss decodes a 4096px bitmap, which is orders
     *    of magnitude more CPU).
     *
     * Fidelity: the exact assertion of the spec is Coil's
     * `dataSource == MEMORY_CACHE` hit ratio, which needs an
     * `EventListener` inside the app (out of this lane's scope — see
     * benchmark/README.md §"Fidelity").
     */
    @Test
    fun mb1_prefetchDisplayReusesDecodedPage() {
        var pagesAdvanced = 0
        var displayCpuMs: Long? = null
        var displayBytes: Long? = null

        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(FrameTimingMetric()),
            iterations = 1,
            compilationMode = compilationMode,
            setupBlock = {
                val reason = Bench.openReader()
                assumeTrue("MB-1 skipped: reader fixture unavailable ($reason)", reason == null)
                Thread.sleep(prefetchSettleMs)
            },
            measureBlock = {
                val cpuBefore = Bench.appCpuTimeMs()
                val bytesBefore = Bench.appNetworkBytes()
                pagesAdvanced = Bench.scrollReaderPages(pagesToScroll)
                val cpuAfter = Bench.appCpuTimeMs()
                val bytesAfter = Bench.appNetworkBytes()
                // A probe that could not be read stays null: "unknown" must
                // never be folded into "zero", or the budget below would pass
                // without measuring anything.
                if (cpuBefore != null && cpuAfter != null) {
                    displayCpuMs = (cpuAfter - cpuBefore).coerceAtLeast(0L)
                }
                if (bytesBefore != null && bytesAfter != null) {
                    displayBytes = (bytesAfter - bytesBefore).coerceAtLeast(0L)
                }
            }
        )

        val cpuMs = displayCpuMs
        val bytes = displayBytes
        val cpuPerPage = (cpuMs ?: 0L) / maxOf(1, pagesAdvanced)
        Log.i(
            Bench.TAG,
            "MB-1 display: pages=$pagesAdvanced cpu=${cpuMs ?: -1}ms " +
                "(${cpuPerPage}ms/page) bytes=${bytes ?: -1}"
        )

        assertTrue(
            "MB-1: the reader advanced only $pagesAdvanced page(s), expected $pagesToScroll",
            pagesAdvanced >= pagesToScroll
        )
        // Both probes are REQUIRED: without the CPU number the per-page
        // budget is meaningless, and without the traffic number the "the
        // prefetched pages were not fetched again" claim is unproven.
        assumeTrue(
            "MB-1 skipped: the platform did not expose the app's CPU time " +
                "(/proc/<pid>/stat was not readable)",
            cpuMs != null
        )
        assumeTrue(
            "MB-1 skipped: the platform did not expose per-uid traffic " +
                "counters (dumpsys netstats carried no byte counter for the app)",
            bytes != null
        )

        assertTrue(
            "MB-1: the display phase moved $bytes bytes — the prefetched pages were fetched again",
            bytes!! <= displayNetBudgetBytes
        )
        assertTrue(
            "MB-1: $cpuPerPage ms per displayed page exceeds $displayCpuBudgetMsPerPage ms/page — " +
                "the display path is decoding instead of reusing the prefetched bitmap",
            cpuPerPage <= displayCpuBudgetMsPerPage
        )
    }

    /**
     * MB-2 (U-27a): a 200-strip vertical scroll must not OOM.
     *
     * The U-27(a) guards are the 4096px decode cap (an uncapped 1080x8000
     * webtoon strip is ~34MB per bitmap) and the single memory cache. The
     * failure mode this test exists for is the app being killed by the
     * low-memory killer in the middle of a long scroll.
     *
     * The harness fails the test by itself if the app process dies during
     * the measurement (MacrobenchmarkRule detects the death), so the
     * explicit assertions here cover the quieter failures: an OOM inside
     * the app, and an unbounded memory growth.
     */
    @Test
    fun mb2_longStripScrollNoOom() {
        var pssBeforeKb: Long? = null
        var pssAfterKb: Long? = null

        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(FrameTimingMetric(), FrameTimingGfxInfoMetric()),
            iterations = 1,
            compilationMode = compilationMode,
            setupBlock = {
                val reason = Bench.openReader()
                assumeTrue("MB-2 skipped: reader fixture unavailable ($reason)", reason == null)
                Thread.sleep(settleMs)
            },
            measureBlock = {
                pssBeforeKb = Bench.appPssKb()
                Bench.resetGfxInfo()
                Bench.swipeUp(strips)
                pssAfterKb = Bench.appPssKb()
            }
        )

        val before = pssBeforeKb
        val after = pssAfterKb
        val stats = Bench.gfxInfo()
        Log.i(
            Bench.TAG,
            "MB-2 scroll: strips=$strips pss=${before ?: -1}KB->${after ?: -1}KB " +
                "frames=${stats?.second ?: -1} janky=${stats?.first ?: -1}"
        )

        assertTrue(
            "MB-2: the app process is gone after $strips strips",
            Bench.appPid() != null
        )
        val log = Bench.logcat()
        val oom = log.contains("OutOfMemoryError") || log.contains("Failed to allocate a")
        assertTrue("MB-2: the app log contains an out-of-memory failure", !oom)
        assumeTrue(
            "MB-2 skipped: the platform did not expose the app's PSS " +
                "(dumpsys meminfo carried no TOTAL line)",
            before != null && after != null
        )
        val growthKb = (after!! - before!!).coerceAtLeast(0L)
        assertTrue(
            "MB-2: PSS grew by ${growthKb / 1024}MB during $strips strips",
            growthKb <= maxPssGrowthKb
        )
    }

    /**
     * MB-3 (U-27b): SMART_CROP must not be jankier than NONE.
     *
     * SMART_CROP attaches a transformation, which forces a software bitmap
     * (`allowHardware(false)`) — the transform reads pixels. NONE keeps
     * hardware bitmaps. The spec's comparison is therefore
     * "jank(SMART_CROP) <= jank(NONE)".
     *
     * The jank ratio is read per phase from `dumpsys gfxinfo` (the
     * platform's own frame statistics, reset before each phase), so the
     * comparison is not distorted by the filter switch itself. The
     * macrobenchmark frame metrics cover the whole window as a companion
     * signal.
     */
    @Test
    fun mb3_smartCropScrollJankVersusNone() {
        var noneStats: Pair<Int, Int>? = null
        var cropStats: Pair<Int, Int>? = null

        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(FrameTimingMetric(), FrameTimingGfxInfoMetric()),
            iterations = 1,
            compilationMode = compilationMode,
            setupBlock = {
                val reason = Bench.openReader()
                assumeTrue("MB-3 skipped: reader fixture unavailable ($reason)", reason == null)
                Thread.sleep(settleMs)
            },
            measureBlock = {
                val switched = Bench.selectReaderFilter(Bench.LABEL_FILTER_NONE)
                assumeTrue("MB-3 skipped: the reader filter chip was not reachable", switched)
                Thread.sleep(2_000)
                Bench.resetGfxInfo()
                Bench.scrollReaderPages(pagesPerFilterMode)
                noneStats = Bench.gfxInfo()

                val switchedToCrop = Bench.selectReaderFilter(Bench.LABEL_FILTER_SMART_CROP)
                assumeTrue("MB-3 skipped: the SMART_CROP chip was not reachable", switchedToCrop)
                Thread.sleep(2_000)
                Bench.resetGfxInfo()
                Bench.scrollReaderPages(pagesPerFilterMode)
                cropStats = Bench.gfxInfo()
            }
        )

        val none = noneStats
        val crop = cropStats
        if (none == null || crop == null) {
            assumeTrue("MB-3 skipped: gfxinfo reported no frames", false)
            return
        }
        if (none.second <= 0 || crop.second <= 0) {
            assumeTrue(
                "MB-3 skipped: gfxinfo reported no frames (none=$none crop=$crop)",
                false
            )
            return
        }
        val noneRatio = none.first.toDouble() / none.second
        val cropRatio = crop.first.toDouble() / crop.second
        Log.i(
            Bench.TAG,
            "MB-3 jank: NONE=${none.first}/${none.second} (${"%.2f".format(noneRatio)}) " +
                "SMART_CROP=${crop.first}/${crop.second} (${"%.2f".format(cropRatio)})"
        )

        assertTrue(
            "MB-3: SMART_CROP is jankier than NONE (${"%.2f".format(cropRatio)} vs " +
                "${"%.2f".format(noneRatio)})",
            cropRatio <= noneRatio + jankMargin
        )
    }

    /**
     * MB-4 (U-27c): the second of two consecutive pushes must not read the
     * remote collections again.
     *
     * The reader pushes the local snapshot when a chapter is loaded
     * (`FirebaseSyncManager.pushLocalSnapshot`, debounced path). Opening the
     * same chapter twice therefore produces two pushes: the second one
     * must be materially cheaper than the first.
     *
     * Fidelity: the spec asks for a counter on `fetchAllCollection`, which
     * is a private function of the sync manager — an app-side hook that is
     * out of this lane's scope. The black-box proxy is the app's remote
     * traffic around each push. Note that the app only pushes for a
     * SIGNED-IN (non-anonymous) session: on an anonymous device the sync
     * path is inert and the test reports a skip.
     */
    @Test
    fun mb4_secondIdenticalPushSkipsRemoteReads() {
        var firstPushBytes = -1L
        var secondPushBytes = -1L

        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(FrameTimingMetric()),
            iterations = 1,
            compilationMode = compilationMode,
            setupBlock = {
                val reason = Bench.openReader()
                assumeTrue("MB-4 skipped: reader fixture unavailable ($reason)", reason == null)
                // Let the first push (and the page images) settle, then take
                // a quiet-window sample of the app's remote traffic.
                Thread.sleep(settleMs)
                firstPushBytes = Bench.networkBytesOverWindow(5_000)
            },
            measureBlock = {
                // Re-open the same chapter: the reader pushes again.
                Bench.device().pressBack()
                Thread.sleep(2_000)
                val chapter = Bench.waitFor(By.textStartsWith(Bench.LABEL_CHAPTER), 20_000)
                assumeTrue("MB-4 skipped: could not re-open the chapter", chapter != null)
                runCatching { chapter?.click() }
                Thread.sleep(settleMs)
                secondPushBytes = Bench.networkBytesOverWindow(5_000)
            }
        )

        Log.i(
            Bench.TAG,
            "MB-4 push traffic: first=${firstPushBytes}B second=${secondPushBytes}B"
        )

        assumeTrue(
            "MB-4 skipped: the platform did not expose per-uid traffic counters, " +
                "or the app has no named session (anonymous), or the first push " +
                "moved no traffic — the sync path is inert on this device",
            firstPushBytes > 0
        )
        assumeTrue(
            "MB-4 skipped: the platform did not expose per-uid traffic counters " +
                "for the second push window",
            secondPushBytes >= 0
        )
        assertTrue(
            "MB-4: the second push moved $secondPushBytes bytes, not less than the " +
                "first push ($firstPushBytes bytes) — the skip guard did not fire",
            secondPushBytes < firstPushBytes
        )
    }

    /**
     * MB-5 (U-27d): browsing 5 pages must not cost 3 requests per page.
     *
     * U-27(d) memoises the overlapping filtered-browse windows, so page
     * N -> N+1 costs ONE request instead of three.
     *
     * Fidelity: the exact assertion of the spec is a request COUNT per
     * source. The app only logs its HTTP requests in DEBUG builds
     * (`core/di/Modules.kt`), and MacrobenchmarkRule rejects a debuggable
     * app under test — so the exact counter lives in the debug-paired
     * instrumentation test (`src/debug/.../BrowseRequestBudgetTest.kt`),
     * while this test covers the browse scenario on the release-like build
     * and reports the traffic it moved.
     */
    @Test
    fun mb5_browsePagesNetworkBudget() {
        var bytesBefore: Long? = null
        var bytesAfter: Long? = null
        var requestsPerHost: Map<String, Int> = emptyMap()

        benchmarkRule.measureRepeated(
            packageName = Bench.APP_ID,
            metrics = listOf(FrameTimingMetric()),
            iterations = 1,
            compilationMode = compilationMode,
            setupBlock = {
                val reason = Bench.openBrowseTabWithStatusFilter()
                assumeTrue("MB-5 skipped: browse fixture unavailable ($reason)", reason == null)
                Thread.sleep(settleMs)
                // The logcat is cleared AFTER the fixture settled: the first
                // page load is not part of the measured window (it would add
                // the three subpage fetches the budget is about).
                Bench.clearLogcat()
            },
            measureBlock = {
                bytesBefore = Bench.appNetworkBytes()
                Bench.scrollGrid(browsePages)
                bytesAfter = Bench.appNetworkBytes()
                requestsPerHost = Bench.loggedRequestsPerHost(Bench.logcat())
            }
        )

        Log.i(
            Bench.TAG,
            "MB-5 browse: pages=$browsePages bytes=${bytesBefore ?: -1}->${bytesAfter ?: -1} " +
                "requests=$requestsPerHost"
        )

        // The release-like app does not log its requests, so the request
        // counter is empty here (the exact counter is the debug variant).
        // What IS available is the traffic, and it is required: without it
        // this case would assert nothing at all.
        assumeTrue(
            "MB-5 skipped: the platform did not expose per-uid traffic counters " +
                "(dumpsys netstats carried no byte counter for the app)",
            bytesBefore != null && bytesAfter != null
        )
        val movedBytes = (bytesAfter!! - bytesBefore!!).coerceAtLeast(0L)
        assertTrue(
            "MB-5: the browse phase moved no traffic at all for $browsePages pages — " +
                "the fixture was not live, so nothing was measured",
            movedBytes > 0
        )
        val sourceRequests = requestsPerHost[Bench.DEFAULT_SOURCE_HOST] ?: 0
        Log.i(
            Bench.TAG,
            "MB-5 browse listing requests: ${Bench.DEFAULT_SOURCE_HOST}=$sourceRequests " +
                "(budget ${browsePages + 2})"
        )
        if (sourceRequests > 0) {
            // Only reachable when the app runs with the OkHttp request log
            // enabled (debug builds); the exact budget of the spec applies.
            assertTrue(
                "MB-5: ${Bench.DEFAULT_SOURCE_HOST} needed $sourceRequests listing requests " +
                    "for $browsePages pages (budget ${browsePages + 2})",
                sourceRequests <= browsePages + 2
            )
        }
    }
}
