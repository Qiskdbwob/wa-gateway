package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Priority 6 — Background subagent task records (Phase 14 of DOC/context-2.md).
 *
 * The main agent delegates via the `delegate_task` tool; the subagent runs on its own
 * coroutine in the background while the main agent immediately keeps chatting. When the
 * subagent finishes, its result is pushed to the user's chat as a new agent message.
 *
 * The record is also the observability surface: it shows up on the Tasks screen with
 * QUEUED/RUNNING/COMPLETED/FAILED status.
 */
@Entity(
    tableName = "agent_tasks",
    indices = [Index(value = ["status"]), Index(value = ["conversationId"]), Index(value = ["createdAt"])]
)
data class AgentTaskEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val task: String,
    val agentName: String,
    val modelId: String,
    val systemPrompt: String,
    /** Whether the subagent may use the registered SAFE tools. */
    val toolsEnabled: Boolean = true,
    val conversationId: String,
    val channel: String = "whatsapp",
    val status: String = STATUS_QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val parentConversationId: String = conversationId,
    /** Set when the task was started by the scheduler instead of a chat message. */
    val scheduledTaskId: String? = null
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
