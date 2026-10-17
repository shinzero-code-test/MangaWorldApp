package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.data.local.dao.PluginDao
import com.exapps.mangaworld.core.data.local.entity.PluginEntity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * Emits once per committed mutation (v9.2.3). Observers that snapshot
     * plugin rows/statuses re-read from here, so no consumer has to poll or
     * guess when a sync/approval/health/boot write landed.
     *
     * Default is `emptyFlow()`: a store with no signal (test fakes, callers
     * that re-read explicitly) stays valid — observers simply keep whatever
     * explicit re-read they already perform.
     */
    val changes: Flow<Unit> get() = emptyFlow()
}

@Singleton
class RoomPluginIndexStore @Inject constructor(
    private val dao: PluginDao,
    @com.exapps.mangaworld.core.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) : PluginIndexStore {
    /**
     * Conflated: a burst of writes (a sweep touches one record per source) only
     * needs to wake observers once — they re-read the whole snapshot anyway.
     */
    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    override suspend fun get(id: String): PluginIndexRecord? = withContext(io) {
        dao.getById(id)?.toRecord()
    }

    override suspend fun getAll(): List<PluginIndexRecord> = withContext(io) {
        dao.getAll().map { it.toRecord() }
    }

    override suspend fun put(record: PluginIndexRecord) {
        withContext(io) {
            dao.upsert(record.toEntity())
            _changes.tryEmit(Unit)
        }
    }

    override suspend fun remove(id: String): Boolean = withContext(io) {
        val existed = dao.getById(id) != null
        if (existed) {
            dao.delete(id)
            _changes.tryEmit(Unit)
        }
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
