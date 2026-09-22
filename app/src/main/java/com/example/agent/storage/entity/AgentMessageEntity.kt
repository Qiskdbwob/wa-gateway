package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole

@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"])
    ]
)
data class AgentMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toDomain(): AgentMessage {
        val agentRole = try {
            AgentRole.valueOf(role.uppercase())
        } catch (_: Exception) {
            AgentRole.USER
        }
        return AgentMessage(
            id = id,
            sessionId = sessionId,
            role = agentRole,
            content = content,
            timestamp = timestamp
        )
    }

    companion object {
        fun fromDomain(domain: AgentMessage): AgentMessageEntity {
            return AgentMessageEntity(
                id = domain.id,
                sessionId = domain.sessionId,
                role = domain.role.name,
                content = domain.content,
                timestamp = domain.timestamp
            )
        }
    }
}
