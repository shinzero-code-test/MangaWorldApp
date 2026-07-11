package com.exapps.mangaworld.core.data.local

data class SyncTombstone(
    val collection: String = "",
    val documentId: String = "",
    val deletedAt: Long = 0L
) {
    val key: String
        get() = "$collection|$documentId"
}
