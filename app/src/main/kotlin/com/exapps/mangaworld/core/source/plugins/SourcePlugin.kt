package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper

/**
 * Phase 1 plugin surface: one plugin per source.
 *
 * - [descriptor] carries the frozen schema-v1 facts (identity, engine, hosts, policy).
 * - [display] carries APK-local display bindings (string/drawable resources).
 * - [scraper] is the existing [MangaScraper] implementation, unchanged.
 *
 * Built-ins construct all three in code; remote descriptors (Phase 2) will supply
 * [descriptor] from signed JSON while the engine supplies [scraper].
 */
interface SourcePlugin {
    val descriptor: PluginManifest
    val display: SourceDisplay
    val scraper: MangaScraper
}

/** APK-local display bindings for a built-in plugin (replaces enum nameRes/logoRes). */
data class SourceDisplay(
    val nameRes: Int,
    val logoRes: Int
)

/**
 * Standard `config` vocabulary (Phase 1): the only keys a plugin manifest may use,
 * derived from the 2026-09-24 source audit (`tmp/analysis/`). Engines consume these
 * incrementally; unknown keys are a schema violation, so this object is the registry
 * of what "deviation" is expressible.
 */
object PluginConfigKeys {
    /**
     * How chapter lists are obtained: `embedded` (server-rendered rows),
     * `slugAjax` (POST `{mangaUrl}/ajax/chapters/`), `numericAction`
     * (`manga_get_chapters` with numeric manga id). Never assume one Madara route.
     */
    const val CHAPTER_LIST_STRATEGY = "chapterListStrategy"

    /** Ajax chapter path for [CHAPTER_LIST_STRATEGY] `slugAjax` (default `/ajax/chapters/`). */
    const val CHAPTER_AJAX_PATH = "chapterAjaxPath"

    /** Admin-ajax action for [CHAPTER_LIST_STRATEGY] `numericAction` (default `manga_get_chapters`). */
    const val CHAPTER_ACTION = "chapterAction"

    /** Archive path override (`/comics/`, `/manhwa/`, …; default `/manga/`). */
    const val LIST_PATH = "listPath"

    /**
     * Reader-image Referer policy: `chapter` (exact chapter URL — required by
     * tempsolo/tempmore/templikey/templuner/templeo/tempsparkio hosts), `site`
     * (origin root), `none` (verified open). Default `chapter` is the safe superset.
     */
    const val IMAGE_REFERER_POLICY = "imageRefererPolicy"

    /** Live-search admin-ajax action override (default `wp-manga-search-manga`). */
    const val SEARCH_ACTION = "searchAction"
}

/** Allowed [PluginConfigKeys.CHAPTER_LIST_STRATEGY] values. */
object ChapterListStrategy {
    const val EMBEDDED = "embedded"
    const val SLUG_AJAX = "slugAjax"
    const val NUMERIC_ACTION = "numericAction"
}

/** Allowed [PluginConfigKeys.IMAGE_REFERER_POLICY] values. */
object ImageRefererPolicy {
    const val CHAPTER = "chapter"
    const val SITE = "site"
    const val NONE = "none"
}
