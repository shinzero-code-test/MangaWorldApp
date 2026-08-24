# AGENTS.md — MangaWorld App

## System Instructions

- Don't use head or tail commands — always read the full output/log.
- For every fix do a patch; for every new feature, do a minor; for important high-impact features, do a major version update.
- Never build the app locally. Always use CI/CD.
- git, gh, vercel, Firebase, gcloud CLIs are already installed and authenticated — use them directly.
- Always use skills, tools, and MCPs for better results.
- When doing code reviews, parallelize with sub-agents, each saving to its own MD file in `tmp/review/`. Sub-agents must not write over each other's results.

## Project Overview

Arabic manga reader Android app (Kotlin + Jetpack Compose). Single-module `:app` project.
- **Package**: `com.exapps.mangaworld`
- **Current version**: 7.0.0 (versionCode 170)
- **Min SDK**: 26 (Android 8.0) · **Target SDK**: 35 · **Compile SDK**: 35
- **JDK**: 17 (required by CI and build)

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

## Key Architecture Facts

- **DI**: Hilt multibindings for scrapers (`@IntoMap @StringKey("sourceId")`) — 18 sources, all match enum ↔ DI ↔ logo drawables
- **Base scrapers**: `BaseScraperImpl`, `MadaraBaseScraper` (Madara WordPress), `MangaReaderBaseScraper` (MangaReader theme), plus custom scrapers. Shared parsing: `ScraperText.firstChapterNumber()` / `.slugFromHref()` / `.extractViews()`
- **Room v14**: schemas exported to `app/schemas/` via KSP `room.schemaLocation`. Migrations 8→14 hand-written in `MangaDatabase.kt`. `exportSchema = true`
- **Favourites vs Reading List**: `FavoriteEntity.isFavorite` boolean separate from `readingStatus` string. `removeFavorite` sets `isFavorite=false` — does NOT delete entity row
- **No FirebaseFunctions dependency**: External API calls use raw HttpURLConnection or OkHttp
- **Cloudinary is the ONLY image upload mechanism**: Firebase Storage forbidden. App uploads proxy through dashboard's `POST /api/cloudinary/app-upload`
- **Firebase Auth is the sole provider-to-UID authority**
- **Network security**: `usesCleartextTraffic="false"` since v6.4.2. All scraper base URLs are HTTPS
- **App Check**: debug provider via `debugImplementation` only (`app/src/debug/kotlin/.../AppCheckInstaller.kt`). Release uses PlayIntegrity; no-Play-Services devices skip silently. Never import debug factory from main source set

## Glance Widgets

- Use `context.getString(R.string.xxx)` NOT `stringResource()`
- Import `androidx.compose.ui.platform.LocalContext`
- `GlanceModifier.defaultWeight()` does NOT exist in Glance 1.1.x — use `fillMaxWidth()` on track + computed inner width via `LocalSize.current.width * fraction`
- Notification IDs partitioned: progress [1001..10000], complete [20000..29999], fail [30000..39999], batch [40000..40999]

## Anonymous/Guest User Handling

- Anonymous users (`isAnonymous == true`) have restricted access:
  - Hidden: Cloud & Sync, Profile, Moderation screens
  - Hidden: comment/review compose buttons (reader sheet + community screens)
  - Visible: Browse, read manga, view comments/reviews/chat
- Access control: `isSignedIn` passed from `MainActivity` → `MangaNavGraph` → screen composables
- RTDB chat rules reject anonymous writes server-side; client gates UI too

## Scraper Architecture (New Sources)

Each scraper must:
1. Extend `BaseScraperImpl`, `MadaraBaseScraper`, or `MangaReaderBaseScraper`
2. Add entry to `MangaSource` enum in `domain/model/Models.kt`
3. Register in `core/di/Modules.kt` ScraperModule with `@Provides @Singleton @IntoMap @StringKey("sourceId")`
4. Place logo PNG in `res/drawable/` (underscore naming)

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

- **Kotlin suspend method references**: `list.forEach(::suspendFun)` fails. Use explicit lambda `forEach { suspendFun(it) }`
- **Sequence + suspend**: `asSequence().filter { suspendCall() }` fails — Sequence lambdas defer past coroutine scope. Use eager `.filter{}`
- **`combine` max 5 flows**: Use nested combine for 6+
- **Room migrations**: ALTER TABLE ADD COLUMN works for nullable/defaulted columns. Register in MangaDatabase companion + bump version. Schema JSON auto-exported
- **DropdownMenuItem Material3 BOM 2025.01.00**: Use Text + Modifier.clickable inside DropdownMenu
- **LazyColumn keys**: Composite unique keys (source_id + url/id) — never bare url or id alone
- **Cloudflare**: `requiresVerification` triggers WebView solver; cookies expire
- **Proguard/R8**: Models and scrapers have keep-rules in proguard-rules.pro
- **Notification ID ranges**: progress [1001..10000], complete [20000..29999], fail [30000..39999], batch [40000+]. Don't overlap
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
