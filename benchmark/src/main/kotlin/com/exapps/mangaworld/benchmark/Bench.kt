package com.exapps.mangaworld.benchmark

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern

/**
 * Black-box device probes shared by the benchmark suites.
 *
 * The benchmark APK is a SEPARATE, self-instrumenting APK (that is what
 * `MacrobenchmarkRule` requires), so none of this code can call into the
 * app's Kotlin objects: every probe here works through the shell
 * (`UiAutomation.executeShellCommand`) or through UiAutomator.
 *
 * That is a deliberate constraint, not an oversight: a macrobenchmark must
 * measure the *installed* app, and the only app-side hooks available without
 * touching `app/src/main` are the ones the platform exposes to the shell.
 * Where a measurement therefore needs an app-side counter (Coil's memory
 * cache hit ratio, `FirebaseSyncManager.fetchAllCollection` call counts),
 * the test falls back to the closest black-box proxy and says so in its
 * KDoc — see benchmark/README.md §"Fidelity".
 */
object Bench {

    const val TAG = "MangaWorldBench"

    /** The app under test (applicationId, see app/build.gradle.kts). */
    const val APP_ID = "com.exapps.mangaworld"
    const val LAUNCH_ACTIVITY = "com.exapps.mangaworld.MainActivity"

    /**
     * Source used by the browse/reader scenarios.
     *
     * `hijala` is one of the two signed pilot plugins that ship inside the
     * APK, so it is registered on a fresh install without any network round
     * trip (see AGENTS.md §Source Plugins). The list still has to be fetched
     * from the site, so every scenario degrades to a JUnit *skip* when the
     * site is unreachable.
     */
    const val DEFAULT_SOURCE_ID = "hijala"

    /** Host of [DEFAULT_SOURCE_ID] (BuiltinSourcePlugins: hijala -> https://hijala.com). */
    const val DEFAULT_SOURCE_HOST = "hijala.com"

    // Arabic labels the app actually renders (app/src/main/res/values/strings.xml).
    const val LABEL_PAGE = "صفحة"                    // accessibility_page -> "صفحة %d"
    const val LABEL_CHAPTER = "الفصل"                // fmt_059           -> "الفصل %s"
    const val LABEL_SETTINGS = "إعدادات"              // settings
    const val LABEL_FILTER_NONE = "بدون"             // reader_filter_none
    const val LABEL_FILTER_SMART_CROP = "قص ذكي للحواف" // reader_filter_smart_crop
    const val LABEL_ONBOARDING_SKIP = "تخطي"          // onboarding_skip
    const val LABEL_SKIP_GUEST_LOGIN = "التخطي والدخول كضيف" // skip_guest_login
    const val LABEL_BROWSE_TAB = "تصفح"              // browse (bottom navigation)
    const val LABEL_FILTER = "تصفية"                 // filter
    const val LABEL_STATUS_ONGOING = "مستمر"          // ongoing
    const val LABEL_APPLY_FILTER = "تطبيق الفلتر"     // browse_filter_apply

    /** Tag used by OkHttp's HttpLoggingInterceptor (BASIC level). */
    const val OKHTTP_LOG_TAG = "okhttp.OkHttpClient"

    private val PAGE_NUMBER = Regex("(\\d+)")
    private val RX_BYTES = Regex("rxBytes=(\\d+)")
    private val TX_BYTES = Regex("txBytes=(\\d+)")
    private val UID_HEADER = Regex("uid=(\\d+)")

    /**
     * Matches ANY non-empty content description.
     *
     * The app is a Compose app: a manga cover is a *semantics* node whose
     * content description is the title (`MangaCard` merges its descendants
     * and sets `contentDescription = manga.title`), not an
     * `android.widget.ImageView`. See [waitForLargestCover].
     */
    private val ANY_DESCRIPTION: Pattern = Pattern.compile(".+")

    /** Minimum edge (px) a cover-like node must have to be considered a cover. */
    private const val MIN_COVER_EDGE_PX = 100

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    fun device(): UiDevice = UiDevice.getInstance(instrumentation)

    // ── shell ────────────────────────────────────────────────────────────────

    /**
     * The instrumentation's [UiAutomation].
     *
     * `Instrumentation.getUiAutomation()` returns **null** on API 31+ when the
     * connection to the accessibility service times out — the platform
     * documentation says the caller should check the value and retry. The
     * harness APK targets a modern SDK, so the first connection attempt can
     * legitimately fail.
     *
     * The retry is BOUNDED on purpose: the platform waits up to 60 s
     * (`Instrumentation.CONNECT_TIMEOUT_MILLIS`) before it gives up, and
     * every failed attempt starts the wait again. Without the "remember the
     * failure" latch below, one unavailable UiAutomation would stall the
     * whole suite for minutes on every probe.
     */
    private var uiAutomationUnavailable = false

    private fun uiAutomation(): UiAutomation {
        if (uiAutomationUnavailable) {
            throw IllegalStateException("UiAutomation is not available on this device")
        }
        repeat(2) { attempt ->
            val automation = runCatching { instrumentation.uiAutomation }.getOrNull()
            if (automation != null) return automation
            Log.w(TAG, "UiAutomation is not connected yet (attempt ${attempt + 1}/2)")
        }
        uiAutomationUnavailable = true
        throw IllegalStateException("UiAutomation could not be connected on this device")
    }

    /**
     * Runs [command] through the device shell and returns its stdout.
     *
     * `UiAutomation.executeShellCommand` returns the **read end of a pipe**
     * (a `ParcelFileDescriptor`), not a stream: it has to be wrapped in an
     * `AutoCloseInputStream` before it can be read. (This is exactly the
     * mistake that kept the module from ever compiling.)
     *
     * The command is exec'd directly — `Runtime.exec(String)` tokenises it on
     * whitespace and does NOT run a shell — so there is no piping, no
     * globbing and no quoting: callers pass plain argv-style strings and
     * parse the output in Kotlin.
     *
     * The command runs with the HARNESS's uid, not the shell's, so the
     * privileged probes (`dumpsys …`, `logcat -d`) are refused unless the
     * platform lets this app escalate — see [rootPrefix].
     */
    fun shell(command: String): String = exec(rootPrefix + command)

    /**
     * The prefix that gives a command root privileges, or "" when root is
     * not available.
     *
     * `UiAutomation.executeShellCommand` executes the command with the
     * harness's own uid: `dumpsys` (DUMP) and `logcat -d` (READ_LOGS) are
     * then refused and every privileged probe would read empty — the gate
     * would skip every case and report "measured nothing".
     *
     * On a userdebug/eng device (the CI emulator image) `su` is available to
     * a debuggable app, and `androidx.benchmark.Shell` uses exactly the same
     * trick (`su root <cmd>`) with the same runtime probe. The probe is
     * cached, so it costs two commands per process at most.
     */
    private val rootPrefix: String by lazy {
        val id = exec("id")
        when {
            id.contains("uid=0(root)") -> ""
            exec("su root id").contains("uid=0(root)") -> "su root "
            else -> {
                Log.w(TAG, "no root escalation available — privileged probes (dumpsys, logcat) will be refused")
                ""
            }
        }
    }

    /** Runs [command] with the harness's own uid. */
    private fun exec(command: String): String = try {
        val descriptor: ParcelFileDescriptor =
            uiAutomation().executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    } catch (t: Throwable) {
        Log.w(TAG, "shell($command) failed: ${t.message}")
        ""
    }

    // ── process probes ──────────────────────────────────────────────────────

    /**
     * PID of the app's MAIN process. The app also runs helper processes
     * (:push, :firebase), so the one whose /proc cmdline is exactly the
     * application id is picked.
     */
    fun appPid(): Int? {
        val pids = shell("pidof $APP_ID").trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
        if (pids.isEmpty()) return null
        return pids.firstOrNull { pid ->
            shell("cat /proc/$pid/cmdline").trim { it == '\u0000' || it == ' ' } == APP_ID
        } ?: pids.first()
    }

    /**
     * CPU time (utime + stime) burnt by the app's main process, in ms.
     *
     * /proc/<pid>/stat counts in clock ticks; Android uses 100 Hz. This is
     * the only black-box signal for "did the app decode the bitmap again?"
     * — a decode is CPU work, a cache hit is not.
     */
    fun appCpuTimeMs(): Long? {
        val pid = appPid() ?: return null
        val stat = shell("cat /proc/$pid/stat")
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        // Everything after ')' starts at field 3 (state), so utime (field 14)
        // is index 11 and stime (field 15) is index 12.
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return (utime + stime) * 10L
    }

    /** PSS of the app's main process in KB (`dumpsys meminfo`). */
    fun appPssKb(): Long? {
        val out = shell("dumpsys meminfo $APP_ID")
        Regex("TOTAL PSS:\\s+(\\d+)").find(out)?.let { m ->
            return m.groupValues[1].toLongOrNull()
        }
        Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(out)?.let { m ->
            return m.groupValues[1].toLongOrNull()
        }
        return null
    }

    /** UID of the app; netstats buckets are keyed by uid. */
    fun appUid(): Int? =
        Regex("userId=(\\d+)")
            .find(shell("dumpsys package $APP_ID"))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /**
     * Bytes the app's uid exchanged with the network, summed over the
     * netstats history buckets.
     *
     * The absolute value covers the whole bucket window, so this is only
     * ever used as a DELTA around a phase.
     *
     * Returns **null** when the platform did not expose a per-uid byte
     * counter. That distinction matters: "the dump contained the uid but no
     * byte counter" means UNKNOWN, not ZERO, and reporting zero would make
     * every "no bytes were moved" assertion pass vacuously.
     */
    fun appNetworkBytes(): Long? {
        val uid = appUid() ?: return null
        var sum = 0L
        var parsed = false
        // The netstats dump groups the counters per uid; the uid header and
        // the byte counters are usually on DIFFERENT lines, so the current
        // uid is tracked while walking the dump.
        var currentUid: Int? = null
        for (line in netstatsDump().lineSequence()) {
            UID_HEADER.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { currentUid = it }
            if (currentUid != uid) continue
            val rx = RX_BYTES.find(line)?.groupValues?.get(1)?.toLongOrNull()
            val tx = TX_BYTES.find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (rx == null && tx == null) continue
            parsed = true
            sum += (rx ?: 0L) + (tx ?: 0L)
        }
        return if (parsed) sum else null
    }

    /**
     * The netstats dump.
     *
     * `dumpsys netstats` prints the per-uid counters; some builds only
     * include the byte counters in the verbose form, so that is tried too.
     */
    private fun netstatsDump(): String {
        val summary = shell("dumpsys netstats")
        if (RX_BYTES.containsMatchIn(summary) || TX_BYTES.containsMatchIn(summary)) {
            return summary
        }
        val detail = shell("dumpsys netstats detail")
        return if (RX_BYTES.containsMatchIn(detail) || TX_BYTES.containsMatchIn(detail)) {
            detail
        } else {
            summary
        }
    }

    /**
     * Network bytes moved by the app during a quiet window of [windowMs].
     * Returns -1 when the platform does not expose per-uid counters.
     */
    fun networkBytesOverWindow(windowMs: Long): Long {
        val before = appNetworkBytes() ?: return -1L
        Thread.sleep(windowMs)
        val after = appNetworkBytes() ?: return -1L
        return (after - before).coerceAtLeast(0L)
    }

    /** CPU milliseconds burnt by the app during [block]. */
    fun cpuDuring(block: () -> Unit): Long {
        val before = appCpuTimeMs() ?: 0L
        block()
        val after = appCpuTimeMs() ?: before
        return (after - before).coerceAtLeast(0L)
    }

    // ── frame stats ─────────────────────────────────────────────────────────

    /**
     * Jank statistics of the app's UI thread, read from `dumpsys gfxinfo`.
     *
     * Returns (janky frames, total frames) or null when the platform does
     * not report them. The counters are reset before the measured phase.
     */
    fun gfxInfo(): Pair<Int, Int>? {
        val out = shell("dumpsys gfxinfo $APP_ID")
        val total = Regex("Total frames rendered:\\s*(\\d+)").find(out)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val janky = Regex("Janky frames:\\s*(\\d+)").find(out)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return janky to total
    }

    fun resetGfxInfo() {
        shell("dumpsys gfxinfo $APP_ID reset")
    }

    // ── logcat ──────────────────────────────────────────────────────────────

    fun clearLogcat() {
        shell("logcat -c")
    }

    fun logcat(): String = shell("logcat -d -v brief")

    /**
     * Counts the HTTP requests the app's OkHttp client logged for [host].
     *
     * The app only enables that logger in DEBUG builds
     * (`core/di/Modules.kt`: HttpLoggingInterceptor at BASIC when
     * BuildConfig.DEBUG), so this is meaningful for the debug-paired
     * instrumentation variant only. The macrobenchmark build is
     * non-debuggable (MacrobenchmarkRule rejects debuggable apps), so the
     * counter is 0 there — that is why the exact counter lives in
     * src/debug/ and the macrobenchmark uses byte budgets instead.
     */
    fun countLoggedRequests(log: String, host: String): Int =
        log.lineSequence().count {
            it.contains(OKHTTP_LOG_TAG) && it.contains("-->") && it.contains(host)
        }

    /**
     * Parses the app's OkHttp request log into per-host request counts.
     * Only meaningful for DEBUG builds (the logger is disabled otherwise).
     */
    fun loggedRequestsPerHost(log: String): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (line in log.lineSequence()) {
            if (!line.contains(OKHTTP_LOG_TAG) || !line.contains("-->")) continue
            val url = line.substringAfter("-->").trim()
            val host = url.substringAfter("//", "").substringBefore('/').substringBefore(':')
            if (host.isBlank()) continue
            counts[host] = (counts[host] ?: 0) + 1
        }
        return counts
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    fun launchApp(): Boolean {
        shell("am start -W -n $APP_ID/$LAUNCH_ACTIVITY")
        return waitFor(By.pkg(APP_ID).depth(0), 20_000) != null
    }

    /**
     * Gets the app past its first-launch gates.
     *
     * A fresh install shows the onboarding wizard, and once onboarding is
     * complete the guest-login overlay is shown on EVERY launch (it is
     * `rememberSaveable`, not persisted). Both sit in front of the nav
     * host, so a deep link cannot reach the browse screen until they are
     * dismissed — every scenario therefore starts here.
     */
    fun ensureAppReady(timeoutMs: Long = 20_000) {
        launchApp()
        val skipOnboarding = waitFor(By.text(LABEL_ONBOARDING_SKIP), timeoutMs)
        if (skipOnboarding != null) {
            runCatching { skipOnboarding.click() }
            Thread.sleep(2_000)
        }
        val skipLogin = waitFor(By.text(LABEL_SKIP_GUEST_LOGIN), 12_000)
        if (skipLogin != null) {
            runCatching { skipLogin.click() }
            Thread.sleep(2_000)
        }
    }

    /**
     * Opens the bottom-navigation Browse tab and applies a status filter.
     *
     * That is the path that uses `MangaPagingSource` with a LOCAL filter
     * (`needsLocalFiltering`) — the three-subpage window that U-27(d)
     * memoises. The per-source browse screen (`source_browse`) uses a
     * different, unpaged path and would never hit the memo.
     */
    fun openBrowseTabWithStatusFilter(): String? {
        ensureAppReady()
        val tab = waitFor(By.text(LABEL_BROWSE_TAB), 20_000)
            ?: return "the bottom navigation has no Browse tab"
        runCatching { tab.click() }
        Thread.sleep(2_500)
        val filterButton = waitFor(By.text(LABEL_FILTER), 12_000)
            ?: return "the Browse screen has no filter button"
        runCatching { filterButton.click() }
        Thread.sleep(1_500)
        val status = waitFor(By.text(LABEL_STATUS_ONGOING), 12_000)
            ?: return "the filter sheet has no status chip"
        runCatching { status.click() }
        Thread.sleep(700)
        val apply = waitFor(By.text(LABEL_APPLY_FILTER), 12_000)
            ?: return "the filter sheet has no apply button"
        runCatching { apply.click() }
        Thread.sleep(3_000)
        return if (waitForLargestCover(30_000) == null) {
            "the filtered browse grid never rendered a cover"
        } else {
            null
        }
    }

    /** Waits for the first node matching [selector]; null on timeout. */
    fun waitFor(selector: BySelector, timeoutMs: Long): UiObject2? {
        val device = device()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching { device.findObject(selector) }.getOrNull()
            if (found != null) return found
            Thread.sleep(400)
        }
        return null
    }

    /**
     * Largest cover-like node on screen; null on timeout.
     *
     * The app is a Compose app, so a cover is **not** an
     * `android.widget.ImageView`: it is a semantics node whose content
     * description is the manga title (`MangaCard` merges its descendants
     * and sets `contentDescription = manga.title`, and `MangaCover` sets the
     * same on the image). Searching for ImageViews therefore finds nothing
     * in a Compose UI — every scenario would time out, skip, and the gate
     * would report green while measuring nothing.
     *
     * Both shapes are probed (described semantics nodes first, real
     * ImageViews as a fallback for a plain View based UI) and the largest
     * candidate wins.
     */
    fun waitForLargestCover(timeoutMs: Long): UiObject2? {
        val device = device()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val candidates = runCatching {
                device.findObjects(By.desc(ANY_DESCRIPTION)) +
                    device.findObjects(By.clazz("android.widget.ImageView"))
            }.getOrDefault(emptyList())
            val largest = candidates
                .filter {
                    it.visibleBounds.width() > MIN_COVER_EDGE_PX &&
                        it.visibleBounds.height() > MIN_COVER_EDGE_PX
                }
                .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            if (largest != null) return largest
            Thread.sleep(500)
        }
        return null
    }

    /**
     * Opens the browse screen of [sourceId] and waits for the grid to
     * render. Returns null on success, or the reason it failed.
     */
    fun openBrowse(sourceId: String = DEFAULT_SOURCE_ID): String? {
        ensureAppReady()
        shell("am start -a android.intent.action.VIEW -d mangaworld://screen/source_browse/$sourceId")
        return if (waitForLargestCover(30_000) == null) {
            "source browse screen for $sourceId never rendered a cover"
        } else {
            null
        }
    }

    /**
     * Drives the app to the reader: source browse grid -> first manga ->
     * first chapter.
     *
     * Returns null on success, or a human readable reason why the fixture
     * could not be reached (site unreachable, empty list, Cloudflare wall,
     * ...). Callers turn a non-null reason into a JUnit skip: a benchmark
     * that cannot reach its fixture must not report a green number.
     */
    fun openReader(sourceId: String = DEFAULT_SOURCE_ID): String? {
        val browseFailure = openBrowse(sourceId)
        if (browseFailure != null) return browseFailure
        val cover = waitForLargestCover(10_000)
            ?: return "source browse screen for $sourceId lost its covers"
        val openManga = runCatching { cover.click() }
        if (openManga.isFailure) {
            return "could not open the first manga: ${openManga.exceptionOrNull()?.message}"
        }
        val chapter = waitFor(By.textStartsWith(LABEL_CHAPTER), 30_000)
            ?: return "detail screen never listed a chapter"
        val openChapter = runCatching { chapter.click() }
        if (openChapter.isFailure) {
            return "could not open the chapter: ${openChapter.exceptionOrNull()?.message}"
        }
        if (!waitForReader(40_000)) return "reader never rendered a page"
        return null
    }

    /**
     * Current reader page (1-based), read from the page image's content
     * description ("صفحة N" — `R.string.accessibility_page`). -1 when the
     * reader is not on screen.
     */
    fun currentReaderPage(): Int {
        val nodes = runCatching {
            device().findObjects(By.descStartsWith(LABEL_PAGE))
        }.getOrDefault(emptyList())
        var max = -1
        for (node in nodes) {
            val desc = node.contentDescription ?: continue
            val number = PAGE_NUMBER.find(desc)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (number > max) max = number
        }
        return max
    }

    fun waitForReader(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (currentReaderPage() > 0) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Scrolls the reader forward until [pages] page turns happened (or the
     * swipe budget runs out). Returns the number of page turns observed.
     */
    fun scrollReaderPages(pages: Int, maxSwipes: Int = pages * 8): Int {
        val device = device()
        val start = currentReaderPage()
        var last = start
        var swipes = 0
        while (swipes < maxSwipes) {
            swipeUp()
            swipes++
            val now = currentReaderPage()
            if (now > last) last = now
            if (last - start >= pages) break
        }
        return last - start
    }

    /** One full-height "next page" swipe. */
    fun swipeUp() {
        val device = device()
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.82).toInt(), w / 2, (h * 0.18).toInt(), 12)
    }

    fun swipeDown() {
        val device = device()
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.18).toInt(), w / 2, (h * 0.82).toInt(), 12)
    }

    /** [count] full-height swipes (the "strip" unit of the long-scroll case). */
    fun swipeUp(count: Int) {
        repeat(count) { swipeUp() }
    }

    /**
     * Switches the reader image filter chip. [label] is the Arabic chip
     * label ("بدون" = NONE, "قص ذكي للحواف" = SMART_CROP).
     *
     * The reader chrome is hidden until the page is tapped, and the filter
     * chips live in the reader settings sheet.
     */
    fun selectReaderFilter(label: String): Boolean {
        val device = device()
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        Thread.sleep(1_000)
        val settings = waitFor(By.desc(LABEL_SETTINGS), 10_000) ?: return false
        settings.click()
        Thread.sleep(1_500)
        val chip = waitFor(By.text(label), 10_000) ?: return false
        chip.click()
        Thread.sleep(2_000)
        device.pressBack()
        Thread.sleep(1_000)
        return true
    }

    /** Scrolls the browse grid forward [times] times (one page each). */
    fun scrollGrid(times: Int) {
        val device = device()
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(times) {
            device.swipe(w / 2, (h * 0.80).toInt(), w / 2, (h * 0.25).toInt(), 10)
            Thread.sleep(1_500)
        }
    }
}
