package com.example.agent.storage

import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentSession
import com.example.agent.storage.db.AgentDatabase
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.storage.entity.AgentMessageEntity
import com.example.agent.storage.entity.AgentSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class RoomAgentSessionRepository(
    private val database: AgentDatabase
) : AgentSessionRepository {

    private val sessionDao = database.sessionDao()
    private val messageDao = database.messageDao()
    private val configDao = database.configDao()

    override suspend fun getOrCreateSession(conversationId: String, agentId: String): AgentSession = withContext(Dispatchers.IO) {
        val existing = sessionDao.findByConversationId(conversationId)
        if (existing != null) {
            return@withContext existing.toDomain()
        }

        val newSession = AgentSession(
            sessionId = UUID.randomUUID().toString(),
            agentId = agentId,
            channel = "whatsapp",
            conversationId = conversationId,
            title = conversationId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.insertSession(AgentSessionEntity.fromDomain(newSession))
        newSession
    }

    override suspend fun getMessages(sessionId: String, limit: Int): List<AgentMessage> = withContext(Dispatchers.IO) {
        messageDao.getRecentMessages(sessionId, limit).map { it.toDomain() }
    }

    override suspend fun saveMessage(message: AgentMessage) = withContext(Dispatchers.IO) {
        messageDao.insertMessage(AgentMessageEntity.fromDomain(message))

        val preview = if (message.content.length > 60) {
            message.content.take(57) + "..."
        } else {
            message.content
        }

        sessionDao.recordNewMessage(
            sessionId = message.sessionId,
            preview = preview,
            updatedAt = message.timestamp
        )

        // Bounded history pruning (keep last 100 messages to prevent unbounded token growth)
        try {
            messageDao.pruneOldMessages(message.sessionId, keepCount = 100)
        } catch (_: Exception) {
            // Non-critical optimization
        }
    }

    override suspend fun clearSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        messageDao.deleteMessagesForSession(sessionId)
        sessionDao.findById(sessionId)?.let { existing ->
            sessionDao.updateSession(
                existing.copy(
                    lastMessagePreview = null,
                    messageCount = 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        Unit
    }

    override suspend fun deleteSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        sessionDao.deleteSessionById(sessionId)
        Unit
    }

    override suspend fun getAllSessions(): List<AgentSession> = withContext(Dispatchers.IO) {
        sessionDao.getAllSessions().map { it.toDomain() }
    }

    override fun getAllSessionsFlow(): Flow<List<AgentSession>> {
        return sessionDao.getAllSessionsFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getMessagesFlow(sessionId: String): Flow<List<AgentMessage>> {
        return messageDao.getMessagesFlow(sessionId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun deleteMessagesUpTo(sessionId: String, upTo: Long): Unit = withContext(Dispatchers.IO) {
        messageDao.deleteMessagesUpTo(sessionId, upTo)
        Unit
    }

    suspend fun saveConfig(config: AgentConfigEntity) = withContext(Dispatchers.IO) {
        configDao.saveConfig(config)
    }

    suspend fun getConfig(): AgentConfigEntity? = withContext(Dispatchers.IO) {
        configDao.getConfig()
    }

    fun getConfigFlow(): Flow<AgentConfigEntity?> {
        return configDao.getConfigFlow()
    }
}
