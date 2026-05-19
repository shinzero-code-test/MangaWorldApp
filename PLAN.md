# MangaWorld Roadmap

## Stability and quality
- Add live integration smoke tests for Olympus, Azora, Starz, Mangasid, and Meshmanga using recorded fixtures plus optional nightly live checks.
- Add source health monitoring with per-source status, last successful scrape time, and failure reason.
- Add a lightweight in-app diagnostics screen for cookies, widget state, shortcut state, and source availability.
- Persist structured timestamps for latest updates instead of display-only `timeAgo` text to improve sorting and widgets.

## Reader enhancements
- Save chapter URL directly in reading history so Continue Reading widgets and shortcuts never depend on cached chapter lists.
- Add per-manga reading sessions, accurate reading-time analytics, and per-day activity summaries.
- Add next/previous chapter prefetching and optional low-bandwidth image mode.
- Add chapter bookmarks, notes, and resume markers within a page.

## Library and discovery
- Build a dedicated Latest Updates screen with source filters, unread-only mode, and open-directly-to-chapter actions.
- Add recommendation ranking based on reading history, genres, completion habits, and source preference instead of simple trending aggregation.
- Add smart collections: recently updated favorites, almost caught up, completed series, dropped series, and hidden series.
- Add offline-first cover caching and refresh expiry rules for library-heavy users.

## Widgets and shortcuts
- Add widget configuration screens for source filters, compact/expanded layouts, and refresh intervals.
- Add pinned shortcuts for favorite manga and widget actions to jump directly into latest unread chapters.
- Add richer widget states: loading, stale data warning, no-network fallback, and last refresh time.
- Add widget analytics/debug logging to track failed launches and stale snapshots.

## More data to scrape and save
- Save canonical manga aliases, alternative titles, author, artist, release year, status history, and source-specific tags.
- Save chapter metadata beyond number/title: chapter ID, canonical URL, upload timestamp, translator/editor group, and paywall/access flags.
- Save per-series popularity snapshots over time: views, rating count, average rating, follower count, and chapter growth.
- Save image host/domain metadata and anti-bot requirements per source to make reader/download handling more reliable.
- Save homepage ranking buckets per source: featured, trending, latest, popular this week, and editor picks.
- Save source taxonomy maps: genres, types, status labels, and tag synonyms for better cross-source filtering.

## New source opportunities
- Add more Arabic sources that expose stable chapter URLs and structured update feeds.
- Add source adapters for sites with JSON/Astro/Next.js embedded data before adding purely DOM-driven sites.
- Prioritize sources with reliable genre catalogs, latest update feeds, and full chapter metadata.

## Engineering follow-up
- Introduce a dedicated source abstraction for "latest updates" and "recommendations" instead of reusing homepage parsing everywhere.
- Add repository methods for widget/shortcut use cases so UI-independent integrations stop reading DAOs directly.
- Replace destructive Room fallback migration with explicit schema migrations for new history/statistics fields.
- Add CI checks for widgets, deep links, and shortcuts, including manifest/resource validation.
