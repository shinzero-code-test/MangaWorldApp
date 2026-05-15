# MangaWorld

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="100" alt="MangaWorld Logo"/>
</p>

<p align="center">
  <strong>Arabic manga reader Android app — built with Kotlin + Jetpack Compose</strong>
</p>

<p align="center">
  <a href="https://github.com/youssef-deveg/MangaWorld/actions/workflows/build.yml"><img src="https://github.com/youssef-deveg/MangaWorld/actions/workflows/build.yml/badge.svg" alt="Build Status"/></a>
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-7C4DFF?logo=kotlin" />
  <img src="https://img.shields.io/badge/Compose-BOM_2025.01-00E5FF?logo=jetpackcompose" />
  <img src="https://img.shields.io/badge/Min_SDK-26_(Android_8)-green" />
  <img src="https://img.shields.io/badge/Target_SDK-35_(Android_15)-green" />
</p>

---

## Sources

| Source | URL | Stack |
|--------|-----|-------|
| **Olympus Staff** | olympustaff.com | Bootstrap + Tailwind + Laravel |
| **Azora Moon** | azoramoon.com | Astro.js + Tailwind + SSR |
| **Manga Starz** | manga-starz.net | WordPress + Madara Theme |

---

## Features

- **Home** — Latest chapters + trending manga aggregated from all 3 sources
- **Browse / Search** — Unified search with genre filtering, status, and type filters
- **Library** — Favorites list + reading history with per-manga progress tracking
- **Manga Detail** — Chapter list with read/download status indicators and progress per chapter
- **Reader** — Multiple modes: vertical scroll, horizontal RTL/LTR, webtoon mode; auto-saves progress
- **Downloads** — Background chapter downloads via WorkManager with progress tracking, offline reading, download management screen
- **Settings** — Theme (dark/light/system), reader customization, source toggles, download preferences (WiFi-only)
- **Cloudflare Bypass** — Built-in WebView solver for Cloudflare-protected sites
- **Localization** — Full Arabic UI (RTL support)

---

## Architecture

```
Clean Architecture + MVVM + Single Activity + Jetpack Compose
```

```
:app
├── presentation/          ← Compose Screens + ViewModels
│   ├── home/
│   ├── browse/
│   ├── detail/
│   ├── reader/
│   ├── library/
│   ├── search/
│   ├── settings/
│   ├── downloads/
│   ├── onboarding/
│   ├── webview/
│   ├── navigation/
│   ├── components/        ← Shared UI components (MangaGrid, MangaPageImage, etc.)
│   └── theme/             ← Design system (colors, typography, shapes)
├── domain/                ← Business logic layer
│   ├── model/             ← Domain models (MangaItem, Chapter, ChapterPage, ...)
│   └── repository/        ← Repository interfaces
└── core/
    ├── data/
    │   ├── local/         ← Room database + DataStore preferences
    │   │   ├── dao/       ← DAO interfaces (FavoriteDao, ReadingProgressDao, DownloadTaskDao, ...)
    │   │   └── entity/    ← Room entities
    │   ├── download/      ← DownloadQueueManager + ChapterDownloadWorker (WorkManager)
    │   └── remote/
    │       └── scraper/   ← Jsoup-based scrapers (BaseScraper + 3 site implementations)
    └── di/                ← Hilt dependency injection modules
```

### Architecture Decisions

- **Repository pattern** — All data access goes through repository interfaces, enabling testability and source swapping
- **Scraper abstraction** — `BaseScraperImpl` defines the contract; each site extends it with site-specific CSS selectors and parsing logic
- **ViewModel + StateFlow** — Each screen has a dedicated ViewModel exposing `StateFlow<UiState>` for unidirectional data flow
- **Room + DataStore** — Room for structured data (favorites, history, progress, cache, download tasks); DataStore for simple key-value preferences
- **WorkManager** — Background chapter downloads survive process death and respect network constraints
- **Hilt DI** — Singleton components for scrapers, repositories, database, and managers

---

## CSS Selectors

### Olympus (olympustaff.com)
| Element | Selector |
|---------|----------|
| Manga card | `.box > .uta` |
| Cover image | `.box .imgu img[src]` |
| Title | `.box .info h3` |
| Chapter card | `.chapter-card[data-number][data-date]` |
| Reader pages | `.reading-content .page-break img.manga-chapter-img` |

### Azora (azoramoon.com)
| Element | Selector |
|---------|----------|
| Manga link | `a[href^="/series/"]` |
| Cover image | `div[class*="aspect-[2/3]"] img` |
| Title | `h2.font-bold` |
| Chapter list | `div.mt-4.space-y-2 > div` |
| Reader pages | `.comic-images-wrapper figure img` |

### Starz (manga-starz.net) — Madara Theme
| Element | Selector |
|---------|----------|
| Manga card | `div.page-item-detail.manga` |
| Cover image | `.item-thumb img.img-responsive` |
| Title | `.post-title a` |
| Chapter list | `.listing-chapters_wrap li` |
| Reader pages | `.reading-content img` |

---

## Testing

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run scraper fixture tests only
./gradlew testDebugUnitTest --tests "*ScraperFixtureTest*"

# Run lint checks
./gradlew lintDebug
```

The project includes fixture-based scraper tests that validate HTML parsing against saved samples for all 3 sources (detail pages + genre pages).

---

## Local Build

```bash
# Debug (unsigned, for testing)
./gradlew assembleDebug

# Release (requires keystore)
KEYSTORE_PATH=/path/to/keystore.jks ./gradlew assembleRelease

# All ABI splits
./gradlew assembleDebug  # produces: arm64-v8a, armeabi-v7a, x86, x86_64, universal

# AAB for Play Store
./gradlew bundleRelease
```

### Release Environment Variables
```
KEYSTORE_BASE64   ← base64-encoded keystore (for CI)
KEYSTORE_PASSWORD ← keystore password
KEY_ALIAS         ← signing key alias
KEY_PASSWORD      ← key password
```

---

## GitHub Actions

The CI pipeline (`.github/workflows/build.yml`) runs automatically:

| Trigger | Actions |
|---------|---------|
| Push to `main`/`master` | Lint + Unit Tests + Debug APKs (all ABIs) |
| Tag `v*` | Release APKs + AAB + GitHub Release |
| Manual dispatch | Selectable: debug / release / both |

**Debug APKs produced for:**
| ABI | Target |
|-----|--------|
| `arm64-v8a` | Modern devices (Pixel, Samsung Galaxy, etc.) |
| `armeabi-v7a` | Legacy 32-bit devices |
| `x86_64` | Modern emulators |
| `x86` | Legacy emulators |
| `universal` | Single APK covering all ABIs |

---

## Tech Stack

| Component | Library | Version |
|-----------|---------|---------|
| UI | Jetpack Compose + Material 3 | BOM 2025.01 |
| Navigation | Navigation Compose | 2.8.5 |
| HTML Parsing | Jsoup | 1.18.3 |
| Networking | OkHttp | 4.12.0 |
| Database | Room | 2.6.1 |
| Preferences | DataStore | 1.1.1 |
| Image Loading | Coil | 2.7.0 |
| DI | Hilt | 2.54 |
| Paging | Paging 3 | 3.3.5 |
| Background Work | WorkManager | 2.10.0 |
| Testing | JUnit 4 | 4.13.2 |

---

## APK Output Structure

```
app/build/outputs/apk/debug/
├── app-arm64-v8a-debug.apk    ~4MB
├── app-armeabi-v7a-debug.apk  ~3.5MB
├── app-x86-debug.apk          ~5MB
├── app-x86_64-debug.apk       ~5MB
└── app-universal-debug.apk    ~6MB
```

---

## License

```
MIT License — for personal and educational use
```

> Disclaimer: This app uses web scraping to access publicly available content.
> Use responsibly and respect each site's terms of service.
