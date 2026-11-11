package com.exapps.mangaworld.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MB-5 (U-27d) — the EXACT request counter.
 *
 * This variant is paired with the app's DEBUG build, which is what makes
 * the exact assertion possible: the app only enables OkHttp's request log
 * in debug builds (`core/di/Modules.kt`: HttpLoggingInterceptor at BASIC
 * when `BuildConfig.DEBUG`). MacrobenchmarkRule rejects a debuggable app
 * under test, so the release-like macrobenchmark build cannot see the
 * request log — that is why this test exists next to
 * `ReaderPerfBenchmarks.mb5_browsePagesNetworkBudget`.
 *
 * The assertion of the spec: browsing 5 pages must cost at most
 * `pages + 2` HTTP requests PER SOURCE. Before U-27(d) the filtered browse
 * fetched `page..page+2` per source on every page load, so 5 pages cost
 * ~15 requests per source; the memoised window makes it ~5.
 *
 * Run it with:
 *   ./gradlew :benchmark:connectedDebugAndroidTest
 * or, in CI, by installing the debug APKs and running the instrumentation
 * (see benchmark/README.md).
 */
@RunWith(AndroidJUnit4::class)
class BrowseRequestBudgetTest {

    /** "browse 5 pages" (spec). */
    private val pages = 5

    @Test
    fun browseFivePagesCostsAtMostSevenRequestsPerSource() {
        val reason = Bench.openBrowseTabWithStatusFilter()
        assumeTrue("MB-5 skipped: browse fixture unavailable ($reason)", reason == null)
        // Let the first page settle so its requests are not counted.
        Thread.sleep(4_000)

        Bench.clearLogcat()
        Bench.scrollGrid(pages)
        Thread.sleep(3_000)

        val perHost = Bench.loggedRequestsPerHost(Bench.logcat())
        Log.i(Bench.TAG, "MB-5 exact: requests per host = $perHost")

        val worst = perHost.maxByOrNull { it.value }
        if (worst == null) {
            assumeTrue(
                "MB-5 skipped: the app logged no HTTP request — this variant needs the " +
                    "app's DEBUG build (the OkHttp request log is debug-only)",
                false
            )
            return
        }
        assumeTrue(
            "MB-5 skipped: no request was attributed to a host ($perHost)",
            worst.value > 0
        )

        assertTrue(
            "MB-5: ${worst.key} needed ${worst.value} HTTP requests for $pages pages " +
                "(budget ${pages + 2}); per host: $perHost",
            worst.value <= pages + 2
        )
    }
}
