package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Priority 3 — Approval requests for destructive tool calls (Phase 9 of DOC/context-2.md,
 * scoped to CONFIRM tools only: this is a WhatsApp agent, so approvals come back through
 * the same chat instead of a desktop dialog).
 *
 * A request is created when the model asks for a CONFIRM tool. The requester receives an
 * ID and replies with `/approve <id>` (or `/reject <id>`); the pending turn is then
 * executed or dropped.
 */
@Entity(
    tableName = "approval_requests",
    indices = [Index(value = ["status"]), Index(value = ["requestedAt"])]
)
data class ApprovalRequestEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val toolName: String,
    /** Raw tool argument JSON the model produced. */
    val arguments: String,
    val status: String = STATUS_PENDING, // STATUS_PENDING / STATUS_APPROVED / STATUS_REJECTED / STATUS_EXPIRED
    val requestedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    /** Where the executed result should be sent. */
    val channel: String = "whatsapp"
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_EXPIRED = "EXPIRED"

        /** Requests older than this are treated as dead and may be cleaned up. */
        const val TTL_MS: Long = 24 * 60 * 60 * 1000L
    }
}
