package com.exapps.mangaworld.core.firebase

object RemoteSelectorOverridesStore {
    @Volatile
    private var overrides: Map<String, Map<String, String>> = emptyMap()

    fun replaceAll(next: Map<String, Map<String, String>>) {
        overrides = next
    }

    fun selector(sourceId: String, key: String, default: String): String =
        overrides[sourceId]?.get(key)?.takeIf { it.isNotBlank() } ?: default
}
