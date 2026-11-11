# `:benchmark` — device measure gate (Stage-2 U-26 / U-27)

This module closes the **device** half of the Stage-2 perf gate. The JVM suites
(`SmartCropScannerTest`, `ReaderImageConfigTest`, `LocalPushFingerprintTest`,
`MangaPagingSourceDedupTest`) prove the *logic*; this module proves the
*numbers on a real device*. The spec it implements is
`tmp/review/stage2/perf.md` §"Measure spec" (MB-1 … MB-5).

It is a standard AndroidX macrobenchmark module (`com.android.test`), not a
baseline-profile module: **no `:baselineprofile` module is created**, so the
suite runs with `CompilationMode.None()` (the app is measured exactly as it is
installed, with no ART pre-compilation). `androidx.profileinstaller` is
already an app dependency, so shipping a profile later is a pure addition.

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

## The suite

| Spec | Test | Asserts |
|---|---|---|
| MB-1 (U-26) | `mb1_prefetchDisplayReusesDecodedPage` | the reader advanced 20 pages; the display phase moved ~0 network bytes; CPU per displayed page stays under budget |
| MB-2 (U-27a) | `mb2_longStripScrollNoOom` | 200 strips: the app process survives, no `OutOfMemoryError` in the log, PSS growth bounded |
| MB-3 (U-27b) | `mb3_smartCropScrollJankVersusNone` | jank(SMART_CROP) ≤ jank(NONE) + margin, per phase from `dumpsys gfxinfo` |
| MB-4 (U-27c) | `mb4_secondIdenticalPushSkipsRemoteReads` | the second of two consecutive pushes moves materially less remote traffic than the first |
| MB-5 (U-27d) | `mb5_browsePagesNetworkBudget` | the browse scenario runs; the per-source request budget is asserted when the request log is available |
| MB-5 (exact) | `BrowseRequestBudgetTest` (debug variant) | browsing 5 pages costs ≤ `pages + 2` HTTP requests **per source** |
| — | `harnessColdStartSmoke` | the app installs, launches and is measurable (`StartupTimingMetric`) |

A scenario that cannot reach its fixture (site unreachable, empty list,
Cloudflare wall, anonymous session) reports a JUnit **skip**, never a green
number. A benchmark that measured nothing must not look like a pass.

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
adb shell am instrument -w \
  -e androidx.benchmark.output.enable=true \
  -e class com.exapps.mangaworld.benchmark.ReaderPerfBenchmarks \
  "$HARNESS/androidx.test.runner.AndroidJUnitRunner"

# 5. the metrics
adb shell cat "/sdcard/Android/data/$HARNESS/files/$HARNESS-benchmarkData.json"
```

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
          | grep -E '^com\.exapps\.mangaworld\.benchmark' | head -n 1)
adb shell am instrument -w \
  -e class com.exapps.mangaworld.benchmark.BrowseRequestBudgetTest \
  "$HARNESS/androidx.test.runner.AndroidJUnitRunner"
```

---

## CI

The `benchmark` job in `.github/workflows/build.yml`:

* runs on **pull requests** and on **pushes to main** (never on tags);
* is **not** in `build-release`'s `needs:` and **must not** gate
  `create-release` — device infrastructure must never block a release (the
  same policy as the existing `firebase-test-lab` job);
* is `continue-on-error: true` at the job level, and the device steps are
  `continue-on-error` individually, so a missing emulator, an exhausted quota
  or a flaky site can never turn a PR red;
* ends with a `::error::` / `::notice::` annotation carrying the outcome, so
  a real regression is visible on the PR even though the job does not block.

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

Reading the numbers: the metric JSON is uploaded as the
`benchmark-metrics` artifact; the human-readable summary is in the job log
under the `MangaWorldBench` tag.
