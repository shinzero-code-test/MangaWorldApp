package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.local.dao.PluginDao
import com.exapps.mangaworld.core.data.local.entity.PluginEntity
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2A activation-pointer record. Mirrors [PluginEntity]; the interface keeps
 * [PluginStore] JVM-testable with a fake while production uses Room.
 */
data class PluginIndexRecord(
    val id: String,
    val activeVersion: Int?,
    val previousVersion: Int?,
    val origin: PluginOrigin,
    val status: PluginStatus,
    val manifestJson: String?,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Narrow persistence port for activation pointers (Room in prod, fake in tests). */
interface PluginIndexStore {
    suspend fun get(id: String): PluginIndexRecord?
    suspend fun getAll(): List<PluginIndexRecord>
    suspend fun put(record: PluginIndexRecord)
    suspend fun remove(id: String): Boolean
}

@Singleton
class RoomPluginIndexStore @Inject constructor(
    private val dao: PluginDao,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) : PluginIndexStore {
    override suspend fun get(id: String): PluginIndexRecord? = withContext(io) {
        dao.getById(id)?.toRecord()
    }

    override suspend fun getAll(): List<PluginIndexRecord> = withContext(io) {
        dao.getAll().map { it.toRecord() }
    }

    override suspend fun put(record: PluginIndexRecord) = withContext(io) {
        dao.upsert(record.toEntity())
    }

    override suspend fun remove(id: String): Boolean = withContext(io) {
        val existed = dao.getById(id) != null
        if (existed) dao.delete(id)
        existed
    }

    private fun PluginEntity.toRecord() = PluginIndexRecord(
        id = id,
        activeVersion = activeVersion,
        previousVersion = previousVersion,
        origin = runCatching { PluginOrigin.valueOf(origin) }.getOrDefault(PluginOrigin.OFFICIAL),
        status = runCatching { PluginStatus.valueOf(status) }.getOrDefault(PluginStatus.INVALID),
        manifestJson = manifestJson,
        updatedAt = updatedAt
    )

    private fun PluginIndexRecord.toEntity() = PluginEntity(
        id = id,
        activeVersion = activeVersion,
        previousVersion = previousVersion,
        origin = origin.name,
        status = status.name,
        manifestJson = manifestJson,
        updatedAt = updatedAt
    )
}
