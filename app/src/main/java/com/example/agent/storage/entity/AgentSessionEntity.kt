package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.agent.model.AgentSession

@Entity(
    tableName = "agent_sessions",
    indices = [
        Index(value = ["conversationId"], unique = true),
        Index(value = ["updatedAt"])
    ]
)
data class AgentSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val agentId: String = "default-agent",
    val channel: String = "whatsapp",
    val conversationId: String,
    val title: String? = null,
    val lastMessagePreview: String? = null,
    val messageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): AgentSession {
        return AgentSession(
            sessionId = sessionId,
            agentId = agentId,
            channel = channel,
            conversationId = conversationId,
            title = title,
            lastMessagePreview = lastMessagePreview,
            messageCount = messageCount,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun fromDomain(domain: AgentSession): AgentSessionEntity {
            return AgentSessionEntity(
                sessionId = domain.sessionId,
                agentId = domain.agentId,
                channel = domain.channel,
                conversationId = domain.conversationId,
                title = domain.title,
                lastMessagePreview = domain.lastMessagePreview,
                messageCount = domain.messageCount,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt
            )
        }
    }
}
