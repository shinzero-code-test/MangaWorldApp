package com.exapps.mangaworld.core.firebase

object RemoteSelectorOverridesStore {
    @Volatile
    private var overrides: Map<String, Map<String, String>> = emptyMap()

    fun replaceAll(next: Map<String, Map<String, String>>) {
        overrides = next
    }

    /** Current map — lets callers keep the previous value on parse failure. */
    fun snapshot(): Map<String, Map<String, String>> = overrides

    fun selector(sourceId: String, key: String, default: String): String =
        overrides[sourceId]?.get(key)?.takeIf { it.isNotBlank() } ?: default
}
