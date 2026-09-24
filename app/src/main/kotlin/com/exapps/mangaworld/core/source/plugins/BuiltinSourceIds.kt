package com.exapps.mangaworld.core.source.plugins

/**
 * Phase 2-remaining: the shipped source set as pure data.
 *
 * Replaces `MangaSource.entries` for every non-UI need (defaults, guards, sync
 * filters). UI display (names/logos) goes through [SourceUiMapper]; networking
 * through descriptors + [com.exapps.mangaworld.domain.model.SourceDomainOverrides].
 * Remote Phase-2B sources will extend the *registry*, never this list.
 */
object BuiltinSourceIds {

    val ALL: Set<String> = setOf(
        "olympus", "azora", "starz", "mangasid", "meshmanga",
        "asq3", "lekmanga", "lekmangaonline", "likemanga", "linkmanga",
        "mangaleko", "mangalionz", "areascans", "hijala", "lavascans",
        "stellarsaber", "procomic"
    )

    /** Sources from the v4.0.0 wave (shown on the Sources grid; originals above them are not). */
    val NEW: Set<String> = setOf(
        "asq3", "lekmanga", "lekmangaonline", "likemanga", "linkmanga",
        "mangaleko", "mangalionz", "areascans", "hijala", "lavascans",
        "stellarsaber", "procomic"
    )

    /** Local/imported rows are not online sources (navigation, workers, solver guards). */
    fun isLocal(id: String): Boolean = id == "imported" || id == "local"

    /** Shipped online source (dead/removed ids like rockmanga return false). */
    fun isBuiltin(id: String): Boolean = id in ALL

    /** Selectable online source id (builtin set; registry is authoritative at runtime). */
    fun isKnown(id: String): Boolean = isBuiltin(id)
}
