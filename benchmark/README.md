# `:benchmark` — device measure gate (Stage-2 U-26 / U-27)

This module is the **device** half of the Stage-2 perf gate. The JVM suites
(`SmartCropScannerTest`, `ReaderImageConfigTest`, `LocalPushFingerprintTest`,
`MangaPagingSourceDedupTest`) prove the *logic*; this module is meant to
prove the *numbers on a real device*. The spec it implements is
`tmp/review/stage2/perf.md` §"Measure spec" (MB-1 … MB-5).

---

## STATUS — read this before trusting anything below

> **No baseline has been recorded yet. Not a single MB number exists.**
>
> The module now compiles and the CI job now fails loudly when it does not,
> but **the first real measurement is still pending**: it needs a CI run with
> a device (the emulator image used by the job, or Test Lab). Until that
> run happens and its numbers are copied into
> `tmp/review/stage2/perf.md`, every number in this document is a **budget
> written by hand, not an observation**.
>
> Concretely, this means:
>
> * The budgets below (bytes, ms/page, PSS growth, requests per source) are
>   *expectations*, not measurements. They have never been validated against
>   a device, and some of them may be wrong.
> * "the perf work (U-26/U-27) improved X" is **not** established by this
>   module. The JVM suites prove the *code paths*; the numbers are unknown.
> * A CI run that reports "0 passed, 6 skipped" means the gate measured
>   **nothing** — the job now reports that as an error, not as a pass.

The history of the "green" claim, for the record: the module was added in
v9.7.x and **never compiled** (`Bench.shell()` called
`UiAutomation.executeShellCommand(...).bufferedReader()`, but that API
returns a `ParcelFileDescriptor`, not a stream). The CI job swallowed the
compile failure behind `BENCH_SKIP=1` and went green. The compile error is
fixed, the build step is fatal now, and "all tests skipped" is reported as
an error — but the numbers are still missing.

---

## Layout

| Path | What it is |
|---|---|
| `build.gradle.kts` | `com.android.test` module, `targetProjectPath = ":app"`, self-instrumenting |
| `src/main/AndroidManifest.xml` | package visibility for the app under test |
| `src/main/kotlin/…/Bench.kt` | black-box device probes (shell, /proc, dumpsys, UiAutomator) |
| `src/benchmark/kotlin/…/ReaderPerfBenchmarks.kt` | the macrobenchmark suite (MB-1 … MB-5 + a harness smoke test) |
| `src/debug/kotlin/…/BrowseRequestBudgetTest.kt` | the **exact** MB-5 request counter (needs the app's DEBUG build) |

### Why two build types

* `benchmark` — pairs with the app's new `benchmark` build type
  (release-like, **non-debuggable**, debug-signed, profileable).
  `MacrobenchmarkRule` refuses a debuggable app under test and needs the app
  to be profileable on API 29-30.
* `debug` — pairs with the app's `debug` variant. The app only enables
  OkHttp's request log in debug builds (`core/di/Modules.kt`), so the exact
  "requests per source" counter of MB-5 lives here.

The harness APK is **self-instrumenting** (`experimentalProperties["android.experimental.self-instrumenting"]`).
Resolve the harness id from the device instead of hardcoding it (AGP derives
the test APK id from the module namespace; the debug pairing may differ):
  HARNESS=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package://p' \
            | grep -E '^com\.exapps\.mangaworld\.benchmark' | head -n 1)

A macrobenchmark test APK that instruments the app under test is rejected by
`MacrobenchmarkRule` ("NOT-SELF-INSTRUMENTING"): the benchmark code would be
loaded into the app's process and could not kill / compile / launch it.

---

## The suite — what each case measures

Every case is black-box: the harness APK is a separate, self-instrumenting
APK, so it cannot call into the app. Measurements therefore come from the
platform (`/proc`, `dumpsys netstats`, `dumpsys meminfo`, `dumpsys gfxinfo`,
the OkHttp request log of a debug build).

| Spec | Test | What it measures | Assertion (a hand-written budget, not yet an observation) |
|---|---|---|---|
| — | `harnessColdStartSmoke` | `StartupTimingMetric` — cold start of the app under test | none; it exists so a broken harness/app is distinguishable from a broken measurement |
| MB-1 (U-26) | `mb1_prefetchDisplayReusesDecodedPage` | the reader advancing 20 pages; the CPU burnt by the app's main process during that phase (`/proc/<pid>/stat`), and the bytes the app's uid moved (`dumpsys netstats`) | the reader advanced ≥ 20 pages; the display phase moved ≤ 64 KiB; ≤ 400 ms CPU per displayed page |
| MB-2 (U-27a) | `mb2_longStripScrollNoOom` | 200 vertical strips; the app's PSS before/after (`dumpsys meminfo`), the frame stats (`dumpsys gfxinfo`), and the app log for an OOM | the app process survived; no `OutOfMemoryError` / `Failed to allocate` in the log; PSS growth ≤ 1 GiB |
| MB-3 (U-27b) | `mb3_smartCropScrollJankVersusNone` | jank ratio per phase from `dumpsys gfxinfo` — first with the "بدون" (NONE) filter, then with "قص ذكي للحواف" (SMART_CROP), 10 pages each | jank(SMART_CROP) ≤ jank(NONE) + 0.15 |
| MB-4 (U-27c) | `mb4_secondIdenticalPushSkipsRemoteReads` | the app's remote traffic in a 5 s window after the first push vs. after the second push of the same chapter | the second push moved strictly fewer bytes than the first |
| MB-5 (U-27d) | `mb5_browsePagesNetworkBudget` | the traffic of scrolling 5 pages of the *filtered* Browse tab, plus the per-host request counts (the latter only in a debug build) | the browse phase moved traffic; the per-source listing requests stay ≤ pages + 2 |
| MB-5 (exact) | `BrowseRequestBudgetTest` (debug variant) | the exact count of listing requests to `hijala.com` while scrolling 5 pages | ≤ 7 listing requests for 5 pages |

**A probe that cannot be read is UNKNOWN, not ZERO.** Every case that needs
a platform counter (`/proc/<pid>/stat`, `dumpsys netstats`,
`dumpsys meminfo`) skips with a reason when the counter is missing — a
missing counter must never be folded into "0 bytes / 0 ms / 0 KB", because
that would make the budget pass without measuring anything.

**A scenario that cannot reach its fixture reports a JUnit *skip*, never a
green number.** A benchmark that measured nothing must not look like a pass.

### Why the budgets are what they are

* **MB-1 bytes (64 KiB)** — the pages were prefetched, so displaying them
  must not download anything. 64 KiB is a small allowance for telemetry.
* **MB-1 CPU (400 ms/page)** — a memory-cache hit renders without decoding;
  re-decoding a 4096 px page costs far more than that.
* **MB-5 requests (`pages + 2`)** — U-27(d) memoises the overlapping
  filtered-browse windows, so scrolling N pages fetches the sub-pages
  `1..N+2` once each (≈ N requests). Before the memo it was three requests
  per page (≈ 3N), so the budget discriminates the two.

---

## Reading the numbers

### From a CI run

1. **The job log.** The harness prints one human-readable line per case
   under the `MangaWorldBench` tag; the CI job echoes them (see
   `benchmark-metrics/bench-probes.log` in the artifact):

   ```
   MB-1 display: pages=20 cpu=812ms (40ms/page) bytes=0
   MB-2 scroll: strips=200 pss=182344KB->183120KB frames=4820 janky=61
   MB-3 jank: NONE=12/900 (0.01) SMART_CROP=30/910 (0.03)
   MB-4 push traffic: first=8123B second=0B
   MB-5 browse: pages=5 bytes=48213->55310 requests={hijala.com=5}
   ```

2. **The `benchmark-metrics` artifact** (30 days). It contains:
   * `bench-probes.log` — the lines above;
   * `*benchmarkData.json` — the androidx.benchmark metrics
     (`frameDurationCpuTime`, `timeToInitialDisplay`, …);
   * `instrumentation.log` / `instrumentation-debug.log` — the raw
     instrumentation protocol, including every skip reason;
   * `benchmark-summary.md` — the pass/fail/skip tally of the run.

3. **The job summary.** The `Report the outcome` step writes a table
   (passed / failed / skipped) into the GitHub job summary.

### What the outcome annotations mean

| Annotation | Meaning |
|---|---|
| `::error::Perf gate broken` | the harness did not compile — the gate is broken, no numbers exist |
| `::warning::Perf gate skipped` | no device was available (no emulator / no quota / APKs missing) — no numbers exist |
| `::error::Perf gate failed` | at least one case failed |
| `::error::Perf gate measured nothing` | every case was **skipped** — the gate ran and measured nothing |
| `::notice::Perf gate produced numbers` | at least one case passed; the numbers are in the artifact |

The "measured nothing" case matters: `am instrument` exits **0** when every
test was skipped, so a suite that measured nothing is indistinguishable
from a suite that passed unless the per-test status codes are counted. The
job counts them (`INSTRUMENTATION_STATUS_CODE: 0` = passed, `-2` = failed,
`-3`/`-4` = skipped) and treats "zero passed" as an error.

---

## Fidelity — exact vs proxy

The harness is a separate APK, so it cannot call into the app's Kotlin code.
Where the spec asks for an app-side counter, the suite uses the closest
black-box signal and says so:

| Spec wants | This module measures | What would make it exact |
|---|---|---|
| MB-1: Coil `dataSource == MEMORY_CACHE` hit ratio | network bytes moved while the prefetched pages are displayed + CPU per displayed page (a memory hit renders without decoding) | an `EventListener` on the app's `ImageLoader` that records `dataSource` (one line in `MangaWorldApp.buildSharedImageLoader`) |
| MB-2: "no OOM, p95 frame time" | the app process survives (the harness fails the test if it dies), no OOM in the log, bounded PSS growth, `FrameTimingMetric` | — (already exact for the OOM part) |
| MB-3: SMART_CROP vs NONE jank | per-phase jank ratio from `dumpsys gfxinfo` | — (already exact) |
| MB-4: a counter on `fetchAllCollection` | the app's remote traffic around each push | a counter inside `FirebaseSyncManager.fetchAllCollection` (private) plus a named Firebase session on the device |
| MB-5: requests per source | the request log (debug variant) / traffic bytes (macrobenchmark variant) | — (already exact in the debug variant) |

### Two findings the suite surfaced

1. **MB-4's exact scenario is not reachable through the UI.** Every push
   trigger mutates state that the U-27(c) fingerprint covers: opening a
   chapter always rewrites `ReadingHistoryEntity.lastReadAt`
   (`Repositories.updateReadingHistory`), and finishing one bumps the reading
   stats. Two pushes therefore never carry identical local state, so the
   fingerprint skip cannot be provoked from the UI — and the device is
   additionally anonymous by default, where the sync path is inert
   (`FirebaseSyncManager.namedUid()` returns null). The suite asserts what
   *is* observable (the second of two consecutive pushes is materially
   cheaper) and skips when the device has no named session.
2. **The U-27(d) window only applies to a *filtered* browse.** The three
   subpage fetches per page load happen when `MangaPagingSource` has a local
   filter (`needsLocalFiltering`: a status or type filter, no query). The
   per-source browse screen (`source_browse/…`) uses a different, unpaged
   path. MB-5 therefore drives the **bottom-navigation Browse tab** with the
   "مستمر" (ongoing) status filter applied.

---

## The CI contract

The `benchmark` job in `.github/workflows/build.yml`:

* runs on **pull requests** and on **pushes to main** (never on tags);
* is **not** in `build-release`'s `needs:` and **must not** gate
  `create-release` — device infrastructure must never block a release (the
  same policy as the existing `firebase-test-lab` job);
* is `continue-on-error: true` at the **job** level, so it never blocks the
  pipeline;
* but the **build step is fatal**: a harness that does not compile fails the
  job (with an `::error::` annotation) instead of being swallowed by a skip
  flag;
* keeps the **device phase** skippable (no emulator, no quota, missing APKs):
  every skip announces itself with `::warning::` and records
  `BENCH_SKIP=1` in `$GITHUB_ENV`;
* ends with an outcome report that distinguishes *build failed* /
  *skipped* / *a case failed* / *everything was skipped* / *numbers were
  produced*, and writes the tally into the job summary.

### Runtime and quota

| Item | Cost |
|---|---|
| Gradle build (`:app:assembleBenchmark`, `:benchmark:assembleBenchmark`) | ~4 min (R8 is off for the `benchmark` type on purpose) |
| Gradle build (`:app:assembleDebug`, `:benchmark:assembleDebug`) | ~4 min — only for the exact MB-5 counter |
| Emulator boot | ~2-4 min (a GitHub-hosted emulator, **no Test Lab quota**) |
| Macrobenchmark suite | ~5-10 min (6 tests; MB-2 alone scrolls 200 strips) |
| Debug-variant counter | ~2 min |
| **Total** | **~20 min per run, no Firebase Test Lab minutes** |

The suite deliberately uses a **GitHub-hosted emulator** instead of Firebase
Test Lab: Test Lab minutes are a shared daily budget (one on-demand physical
run exhausted a whole day in this repo), macrobenchmark wants a device the
job controls, and the emulator path costs no quota at all. If a device is not
available the job skips loudly instead of failing.

---

## Running it locally

> AGENTS.md: **never build the app locally** — use CI. The commands below are
> for a developer machine that already has the SDK; the CI job is the
> supported path.

### With a local emulator (what CI does)

```bash
# 1. an emulator with the app under test
sdkmanager "system-images;android-30;google_apis;x86_64"
avdmanager create avd -n manga-bench -k "system-images;android-30;google_apis;x86_64"
emulator -avd manga-bench -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device

# 2. the APKs
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-universal-benchmark.apk
adb install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk

# 3. the runtime permissions the app asks for on first launch (otherwise a
#    system dialog sits in front of the app and every UI step times out)
adb shell pm grant com.exapps.mangaworld android.permission.READ_EXTERNAL_STORAGE

# 4. run
HARNESS=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package://p' \
          | grep -E '^com\.exapps\.mangaworld\.benchmark' | head -n 1)
adb shell am instrument -w -r \
  -e androidx.benchmark.output.enable=true \
  -e class com.exapps.mangaworld.benchmark.ReaderPerfBenchmarks \
  "$HARNESS/androidx.test.runner.AndroidJUnitRunner"

# 5. the metrics
adb shell cat "/sdcard/Android/data/$HARNESS/files/$HARNESS-benchmarkData.json"
adb logcat -d -s MangaWorldBench:I '*:S'
```

`-r` (raw) matters when reading the result by hand: it prints the per-test
`INSTRUMENTATION_STATUS_CODE` lines, which is the only way to see that a case
was **skipped** rather than passed.

### With Gradle (connected device / emulator)

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest        # macrobenchmark suite
./gradlew :benchmark:connectedDebugAndroidTest            # the exact MB-5 counter
```

### The exact MB-5 counter needs the DEBUG build

```bash
./gradlew :app:assembleDebug :benchmark:assembleDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r benchmark/build/outputs/apk/debug/benchmark-debug.apk
HARNESS=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package://p' \
          | grep -E '^com\.exapps\.mangaworld\.benchmark' | head - n 1)
adb shell am instrument -w -r \
  -e class com.exapps.mangaworld.benchmark.BrowseRequestBudgetTest \
  "$HARNESS/androidx.test.runner.AndroidJUnitRunner"
```

---

## Known limitations

* **No baseline.** See STATUS at the top. The budgets are unvalidated.
* **The measurements depend on live third-party sites.** `hijala.com` is
  reachable from CI or it is not; when it is not, every case that needs the
  reader/browse fixture skips, and the job reports "measured nothing".
  That is honest, but it means a green run needs a reachable site.
* **The privileged probes need root.** `UiAutomation.executeShellCommand`
  runs a command with the *harness's* uid, so `dumpsys …` (DUMP) and
  `logcat -d` (READ_LOGS) are refused by default. The harness probes for
  `su` once per process and escalates with `su root <cmd>` when the platform
  allows it (the same trick `androidx.benchmark.Shell` uses). On a
  userdebug/eng device — the CI emulator image — that works, so the
  `dumpsys`/`/proc` probes are readable. On a user build they are not, and
  the affected cases skip with "the platform did not expose …" instead of
  reporting a fake zero.
* **`CompilationMode.None()`** performs *no* pre-compilation: the app's saved
  ART profile is reset and the app runs JIT-only, so every run starts from
  the same cold-compilation state. There is no `:baselineprofile` module in
  this repo, so `Partial(BaselineProfileMode.Require)` cannot be used — it
  would fail the run instead of measuring anything.
  `androidx.profileinstaller` IS already a dependency of the app, so
  shipping a profile later is a pure addition.
* **The reader fixture is driven through the UI** (browse grid → manga →
  chapter), so it depends on the app's current navigation layout and on the
  Arabic labels listed in `Bench.kt` (`تصفح`, `تصفية`, `مستمر`,
  `تطبيق الفلتر`, `إعدادات`, `بدون`, `قص ذكي للحواف`, `تخطي`,
  `التخطي والدخول كضيف`). A label change silently turns a case into a
  skip — the job's "measured nothing" error is the backstop.
* **A Compose UI has no `ImageView`.** The covers are detected through the
  accessibility tree (a semantics node whose content description is the
  manga title), not through `android.widget.ImageView`; probing the class
  would find nothing and every case would skip.
