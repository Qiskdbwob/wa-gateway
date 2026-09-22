package com.example.agent.storage

import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

interface AgentSessionRepository {
    suspend fun getOrCreateSession(conversationId: String, agentId: String = "default-agent"): AgentSession
    suspend fun getMessages(sessionId: String, limit: Int = 20): List<AgentMessage>
    suspend fun saveMessage(message: AgentMessage)
    suspend fun clearSession(sessionId: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun getAllSessions(): List<AgentSession>
    fun getAllSessionsFlow(): Flow<List<AgentSession>>
    fun getMessagesFlow(sessionId: String): Flow<List<AgentMessage>>
}

class InMemoryAgentSessionRepository : AgentSessionRepository {
    private val sessionsByConversation = ConcurrentHashMap<String, AgentSession>()
    private val messagesBySession = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    private val _sessionsFlow = MutableStateFlow<List<AgentSession>>(emptyList())

    private fun updateFlow() {
        _sessionsFlow.value = sessionsByConversation.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun getOrCreateSession(conversationId: String, agentId: String): AgentSession {
        val session = sessionsByConversation.computeIfAbsent(conversationId) {
            AgentSession(
                agentId = agentId,
                conversationId = conversationId
            )
        }
        updateFlow()
        return session
    }

    override suspend fun getMessages(sessionId: String, limit: Int): List<AgentMessage> {
        val list = messagesBySession[sessionId] ?: return emptyList()
        synchronized(list) {
            return list.takeLast(limit).toList()
        }
    }

    override suspend fun saveMessage(message: AgentMessage) {
        val list = messagesBySession.computeIfAbsent(message.sessionId) {
            mutableListOf()
        }
        synchronized(list) {
            list.add(message)
            if (list.size > 100) {
                list.removeAt(0)
            }
        }
        // Update session stats
        sessionsByConversation.values.find { it.sessionId == message.sessionId }?.let { session ->
            val preview = if (message.content.length > 60) message.content.take(57) + "..." else message.content
            val updated = session.copy(
                updatedAt = System.currentTimeMillis(),
                lastMessagePreview = preview,
                messageCount = session.messageCount + 1
            )
            sessionsByConversation[session.conversationId] = updated
        }
        updateFlow()
    }

    override suspend fun clearSession(sessionId: String) {
        messagesBySession.remove(sessionId)
        sessionsByConversation.values.find { it.sessionId == sessionId }?.let { session ->
            sessionsByConversation[session.conversationId] = session.copy(
                lastMessagePreview = null,
                messageCount = 0,
                updatedAt = System.currentTimeMillis()
            )
        }
        updateFlow()
    }

    override suspend fun deleteSession(sessionId: String) {
        messagesBySession.remove(sessionId)
        val conversationToRemove = sessionsByConversation.values.find { it.sessionId == sessionId }?.conversationId
        if (conversationToRemove != null) {
            sessionsByConversation.remove(conversationToRemove)
        }
        updateFlow()
    }

    override suspend fun getAllSessions(): List<AgentSession> {
        return sessionsByConversation.values.sortedByDescending { it.updatedAt }
    }

    override fun getAllSessionsFlow(): Flow<List<AgentSession>> {
        return _sessionsFlow.asStateFlow()
    }

    override fun getMessagesFlow(sessionId: String): Flow<List<AgentMessage>> {
        val list = messagesBySession[sessionId] ?: emptyList()
        return MutableStateFlow(list.toList())
    }
}
