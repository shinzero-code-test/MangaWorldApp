# AGENTS.md — MangaWorld App

## Project Overview
Arabic manga reader Android app (Kotlin + Jetpack Compose). Single-module `:app` project with Hilt DI, Room DB, OkHttp+Jsoup scraping, and Coil image loading. Targets API 26+ (Android 8.0).

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

# Full CI pipeline (what GitHub Actions runs)
./gradlew :app:lintDebug :app:testDebugUnitTest --stacktrace
```

**CI order**: lint → unit tests → debug build → release build → verify artifacts

## Key Architecture Facts

- **Package**: `com.exapps.mangaworld`
- **Layers**: `core/` (data, DI, Firebase, ML, widgets), `domain/` (models, repositories), `presentation/` (Compose UI, ViewModels, navigation)
- **DI**: Hilt with multibindings for scrapers (`@IntoMap @StringKey("sourceId")`)
- **Scrapers**: Each manga source has a scraper class in `core/data/remote/scraper/`. New sources must be added to both the `MangaSource` enum AND the Hilt `ScraperModule`.
- **Base scrapers**: `BaseScraperImpl` (common), `MadaraBaseScraper` (Madara WordPress sites), `MangaReaderBaseScraper` (MangaReader theme sites). Most Arabic sources use one of these.
- **Database**: Room with DAOs in `core/data/local/dao/`. Entities in `core/data/local/entity/`.
- **Firebase**: Auth, Firestore, Remote Config, App Distribution, Crashlytics, Performance. Config in `core/firebase/`.

## Scraper Architecture (Critical for New Sources)

Each scraper must:
1. Extend `BaseScraperImpl`, `MadaraBaseScraper`, or `MangaReaderBaseScraper`
2. Add an entry to `MangaSource` enum in `domain/model/Models.kt` (id, displayName, baseUrl, requiresVerification, themeType, logoRes)
3. Register in `core/di/Modules.kt` `ScraperModule` with `@Provides @Singleton @IntoMap @StringKey("sourceId")`
4. Place logo PNG in `app/src/main/res/drawable/` (underscore naming, no hyphens, must start with letter)

**URL path conventions vary by Madara fork** — some use `/manga/{slug}/`, others `/comics/{slug}/` or `/manhwa/{slug}/`. Check actual site HTML before implementing.

## Cloudflare Handling

- Sites behind Cloudflare show `WebViewSolverActivity` when `requiresVerification = true`
- After solving, cookies go to both `CookieCache` (in-memory) and `SettingsRepository` (persistent)
- SourceBrowseViewModel auto-triggers solver once; manual retry if it fails again
- The solver clears old cookies before loading to prevent immediate close

## Testing Scraper Changes

Scraper fixture tests exist: `./gradlew :app:testDebugUnitTest --tests "*ScraperFixtureTest*"`. HTML snapshots for analysis live in `tmp/ar-sources/`.

## Firebase Distribution

The CI distributes to Firebase App Distribution on push. The "Create GitHub Release" step requires a tag push and may fail if the Firebase app isn't configured — this is expected and not a build failure.

## Signing

Release signing uses env vars: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. A debug keystore is auto-generated if missing. CI builds unsigned releases when no keystore is available.

## Common Pitfalls

- **Drawable naming**: Files in `drawable/` must use underscores (not hyphens), start with a letter, and be valid resource names. Resource references use `R.drawable.filename`.
- **Scraper selector matching**: CSS selectors for manga sites vary by WordPress theme fork (Madara vs MangaReader vs custom). Always verify against actual site HTML in `tmp/ar-sources/`.
- **Cloudflare**: Some sources are CF-protected. The `requiresVerification` flag on `MangaSource` triggers the WebView solver. Even after solving, cookies expire.
- **URL paths**: Madara sites use different URL paths (`/manga/`, `/comics/`, `/manhwa/`). The `listPath` property on scrapers controls this.
- **Build**: Release builds may fail Firebase Distribution if the app isn't registered — this is a CI config issue, not a code failure.
