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
- **Current version**: 8.10.0 (versionCode 236)
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
- **Source plugins (Phase 0, v8.8.0 train)**: contract package `core/source/plugins/` — pure Kotlin, zero Android deps, JVM-CI-safe. Frozen schema v1 (`PluginManifest`), JCS (RFC 8785) canonicalization minus `signature`, strict duplicate-key rejection, closed engine vocab, literal-host allow-lists, compat-gate-before-schema. Deps: Tink 1.18.0 (raw Ed25519 verify), Jackson tree-model-only (no reflection/R8 rules), erdtman JCS 1.1. Contract tests: 11 suites under `core/source/` tests. `requiresPermission` flag (robots-gated APIs — never auto-enable), `SourceEngine.CUSTOM` is builtin-only (rejected for remote manifests). Full plan: `tmp/source-plugin-plan-v3.md`. Phase 1 (v8.8.0): `SourcePlugin`/`SourceRegistry`/`SourceDisplayResolver`, 17 builtin plugins, rockmanga removed. Phase 2A (v8.9.0): `PluginTrust` (pinned `official-1` key; RC cross-signed rotation; 90-day trust age on downloads only), `PluginStore` + `plugin_index` (staging → verify → immutable `files/plugins/<id>/versions/<n>/`, pointer activation/rollback, quota), verified official overrides in `SourceRegistry` (custom/local never shadow), bundled pilots hijala+lavascans (signed JSON + existing scrapers, behavior-identical), `MangaScraper.pluginDescriptor` hook, `MangaSource.fromId` deprecated (use `fromIdOrNull`). Private plugin key in dashboard secrets only — never in repo (live: Vercel `PLUGIN_SIGNING_*` production env on mangaworld-admin; local copy: `tmp/secrets/` gitignored; public pin mirrored in `dashboard/lib/plugin-keys.ts`). Phase 2-remaining (v8.10.0): `MangaSource` retired `@Deprecated(ERROR)` (removal v9.0.0); domain source fields + repository params are `SourceId`; scrapers take id/URL literals; UI resolves display via `SourceUiMapper`/`SourceUiEntry`; `BuiltinSourceIds` owns the shipped set; `MangaPagingSource`/workers/interceptor read the registry. Full migration + secrets backup recorded in `tmp/review/phase2-*.md`.
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
5. Remote Config needs NO edit (kill-switch/domain keys derive from the registry)

**Per-source audit evidence**: `tmp/analysis/` (2026-09-24, Chromium-verified). Treat HTTP/WAF behavior as dated; re-verify before trusting. Key corrections baked in: Starz fully working (audit 403s were egress-specific); StellarSaber is AES-**128**-GCM; Hijala cover = `.manga-info .thumb img[data-src]`, no default split-stitching; Leko/Lionz/Spark chapters via numeric `manga_get_chapters` (NOT slug-ajax); LekMangaOnline oEmbed thumbnail broken; Despair canonical = despair-world.com.

**Removed sources**: rockmanga/rocksmanga.com died upstream — enum/DI/drawable/strings/fixture deleted. Stored rows filter at repository layer, never AZORA-fallback. Same rule for any future removal.

## Scraper Architecture (New Sources)

Each scraper must:
1. Extend `BaseScraperImpl`, `MadaraBaseScraper`, or `MangaReaderBaseScraper`
2. Wrap it in a `SourcePlugin` (see above) — do NOT touch `MangaSource` beyond the enum entry, do NOT add a `Modules.kt` provider
3. Add entry to `MangaSource` enum in `domain/model/Models.kt` (deprecated bridge until Phase 2)
4. Place logo PNG in `res/drawable/` (underscore naming)
5. Ship a captured-style fixture under `app/src/test/resources/scrapers/` + a test driving the REAL parser (never re-implement selectors inline — tautological fixtures pass while prod breaks)

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
