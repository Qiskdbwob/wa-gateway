package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.AgentConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: AgentConfigEntity)

    @Query("SELECT * FROM agent_configs WHERE id = :id LIMIT 1")
    suspend fun getConfig(id: String = AgentConfigEntity.DEFAULT_CONFIG_ID): AgentConfigEntity?

    @Query("SELECT * FROM agent_configs WHERE id = :id LIMIT 1")
    fun getConfigFlow(id: String = AgentConfigEntity.DEFAULT_CONFIG_ID): Flow<AgentConfigEntity?>
}
