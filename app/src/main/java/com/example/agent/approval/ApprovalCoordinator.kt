package com.example.agent.approval

import com.example.agent.storage.dao.ApprovalRequestDao
import com.example.agent.storage.entity.ApprovalRequestEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Priority 3 — approval coordinator for CONFIRM-class (destructive) tools.
 *
 * A WhatsApp agent cannot pop a desktop dialog, so approval flows through the chat:
 * the requester gets a request ID and answers with `/approve <id>` or `/reject <id>`.
 * Requests expire after [ApprovalRequestEntity.TTL_MS] to avoid stale approvals being
 * executed much later.
 */
class ApprovalCoordinator(private val dao: ApprovalRequestDao) {

    /** Creates a pending request; returns it so the loop can surface the ID to the user. */
    suspend fun createRequest(
        conversationId: String,
        toolName: String,
        arguments: String,
        channel: String = "whatsapp"
    ): ApprovalRequestEntity {
        val request = ApprovalRequestEntity(
            id = "appr-" + UUID.randomUUID().toString().take(8),
            conversationId = conversationId,
            toolName = toolName,
            arguments = arguments,
            status = ApprovalRequestEntity.STATUS_PENDING,
            requestedAt = System.currentTimeMillis(),
            channel = channel
        )
        dao.upsert(request)
        return request
    }

    /** Resolves [requestId] as approved/rejected; false when the id is unknown or stale. */
    suspend fun resolve(requestId: String, approved: Boolean): ApprovalRequestEntity? {
        val request = dao.findById(requestId.trim()) ?: return null
        if (request.status != ApprovalRequestEntity.STATUS_PENDING) return null
        val isExpired = System.currentTimeMillis() - request.requestedAt > ApprovalRequestEntity.TTL_MS
        if (isExpired) {
            dao.resolve(requestId, ApprovalRequestEntity.STATUS_EXPIRED, System.currentTimeMillis())
            return null
        }
        dao.resolve(
            requestId,
            if (approved) ApprovalRequestEntity.STATUS_APPROVED else ApprovalRequestEntity.STATUS_REJECTED,
            System.currentTimeMillis()
        )
        return request
    }

    suspend fun get(requestId: String): ApprovalRequestEntity? = dao.findById(requestId.trim())

    suspend fun pendingFor(conversationId: String): List<ApprovalRequestEntity> =
        dao.getPendingForConversation(conversationId)

    suspend fun expireStale() {
        val now = System.currentTimeMillis()
        dao.expireOld(now - ApprovalRequestEntity.TTL_MS, now)
        dao.purgeResolvedBefore(now - 7L * 24 * 60 * 60 * 1000)
    }

    fun pendingFlow(): Flow<List<ApprovalRequestEntity>> = dao.getPendingFlow()
}
