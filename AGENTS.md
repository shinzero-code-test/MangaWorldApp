# AGENTS.md — MangaWorld App

## System Instructions

- every push & version bump, create a release tag.
- For every fix do a patch; for every new feature, do a minor; for important high-impact features, do a major version update.
- no version bumps for dashboard changes only.
- Always use string resources. No hardcoded strings.
- Never build the app locally. Always use CI/CD.
- git, gh, vercel, Firebase, gcloud CLIs are already installed and authenticated — use them directly.
- Always use skills, tools, and MCPs for better results.
- Always update AGENTS.md
- When doing code reviews, parallelize with sub-agents, each saving to its own MD file in `tmp/review/`. Sub-agents must not write over each other's results.

## Project Overview

Arabic manga reader Android app (Kotlin + Jetpack Compose). Single-module `:app` project.
- **Package**: `com.exapps.mangaworld`
- **Current version**: 9.4.1 (versionCode 263)
- **Min SDK**: 26 (Android 8.0) · **Target SDK**: 36 · **Compile SDK**: 36
- **JDK**: 17 (required by CI and build)
- **Typography**: Cairo Bold for display/headline/title; IBM Plex Sans Arabic for body/label/UI/button text. Fonts are bundled in `res/font`; Glance cannot use bundled custom fonts.

Also contains a Next.js admin dashboard in `dashboard/` (deployed to Vercel at mangaworld-admin.vercel.app).

## Build & Test Commands

```bash
# Build
./gradlew assembleDebug          # Debug APK (all ABIs)
./gradlew assembleRelease        # Release APK (unsigned if no keystore)
./gradlew bundleRelease          # Release AAB for Play Store

# Lint
./gradlew :app:lintDebug --stacktrace

# Tests
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ScraperFixtureTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ScraperTextTest*" --stacktrace

# Dashboard typecheck (run from dashboard/)
node node_modules/typescript/bin/tsc --noEmit -p tsconfig.json
```

**CI order**: lint → unit tests → debug build → release build → verify artifacts → release notes

## Source Layout

All Kotlin source is under `app/src/main/kotlin/com/exapps/mangaworld/`:
- `core/data/remote/scraper/` — scraper classes per source + shared `ScraperText`/`ScraperTelemetry` helpers
- `core/data/local/` — Room database (v14), DAOs, entities, AppPreferences (DataStore)
- `core/data/download/` — durable download queue (DownloadQueueManager, ChapterDownloadWorker, AutoDownloadWorker)
- `core/di/Modules.kt` — Hilt modules (NetworkModule, DatabaseModule, ScraperModule, etc.)
- `core/firebase/` — Firebase Auth, Firestore, sync, notifications, telemetry, community repository
- `domain/model/Models.kt` — `MangaSource` enum (18 sources), all data classes
- `domain/repository/Repositories.kt` — repository interfaces
- `presentation/` — Compose UI screens + ViewModels, organized by feature
- `widgets/` — Glance widget composables

Dashboard: `dashboard/src/` (Next.js App Router) with `dashboard/vercel.json`, `dashboard/.env.example`

`tmp/analysis/` — point-in-time, Chromium/DevTools-verified source reviews. Treat HTTP/WAF behavior and API details as dated evidence; every report must distinguish live-browser evidence from historical or extension-derived claims.

## Key Architecture Facts

- **DI**: Hilt multibindings for source plugins (`@Binds @IntoMap @StringKey("sourceId")` in `SourcePluginModule`) — 17 sources, each a `SourcePlugin` (descriptor + display + scraper); `SourceRegistry` merges builtins with future remotes. Rockmanga removed (dead upstream).
- **Base scrapers**: `BaseScraperImpl`, `MadaraBaseScraper` (Madara WordPress), `MangaReaderBaseScraper` (MangaReader theme), plus custom scrapers. Shared parsing: `ScraperText.firstChapterNumber()` / `.slugFromHref()` / `.extractViews()`
- **Source plugins (Phase 0, v8.8.0 train)**: contract package `core/source/plugins/` — pure Kotlin, zero Android deps, JVM-CI-safe. Frozen schema v1 (`PluginManifest`), JCS (RFC 8785) canonicalization minus `signature`, strict duplicate-key rejection, closed engine vocab, literal-host allow-lists, compat-gate-before-schema. Deps: Tink 1.18.0 (raw Ed25519 verify), Jackson tree-model-only (no reflection/R8 rules), erdtman JCS 1.1. Contract tests: 11 suites under `core/source/` tests. `requiresPermission` flag (robots-gated APIs — never auto-enable), `SourceEngine.CUSTOM` is builtin-only (rejected for remote manifests). Full plan: `tmp/source-plugin-plan-v3.md`. Phase 1 (v8.8.0): `SourcePlugin`/`SourceRegistry`/`SourceDisplayResolver`, 17 builtin plugins, rockmanga removed. Phase 2A (v8.9.0): `PluginTrust` (pinned `official-1` key; RC cross-signed rotation; 90-day trust age on downloads only), `PluginStore` + `plugin_index` (staging → verify → immutable `files/plugins/<id>/versions/<n>/`, pointer activation/rollback, quota), verified official overrides in `SourceRegistry` (custom/local never shadow), bundled pilots hijala+lavascans (signed JSON + existing scrapers, behavior-identical), `MangaScraper.pluginDescriptor` hook, `MangaSource.fromId` deprecated (use `fromIdOrNull`). Private plugin key in dashboard secrets only — never in repo (live: Vercel `PLUGIN_SIGNING_*` production env on mangaworld-admin; local copy: `tmp/secrets/` gitignored; public pin mirrored in `dashboard/lib/plugin-keys.ts`). Phase 2-remaining (v8.10.0): `MangaSource` retired `@Deprecated(ERROR)` (removal v9.0.0); domain source fields + repository params are `SourceId`; scrapers take id/URL literals; UI resolves display via `SourceUiMapper`/`SourceUiEntry`; `BuiltinSourceIds` owns the shipped set; `MangaPagingSource`/workers/interceptor read the registry. Full migration + secrets backup recorded in `tmp/review/phase2-*.md`. Phase 2B (v8.11.0): `PluginSyncEngine` + `PluginSyncWorker` (24h ETag poll) + `PluginSyncScheduler`; `PluginIndexParser` (untrusted index, caps); `PluginDistribution` (same-host manifest rule, kind-skew tolerance); `PluginKillSwitch` (revoke/engine-cut/customs-off, remote payloads only); `OkHttpPluginFetcher` (manual redirect validation, per-hop cookies, caps); verify-before-activate via split `PluginStore.verify`/`persistVerified`; consent holds, new-source deferral, post-smoke rollback; boot resume of installed actives; dashboard `public/plugins/` + `_incoming`/`_archive` scaffolding. Phase 3 (v9.0.0): Rhino sandbox (`core/source/script/`, interpreter-locked `-1`, narrow bridge fetch/parse/select/log, quotas, dedicated dispatcher) + `ScriptRunner`/`ScriptPluginLoader` (cap + hash pin + compile gate) + `DescriptorScraper` (signed baseUrl/listPath/`chapterListSelector` over theme engines) + `PluginRunnerFactory` (new-id pairing; astro/api still runner-less → deferred); sync downloads + pins `source.js`, holds engine changes, honors `enabledByDefault`, schedules quota eviction (32 MB); `SCRIPT` enabled in production capabilities; boot resume rebuilds runners; `PluginUpgradeReconciler` revives `INCOMPATIBLE` on upgrade; backup v4 by reference; `SourceHealthMonitor` (5→degraded, 10→quarantine w/ 24h cooldown, search never counts); `MangaSource` physically removed; absolute logo URLs constrained to allow-list ∪ Cloudinary. Reviews in `tmp/review/phase3-*.md`. Stabilization (v9.0.1): `PluginTelemetry` port (`FirebasePluginTelemetry` → Crashlytics breadcrumbs; `RecordingPluginTelemetry` in tests) wired into sync sweeps (one aggregate report incl. 304 heartbeats + aborts), boot bootstrap aggregates, health transitions, and per-entry script samples; plan in `tmp/stabilization-plan-v9.md`. Consent surfaces (v9.1.0): `approveHeld` backend (re-verify + pair + enable + smoke, mirrors sync pairing) + Sources badges/sheet (`ManifestPreview` display-only parse, `PluginRowState`, index-held extras in `SourceUiMapper`) + approve/re-check actions with snackbar results.
- **Room v16**: schemas exported to `app/schemas/` via KSP `room.schemaLocation`. Migrations 8→16 hand-written in `MangaDatabase.kt`. `exportSchema = true`. v16 adds plugin_index (Phase 2A activation pointers: active/previous version, origin, status, manifest JSON).
- **Favourites vs Reading List**: `FavoriteEntity.isFavorite` boolean separate from `readingStatus` string. `removeFavorite` sets `isFavorite=false` — does NOT delete entity row
- **Public library intent (RA-7)**: the visitor library is the *reading-status* list, not favourites — `users/{uid}/favorites` public reads match `readingStatus in [...]` with no `isFavorite` check, so a deselected favourite that keeps a status still shows publicly until status-cleared. By design, not a leak.
- **No FirebaseFunctions dependency**: External API calls use raw HttpURLConnection or OkHttp
- **Cloudinary is the ONLY image upload mechanism**: Firebase Storage forbidden. App uploads proxy through dashboard's `POST /api/cloudinary/app-upload`
- **Firebase Auth is the sole provider-to-UID authority**
- **Network security**: `usesCleartextTraffic="false"` since v6.4.2. All scraper base URLs are HTTPS
- **App Check**: debug provider via `debugImplementation` only (`app/src/debug/kotlin/.../AppCheckInstaller.kt`). Release uses PlayIntegrity; no-Play-Services devices skip silently. Never import debug factory from main source set

## Glance Widgets

- Use `context.getString(R.string.xxx)` NOT `stringResource()`
- Import `androidx.compose.ui.platform.LocalContext`
- `GlanceModifier.defaultWeight()` does NOT exist in Glance 1.1.x — use `fillMaxWidth()` on track + computed inner width via `LocalSize.current.width * fraction`
- Notification IDs partitioned: progress [1001..10000], complete [20000..29999], fail [30000..39999], batch [40000..40999], firebase lane [90000..90999] (untagged notify shares one namespace — keep every lane disjoint), per-manga [70000..89999], FCM [200000..300000)

## Anonymous/Guest User Handling

- Anonymous users (`isAnonymous == true`) have restricted access:
  - Hidden: Cloud & Sync, Profile, Moderation screens
  - Hidden: comment/review compose buttons (reader sheet + community screens)
  - Visible: Browse, read manga, view comments/reviews/chat
- Access control: `isSignedIn` passed from `MainActivity` → `MangaNavGraph` → screen composables
- RTDB chat rules reject anonymous writes server-side; client gates UI too

## Source Plugins (v8.8.0+ — replaces "Scraper Architecture" below)

Each source is a `SourcePlugin` (`core/source/plugins/`): descriptor + display + scraper.
Adding a source = **one plugin class + one `@Binds` line** in `SourcePluginModule`:

1. Extend `BaseScraperImpl`, `MadaraBaseScraper`, or `MangaReaderBaseScraper` (or reuse a theme engine as-is)
2. Add a `@Singleton` plugin class (see `BuiltinSourcePlugins.kt`) with:
   - `descriptor`: id (== stored sourceId, NEVER rename), `SourceEngine`, baseUrl, `requiresVerification`,
     `allowedHosts` (base + cover/reader/API/storage CDNs from the audit), `config` from the
     `PluginConfigKeys` vocabulary (chapter-list strategy, image-Referer policy, list path…)
   - `display`: existing `R.string`/`R.drawable` (underscore naming for new PNGs)
   - `scraper`: the implementation instance
3. Bind it in `SourcePluginModule` with `@Binds @IntoMap @StringKey("sourceId")`
4. Ship a captured-style fixture under `app/src/test/resources/scrapers/` + a test driving the REAL parser (never re-implement selectors inline — tautological fixtures pass while prod breaks)
5. Remote Config needs NO edit for APK-bundled source ids (kill-switch/domain keys derive from the registry). Dynamic remotes are different: `FirebaseRemoteConfigManager` seeds defaults once, so a later `source_<id>_enabled` lookup may default false after an RC refresh; see `tmp/review/remote-plugin-review-2026-10-02.md` before adding or debugging remote ids.

**Remote plugin review (2026-10-02):** findings remediated in v9.2.0 (see below). Original report: `tmp/review/remote-plugin-review-2026-10-02.md`.

**Remediation pack (v9.2.0):** all review findings fixed. Architecture rules that follow:
- `syncOverride` is builtin-only: gate with `registry.isBuiltin(id)`; installed remotes update via `syncRemoteUpdate` (rebuild runner from candidate + persist bytes, smoke-before-register, preserve serving state).
- `approveHeld` mirrors the same pairing (builtin reuse on same-engine, new runner otherwise); consent holds download script bytes up front so approval never needs the network.
- Toggles funnel through `PluginToggleReconciler` (enabling a DISABLED-with-payload row runs approval, never a bare flip); disabling never touches the index.
- RC `applyState()` re-seeds defaults from the live registry every run (dynamic ids), and domain overrides publish for builtins only (signed remote manifests pin their own baseUrl).
- `BundledPluginLoader.bootstrapOne` never re-activates QUARANTINED/REVOKED/DISABLED records; freshness gates acquisition only, never stored bytes.
- Quarantined new-ids stay visible (row + badge) and `reverify` rebuilds from disk; malformed index entries skip (whole-document Invalid is structural-only); pilot descriptors are frozen at v1 (corrections bump version).

**Observable rows (v9.2.3):** registry membership and index status are now *streams*, because every screen previously read one snapshot and registration happens asynchronously (boot resume, sweep, approval) — the fleet report was "tap refresh N times until the source shows up, then it vanishes after a restart".
- `SourceRegistry.revision: StateFlow<Long>` bumps on every membership change (`registerVerified`, `removeAll`, `unregisterRemote`, `clearOverride`); no-op paths (refused registration, removal of an unknown id) must NOT bump.
- `PluginIndexStore.changes: Flow<Unit>` emits per committed `put`/`remove`. Default is `emptyFlow()`, so plain test fakes stay valid; `RoomPluginIndexStore` is the production implementation (conflated `MutableSharedFlow`, DROP_OLDEST).
- `SourceUiMapper` re-reads the index snapshot itself (injected `@ApplicationScope`) and exposes `entriesFlow()` / `entryFlow(id)` / `heldDetailsFlow()`. Its snapshot is one immutable value swapped atomically — records + the extras derived from them can never be read torn.
- **Rule: never snapshot rows in a ViewModel `init`.** `flowOf(mapper.entries())` and `settings.map { mapper.entries() }` both froze membership at first read (Settings, Home, Search, Browse, Latest, Suggestions, LocalStorage, UserProfile, PublicProfile all had it). Combine settings with `entriesFlow()` instead, and include row ids in any `distinctUntilChanged` key — a settings-only key silently drops row changes.
- One-shot `mapper.entry(id)` stays correct because the mapper keeps its own snapshot current; it is the traffic gate (`isServing` in `MangaDetailViewModel`), so never bypass it for an unregistered/disabled row.
- `SourceBrowseScreen` (the launcher-pin / exported-deep-link path) gates on `enabled ∈ settings.enabledSources` AND `rowState == SERVING`, and fails closed with `source_unknown` when the registry never claims the id (8 s boot-resume grace). No list screen may drive live traffic without those two checks.
- `HomeViewModel.lastSourceId` accepts any ENABLED id (the builtin-only check made it impossible to reopen Home on an installed plugin source).

**Boot diagnostics + never-invisible states (v9.2.4):** a resume that skips used to leave the source absent from every list with no badge and no on-device trace, so the 9.2.3 fleet report was undiagnosable. `BundledPluginLoader.bootstrap()` now logs one `index <id>: status=… active=… manifest=…` line per record plus one `boot <id>: activated|skipped (<reason>) <payload probe>` line per outcome (bounded, no user data), and `DiskBuild.NoRunner` carries the builder's reason instead of a bare null. On the UI side `PluginRowState.UNRESOLVED` (`plugin_unresolved` badge, `plugin_resync` action) covers a payload record with no serving runner: `SourceUiMapper` renders an extra row for ANY unregistered record with bytes (not just DISABLED/QUARANTINED) and `heldDetails()` includes ENABLED/INSTALLED facts. **Rule: a state the user can reach must be visible with a reason and a recovery action — never silently absent.**

**Train 3 — ops/distribution (no app version bump; dashboard + CI only):**
- `dashboard-checks` (sign-plugin `--selftest`/`--check-published`) is in the `needs:` of `build-release` / `create-release` / `build-summary`; the tag-push escape hatch is preserved (a red gate blocks branch pushes and PRs).
- `verify-deployed-plugins` (read-only, push/PR/**daily schedule**, no secrets) fetches the LIVE index + every manifest + `source.js`, verifies Ed25519 against the committed pin, hash-pins scripts chunked-safe, and fails when any anchor is within 30 days of the 90-day window. Absence of this check is how distribution sat undeployed and unnoticed.
- `sign-plugin.py`: validate-before-write (engine↔`--js`, future `issuedAt`, `scriptSha256` match, pinned-key match, byte-level self-check), `--promote [--dry-run]`, `_archive` coverage, and the anchor calendar. `--selftest` oracle follows `index.json` (never hardcode a published id).
- The production signing **private key is NOT in any Vercel env var** (removed 2026-10-18; signing is offline by design). Compromise procedure: `tmp/review/plugin-key-compromise-runbook.md` — containment via kill-switch first, rotate, re-sign at **new versions** (a same-version re-sign is impossible by design).

**Kill-switch containment latency (measured 2026-10-18, drill log):** RC publishes instantly, but `FirebaseRemoteConfigManager` sets `setMinimumFetchIntervalInSeconds(3600)` and the SDK persists that window — the Sources **refresh button does NOT bypass it** (it re-reads the activated local value). So containment = ≤1 h RC throttle + ≤24 h sweep (worst case ~25 h), and a published kill-switch is NOT evidence of containment: the proof is `revoked=[…]` in `PluginSync`. Correcting a prior doc claim. Procedure: `tmp/review/plugin-key-compromise-runbook.md`; drills: `tmp/review/gate-drill-log-2026-10-18.md`.

**Hardcoded `Dispatchers.IO` in a ViewModel (v9.3.2, F-1 — root-caused, not guessed):** `MangaDetailViewModel` hopped to the real `Dispatchers.IO` in 8 places. A real hop is NOT drained by `advanceUntilIdle()`, so the test finished, `Dispatchers.resetMain()` restored Android's Main dispatcher, and the resuming IO coroutine hit *"Main dispatcher had failed to initialize / getMainLooper not mocked"* → the coroutine machinery died on a worker thread → whichever unrelated `runTest` ran next failed with `UncaughtExceptionsBeforeTest`. It looked like two random flaky tests (`DetailReaderViewModelTest`, `HomeViewModelTest.init_restoresLastOpenedSource`); the real culprit was a third ViewModel's leak. **Never hardcode a dispatcher in a ViewModel** — inject `@IoDispatcher`. When a test fails with an exception that looks like it belongs to another test, read the worker's `system-out` in the `unit-test-results` artifact for the *originating* frame; the attributed test is a victim.

**Lifted revocation must re-drive (v9.3.3, drill 3):** `Revoked` is not in the unresolved set, so with an unchanged index the 304 short-circuit skipped the id entirely — lifting a kill-switch left the source dead until the publisher shipped a new version. The sweep now marks a `REVOKED` record unresolved when the current policy no longer forbids **that record's version** (judged per-version: a version-scoped kill must not look lifted, or every sweep re-drives it). Revocation without a recovery path is a one-way door. Note the recovered posture is `DISABLED`, not serving: with the runner gone the record is indistinguishable from a new arrival, so consent applies again — a revoked source must never silently resume.

**Bundled pilots are a floor, not a ceiling (v9.3.4, found on the 2-device cohort):** `hijala`/`lavascans` ship as APK assets at v1. The boot guard skipped only QUARANTINED/REVOKED/DISABLED, so an ENABLED record pointing at a **newer** synced version was overwritten by the bundled v1 on every cold start — `sync hijala -> Updated`, then the next boot logged `active=1 prev=2` and the device churned v2→v1→v2 forever. `bootstrapOne` now also skips when `record.activeVersion > bundledVersion`. **Never let an APK asset move a pointer backwards**; any shipped payload is the floor a remote update must be allowed to exceed.

**The plugin bootstrap must not queue behind the network (v9.3.5, found on the 2-device cohort):** `bundledPluginLoader.bootstrap()` is LOCAL work (APK assets + Room + prefs) but it ran *after* `firebaseStartupCoordinator.initialize()`, which awaits a Firebase session and an RC fetch. A stalled Firebase init therefore left the app with **no signed plugin overrides for the whole session** — and no diagnostic, because the coordinator never returned. Observed: boot logs after ~50 s, then not at all for 150 s+ while Auth was still in flight. The bootstrap and the mapper snapshot now run in their own coroutine, first. **Never sequence local, security-relevant initialization behind network work** — and never let a network stall silently disable a subsystem.

**`minAppVersion` must be judged by the SIGNED manifest (v9.4.0):** `syncEntry` used to return `Skipped("requires app X")` *before* fetching the manifest, which short-circuited the whole upgrade-reconcile mechanism: no manifest reached the verifier, so no `INCOMPATIBLE` marker was written, so the source was **invisible** (no row, no badge, no reason) and the reconciler had nothing to revive. The gate now lives in `ManifestParser` → `EngineCompatibility` (the signed authority — the index is untrusted), the marker is written, and `PluginUpgradeReconciler` revives it on the next app upgrade. **An early return that skips the manifest skips the marker, the badge, and the recovery.**

**Remote Config throttle is not directly observable (v9.4.1 correction):** the v9.3.1 status line logs `FetchStatus`, which the current Firebase SDK defines as a typealias for `Boolean` — it prints `true`/`false`, so it separates success from failure but **cannot** tell THROTTLED from SUCCESS. The public API exposes nothing richer. Do not claim the throttle is visible; the honest signal is "the value did not change", and the only real fix is a remote-config *version* probe.

**Test Lab device strings need the OS version (v9.4.2):** `--device model=blazer` silently allocates **0 devices** and the run fails with exit 18; the working form is `--device model=blazer,version=36` (the model id + an OS version the model actually offers — `gcloud firebase test android models list` is the reference). Physical models verified: `blazer` (Pixel 10 Pro, API 36) → 8/8 test cases pass; `pa3q` (Galaxy S25 Ultra, API 35/36) and `frankel`/`mustang` (Pixel 10 family) are also available. Dispatch a physical run with `gh workflow run build.yml -f build_type=debug -f testlab_device="model=blazer,version=36"`.

**Test Lab quota is shared between physical and virtual runs (v9.4.2):** one on-demand physical run (Pixel 10 Pro, 8 test cases) exhausted the day's budget, and the next virtual matrix run failed with `TEST_QUOTA_EXCEEDED` — *not* a regression. The job treats a quota exhaustion as a **skip with a warning**, never a failure: a branch must not be blocked because the minutes ran out, and the warning keeps the missing coverage visible. Budget accordingly — the daily virtual matrix is the standing gate; physical runs are on-demand and spend the same budget.

**Firebase Test Lab is the on-device gate (v9.4.2):** CI runs the instrumentation suite on a **3-device virtual matrix (API 26 = minSdk, 30, 34)** — every run is a FRESH INSTALL, the condition the 9.2.3 "source vanished from every list" mystery needed and no long-lived device ever reproduced. `PluginLifecycleDeviceTest` asserts on a real device what the JVM suite cannot: the bundled pilots activate from `assets/`, **no ENABLED source lacks a runner** (that is exactly the condition that hides a source from every list), every record maps to a VISIBLE row state, and re-running the bootstrap never moves a pointer backwards. Job: `firebase-test-lab` — NOT in `build-release`'s `needs` (a release must not be blocked by device infrastructure) but it fails the branch on a real regression and is reported in the summary. Gotchas: the project **cannot create a named GCS bucket** (billing account not enabled) so the CLI's own random bucket is used and its name is parsed from the run log; `--results-dir` lands in the cloud, not the workspace; the raw results carry ~130 MB of video, so the artifact keeps only the report and the logcat.

**Gate 0 telemetry is Analytics events, not Crashlytics (v9.5.0):** `FirebasePluginTelemetry` used to write only to Crashlytics log lines + custom keys — breadcrumbs that attach to crash reports and **cannot be aggregated**, so the soak's exit queries had no data source (and `trust_reject` had no emitter at all). Each sink now also emits a GA4 event so the queries are group-bys: `plugin_sync_outcome {outcome,count}` (query 1 — one event per outcome, not a packed string), `plugin_sweep {not_modified,revoked,evicted,duration_ms}`, `plugin_script_call {plugin_id,entry,version,success,duration_ms,failure}` (query 3 — `version` was missing from the interface), `plugin_health {plugin_id,from,to}`, `plugin_bootstrap {activated,skipped,reconciled}`, and `plugin_trust_reject {plugin_id,reason}` (query 4 — new). Crashlytics logging is kept for crash context. **If you add a fleet signal, add it as an analytics event — a breadcrumb is not a metric.**

**There is no user fleet to soak against (2026-10-25):** the gate says "≥2-device cohort", not "≥2 adb devices", so real installs would be *better* evidence (OEM/OS diversity, real traffic) and the v9.5.0 Analytics events work identically on user devices. But: the app has **no Play Store listing** (`play.google.com/...?id=com.exapps.mangaworld` → 404), App Distribution has **1 tester and 0 releases**, so "users' devices" is currently *zero* devices. The ~52 `users` docs in Firestore are historical profile records (including repeated test installs), not an active 9.5.0 fleet. Until the app ships on a track, the cohort must be physical devices. **GA4 fleet view is triple-blocked:** `GA4_PROPERTY_ID` is an **empty string** in Vercel production, the shared `getAccessToken()` asked for `cloud-platform` which the Data API rejects (`analytics.readonly` now used — fixed), and the service account has **no property access** (`firebase-adminsdk-fbsvc@…` needs Viewer on the GA4 property). The last two are owner actions; none require an app release.

**Snackbar reasons (v9.3.1, audit B14):** `SourcesViewModel.notice` is `SharedFlow<SourceNotice(resId, reason)>`; the engine's typed reason (`ApproveOutcome.Held/Failed(reason)`, reverify failure) is formatted into the snackbar. A bare "تعذر الاعتماد" with no reason is a diagnosable state presented as unexplained — never do that.

**Train 2 — visibility + storage integrity (v9.3.0, 256)**:
- **Every record status is a visible row.** `PluginRowState` gained `INCOMPATIBLE` / `AVAILABLE` / `REVOKED`; the mapper renders marker records that carry **no** `activeVersion` (the reconciler's INCOMPATIBLE marker always has none) with badge + `plugin_resync` action. `rowStateFor` maps each status explicitly; `heldDetails()` exposes their facts.
- **Bounded reads** (`CappedBytes.read`): both fetchers stream through a fixed buffer and throw the moment the cap is passed. The old `body.bytes()` + post-check buffered the whole body first, and a **chunked** response has no `Content-Length` for the pre-check.
- **Activation post-condition** (`persistVerified`): the pointer moves only after the manifest (and `source.js`) are read back and byte-compared on disk. Refuses with `payload did not land` otherwise — a record can never claim a version whose bytes are absent.
- **`payloadLock` mutex** serializes `persistVerified` and `evictIfOverBudget`, so quota eviction cannot delete a version dir between the immutability check and the pointer move (that race produced exactly the torn dirs above).

**Train 1 — state-machine safety (v9.2.5, 255)** — the audit's 1 P0 + P1 cluster, landed before any soak because soak data is untrustworthy while these are open:
- **Resume posture guard** (`BundledPluginLoader.resumeInstalled`): re-reads pointer **and** status; `RESUMABLE_STATUSES` = INSTALLED/ENABLED/DISABLED. `markEnabled` refuses `SAFETY_STATUSES` (QUARANTINED/REVOKED). Boot logs `posture <status>` when it backs off.
- **`persistVerified` repairs a torn version dir**: staging+move always runs; immutability now refuses only *present-and-differing* bytes (a missing manifest/script is repaired). This is also what makes the UNRESOLVED re-drive work.
- **UNRESOLVED re-drive** (`syncEntry`): `UpToDate` is only returned when `registry.pluginFor(id) != null`; a record claiming a version with nothing registered logs `re-driving` and reinstalls. The Sources "re-sync" action is no longer a dead end.
- **`reverify` always builds from disk** (`buildPluginFromDisk`) and smokes that — never `registry.scraperFor(id)`, which resolves to the builtin for an override id.
- **Health counters are version-scoped** (`Observation.version`, persisted as `v`; `Decision.ResetForVersion`). A new version gets a fresh slate including the quarantine latch, so a second broken version of the same id can still be quarantined.
- **Freshness gates acquisition only** — `approveHeld` verifies stored bytes with `enforceFreshness = false`.
- **`PluginKillSwitch.parseResult`** distinguishes `Absent` from `Malformed`; the sweep ABORTS on a malformed policy and `approveHeld` HOLDS, instead of degrading to "no policy" (which silently disabled every revocation/engine kill).
- **`persistQuietly` rejections are logged** (they used to vanish, leaving a clean-looking row and a `NoPayload` boot report with no trail).
- **`Repositories.scraper()` throws a typed `SourceUnavailable` inside `withScraper`** — every read returns a failed `Result` instead of crashing (four call sites were outside `runCatching`).
- **Home/Search/Browse/Latest filter on `enabledSources` AND `rowState == SERVING`** before touching the registry.

**Plugin state-machine invariants (audit 2026-10-18, register in `tmp/review/phase4-issues-and-plan-2026-10-18.md`):** enforced by Train 1 (v9.2.5) onward — do not regress these.
- **Resume may never resurrect or re-label a safety state.** The re-read guard in `resumeInstalled` compares `activeVersion` **and** a status allow-list; `markEnabled` never overwrites QUARANTINED/REVOKED. Quarantine/kill-switch keep `activeVersion`, so a pointer-only guard does not protect them.
- **Every recovery action must actually re-drive the install.** A sweep returns `UpToDate` when `record.activeVersion == index.version` (`PluginSyncEngine.syncEntry`) — so any "re-sync"/"re-check" affordance on a broken local payload is a dead end unless it forces the re-install path.
- **`reverify` must smoke the payload it is judging** (`buildPluginFromDisk`), never `registry.scraperFor(id)` — that resolves to the **builtin** for an override id and rubber-stamps a drifted payload.
- **Freshness gates acquisition only.** Never `enforceFreshness = true` on stored/on-disk bytes (`approveHeld`, resume, reverify) — a stored payload older than the 90-day anchor must stay usable.
- **`Repositories.scraper()` must return a failed `Result`, not `error(...)`.** A dead/removed source id crashes Home/Search/Browse; list screens filter by `enabledSources` **and** `rowState == SERVING` before touching the registry.
- **Storage is transactional**: never activate a pointer unless the manifest (+ `source.js`) exist and hash-match; install and quota eviction are serialized per id.
- **Every published trust anchor is a calendar item** (90-day window): hijala/lavascans expire 2026-12-23, manonga v1 archive 2026-12-25, manonga v2 2027-01-01. Re-issue = new `issuedAt` + **version bump** + re-sign (same-version bytes are immutable).
- **CI gate reality check**: `dashboard-checks` (plugin signature gate) must appear in the `needs:` of release jobs — verify before trusting it.

**RegExp in the script sandbox (v9.2.2):** first touch of the lazy `RegExp` builtin runs `LazilyLoadedCtor.init` → `buildClassCtor` → a re-entrant read of the still-initializing slot, which throws `IllegalStateException("RegExp")` on R8 builds only (JVM never reproduces it). `ScriptBridge.install` therefore defines it eagerly via a **direct** `NativeRegExp.init` call (no string lookup → R8-proof) and reads it back like the host-root shadowing. When a device-only failure appears in a build that unit tests cannot reproduce, ship a class+stack logging line first, pull that build's mapping artifact, and decode frames from it.

**Per-source audit evidence**: `tmp/analysis/` (2026-09-24, Chromium-verified). Treat HTTP/WAF behavior as dated; re-verify before trusting. Key corrections baked in: Starz fully working (audit 403s were egress-specific); StellarSaber is AES-**128**-GCM; Hijala cover = `.manga-info .thumb img[data-src]`, no default split-stitching; Leko/Lionz/Spark chapters via numeric `manga_get_chapters` (NOT slug-ajax); LekMangaOnline oEmbed thumbnail broken; Despair canonical = despair-world.com.

**Removed sources**: rockmanga/rocksmanga.com died upstream — enum/DI/drawable/strings/fixture deleted. Stored rows filter at repository layer, never AZORA-fallback. Same rule for any future removal.

## Scraper Architecture (New Sources)

Each scraper must:
1. Extend `BaseScraperImpl`, `MadaraBaseScraper`, or `MangaReaderBaseScraper`
2. Wrap it in a `SourcePlugin` (see above) — do NOT touch `MangaSource` beyond the enum entry, do NOT add a `Modules.kt` provider
3. **Add the id to `BuiltinSourceIds.ALL`** — this set is the registry's ownership map AND the default value of `settings.enabledSources` (`AppPreferences`). An id that is bound but absent from it is a **bound-but-invisible source**: it never appears in any list (Home/Browse/Search/Sources filter on `enabledSources`), and the failure is silent. `BuiltinSourceIds.isBuiltin/isLocal` also branch on it.
4. Add entry to `MangaSource` enum in `domain/model/Models.kt` (deprecated bridge until Phase 2)
5. Place logo PNG in `res/drawable/` (underscore naming)
6. Ship a captured-style fixture under `app/src/test/resources/scrapers/` + a test driving the REAL parser (never re-implement selectors inline — tautological fixtures pass while prod breaks)

**3asq chapters**: `{mangaUrl}/ajax/chapters/` endpoint (NOT wp-admin/admin-ajax.php)

**Lazy-loaded images**: `abs:src` may be base64 placeholder when lazy-loading active. Prefer `data-src`; skip any URL starting with `"data:"`. Guarded pattern lives in MadaraBaseScraper/OlympusScraper/StarzScraper

**Non-ASCII Referer headers**: OkHttp throws IllegalArgumentException for Arabic chars. Always `encodeForHeader()`

**Error codes in Room**: Download errors store stable tokens (`"cancelled"`, `"download_error"`, `"retry_unavailable"`), translated at render time in ReaderViewModel. Never persist localized strings

## Community System

- Threading via `parentId`, likes/dislikes proxied through dashboard API (`/api/community/vote`)
- Guest gating: compose buttons hidden; like/dislike/report wrapped in no-op lambdas when `!isSignedIn`
- Soft-deleted comments/reviews filtered server-side in GET routes AND defensively client-side
- Content moderation: local keyword check (Remote Config) + server-side check via `/api/community/moderate` endpoint. Fail-open on network errors
- TOTP secrets encrypted AES-256-GCM at rest (`lib/security.ts`); OTP attempts throttled with lockout

## Notification Center

- Chapter updates persist to SharedPreferences via `NotificationCenterStore.update {}` (mutex-serialized — raw read-modify-write races lost updates)
- Workers: ChapterUpdateChecker (throttle written AFTER sweep), SuggestionNotificationWorker (12h), FavoriteDigestWorker (6h)
- All use `CLOUD_CHANNEL_ID` (IMPORTANCE_HIGH); suggestions use dedicated LOW channel

## Firebase Security Model

- **Firestore rules** (`firestore.rules`): field-restricted publicProfiles (role not writable), email_registry locked, canModerate() reads custom claims, authorUid immutable, soft-delete anchored
- **RTDB rules** (`database.rules.json`): chat writes require non-anonymous provider + full validation (text ≤500, name ≤64, badge ≤32)
- **Dashboard API**: every route has explicit guard chain; dynamic Firestore routes whitelist collections only; MFA required for privileged ops; OTP secrets encrypted; login rate-limited per IP+email

## Dashboard API Conventions

- Every mutation route checks response body shape (bounded strings, typed booleans, capped arrays)
- Error responses use generic Arabic messages + correlation ID — never leak internal error.message to clients
- `verifyAppIdToken()` on app-facing endpoints; `requireRole()` with MFA on admin endpoints
- Rate limiting via Firestore-backed counters in `lib/security.ts` (survives serverless cold starts)

## Common Pitfalls

- **Rhino interpreter lock**: `ScriptContextFactory` is the ONLY `Context.enter` site — `optimizationLevel = -1` set in `onContextCreated`, asserted per-run by `RhinoInterpreterModeTest`. Never enable codegen (recreates dynamic code loading), never expose Java host objects (bridge passes strings/handles only; `NativeJavaObject` back across the boundary is a hard failure)
- **Script manifest rules**: `bridgeApi` script-only, `scriptSha256` required for scripts (checked at download + install + resume), `config` must be `{}` for scripts, `source.js` ≤ 512 KB, entry results validated (https page URLs, no CRLF headers, finite numbers)
- **Health counting**: home/detail/pages only (search NEVER counts); CF challenges, chapter locks, cancellations never count; quarantine lifts only via explicit re-smoke
- **Firebase Perf vs JVM unit tests**: the Perf Gradle plugin rewrites every `okhttp3.Call.execute()` call site in main to `FirebasePerfOkHttpClient.execute()`, which touches `android.os.Bundle` and crashes JVM unit tests (`Method clone in android.os.Bundle not mocked`). Instrumentation is disabled for `debug` via `FirebasePerfExtension` in `app/build.gradle.kts` (unit tests run on the debug variant; release keeps auto network tracing). If that stack reappears, the flag was lost — restore it, never work around it in product code
- **Android APIs in ViewModel unit tests**: `android.util.Log` and `android.os.SystemClock` both throw "not mocked" on the JVM. Any timing/deadline logic that runs in `viewModelScope` must use `System.nanoTime()`/`kotlin.time.TimeSource`, and log through an injectable sink (see `SourcesViewModel.log`).
- **ReaderViewModel.loadChapter in unit tests**: FORBIDDEN — calling it wedges the test worker until the 30-min step timeout (cut in v8.3.4, reproduced in v8.7.5; mechanism never isolated). Cover that path with instrumentation/emulator tests, never unit tests. `onPageChanged`/tap/viewport events are safe (no load).
- **Kotlin suspend method references**: `list.forEach(::suspendFun)` fails. Use explicit lambda `forEach { suspendFun(it) }`
- **Sequence + suspend**: `asSequence().filter { suspendCall() }` fails — Sequence lambdas defer past coroutine scope. Use eager `.filter{}`
- **`combine` max 5 flows**: Use nested combine for 6+
- **Room migrations**: ALTER TABLE ADD COLUMN works for nullable/defaulted columns. Register in MangaDatabase companion + bump version. Schema JSON auto-exported
- **Room DB < v8**: no upgrade path by decision (pre-8 DDL unrecoverable, negligible cohort) — missing migration must crash loudly, never wipe (no `fallbackToDestructiveMigration` on upgrade)
- **Room schemas**: `app/schemas/` is not committed — schemas publish as CI `room-schemas` artifacts; commit the v15 JSONs from there when present
- **DropdownMenuItem Material3 BOM 2025.01.00**: Use Text + Modifier.clickable inside DropdownMenu
- **LazyColumn keys**: Composite unique keys (source_id + url/id) — never bare url or id alone
- **Cloudflare**: `requiresVerification` triggers WebView solver; cookies expire
- **Proguard/R8**: Models and scrapers have keep-rules in proguard-rules.pro
- **Notification ID ranges**: progress [1001..10000], complete [20000..29999], fail [30000..39999], batch [40000+], firebase lane [90000..90999], per-manga [70000..89999], FCM [200000..300000). Don't overlap
- **Parental PIN**: salted PBKDF2-SHA256 (120k iter) with legacy hashCode upgrade path — don't simplify
- **Backup schemaVersion**: currently v3; imports of older versions accepted; newer versions rejected
- **Favourites architecture**: `isFavorite` boolean separate from `readingStatus`. `removeFavorite` sets `isFavorite=false`, doesn't delete entity

## Domain Migrations (completed)

| Old | New | Source |
|---|---|---|
| lek-manga.net | mangalik.net | LEKMANGA |
| 3asq.org | 3asq.online | ASQ3 |

Source IDs (`lekmanga`, `asq3`) unchanged — only baseUrl strings migrated.

## Removed Features

- **WidgetShelf**: entirely removed (file, XML, manifest receiver, strings, coordinator refs)
- **notifications/history page**: duplicate of working history tab — deleted
- **use-auth.ts / use-firestore.ts / role-guard.tsx**: dead code with latent bugs — deleted
