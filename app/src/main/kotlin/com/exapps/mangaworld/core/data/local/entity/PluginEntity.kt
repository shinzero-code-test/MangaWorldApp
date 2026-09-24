package com.exapps.mangaworld.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 2A plugin index row: one per source id. Points at immutable version
 * directories (`files/plugins/<id>/versions/<n>/plugin.json`); the registry serves
 * exactly [activeVersion]. [previousVersion] is the rollback target, retained until
 * the new active passes post-activation smoke. Payloads live on disk — backups store
 * references (`{id, version, origin}`), never bytes.
 */
@Entity(tableName = "plugin_index")
data class PluginEntity(
    @PrimaryKey val id: String,
    val activeVersion: Int?,
    val previousVersion: Int?,
    /** PluginOrigin name (OFFICIAL/CUSTOM/LOCAL). */
    val origin: String,
    /** PluginStatus name. */
    val status: String,
    /** Last verified manifest JSON (display + debugging), null until first install. */
    val manifestJson: String?,
    val updatedAt: Long = System.currentTimeMillis()
)
