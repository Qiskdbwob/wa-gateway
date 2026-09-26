package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.McpServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface McpServerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("SELECT * FROM mcp_servers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers ORDER BY name COLLATE NOCASE ASC")
    fun getAllFlow(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun findById(id: String): McpServerEntity?

    @Query("SELECT * FROM mcp_servers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): McpServerEntity?

    @Query("UPDATE mcp_servers SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}
