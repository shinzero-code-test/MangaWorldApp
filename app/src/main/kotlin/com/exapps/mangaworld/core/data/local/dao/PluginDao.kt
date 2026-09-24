package com.exapps.mangaworld.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.exapps.mangaworld.core.data.local.entity.PluginEntity

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugin_index WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PluginEntity?

    @Query("SELECT * FROM plugin_index ORDER BY id ASC")
    suspend fun getAll(): List<PluginEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PluginEntity)

    @Query("DELETE FROM plugin_index WHERE id = :id")
    suspend fun delete(id: String)
}
