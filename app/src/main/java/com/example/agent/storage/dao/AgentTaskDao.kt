package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.AgentTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun findById(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN ('QUEUED','RUNNING') ORDER BY createdAt DESC")
    fun getActiveFlow(): Flow<List<AgentTaskEntity>>

    @Query("UPDATE agent_tasks SET status = :status, startedAt = :startedAt WHERE id = :id")
    suspend fun markRunning(id: String, status: String, startedAt: Long)

    @Query("UPDATE agent_tasks SET status = :status, completedAt = :completedAt, result = :result, error = :error WHERE id = :id")
    suspend fun markFinished(id: String, status: String, completedAt: Long, result: String?, error: String?)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}
