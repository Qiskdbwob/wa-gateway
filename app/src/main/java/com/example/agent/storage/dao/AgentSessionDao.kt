package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.agent.storage.entity.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: AgentSessionEntity)

    @Update
    suspend fun updateSession(session: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions WHERE conversationId = :conversationId LIMIT 1")
    suspend fun findByConversationId(conversationId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findById(sessionId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    fun getAllSessionsFlow(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessions(): List<AgentSessionEntity>

    @Query("UPDATE agent_sessions SET updatedAt = :updatedAt, lastMessagePreview = :preview, messageCount = messageCount + 1 WHERE sessionId = :sessionId")
    suspend fun recordNewMessage(sessionId: String, preview: String, updatedAt: Long)

    @Query("DELETE FROM agent_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM agent_sessions")
    suspend fun clearAllSessions()
}
