package com.example.agent.memory

import com.example.agent.storage.dao.MemoryItemDao
import com.example.agent.storage.entity.MemoryItemEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Priority 2 — long-term memory repository (episodic, knowledge, learning).
 *
 * All writes funnel through [remember]/[recordEpisodic]/[recordLearning] so keywords are
 * always derived consistently by [MemoryRetriever.extractKeywords]. Learning items follow
 * the DOC/context-2.md pipeline: created as `candidate`, promoted to `active` (or marked
 * obsolete) via [promoteLearning]/[rejectLearning].
 */
class MemoryRepository(private val dao: MemoryItemDao) {

    suspend fun remember(
        content: String,
        type: String = MemoryItemEntity.TYPE_KNOWLEDGE,
        conversationId: String? = null,
        source: String = MemoryItemEntity.SOURCE_MANUAL
    ): MemoryItemEntity {
        val item = MemoryItemEntity(
            id = UUID.randomUUID().toString(),
            type = type,
            content = content.trim(),
            keywords = MemoryRetriever.extractKeywords(content),
            status = MemoryItemEntity.STATUS_ACTIVE,
            conversationId = conversationId,
            source = source,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(item)
        return item
    }

    /** Episodic memory: compact summaries and notable interaction events. */
    suspend fun recordEpisodic(
        summary: String,
        conversationId: String?,
        source: String
    ): MemoryItemEntity = remember(
        content = summary,
        type = MemoryItemEntity.TYPE_EPISODIC,
        conversationId = conversationId,
        source = source
    )

    /**
     * Learning pipeline step 1: a lesson starts as a candidate derived from an error or
     * correction. It becomes part of the agent context only after promotion.
     */
    suspend fun recordLearning(lesson: String, conversationId: String?): MemoryItemEntity {
        val item = MemoryItemEntity(
            id = UUID.randomUUID().toString(),
            type = MemoryItemEntity.TYPE_LEARNING,
            content = lesson.trim(),
            keywords = MemoryRetriever.extractKeywords(lesson),
            status = MemoryItemEntity.STATUS_CANDIDATE,
            conversationId = conversationId,
            source = MemoryItemEntity.SOURCE_LEARNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(item)
        return item
    }

    /** Learning pipeline step 2: validate a candidate into an active rule. */
    suspend fun promoteLearning(id: String) {
        dao.updateStatus(id, MemoryItemEntity.STATUS_ACTIVE, System.currentTimeMillis())
    }

    suspend fun rejectLearning(id: String) {
        dao.updateStatus(id, MemoryItemEntity.STATUS_OBSOLETE, System.currentTimeMillis())
    }

    suspend fun markObsolete(id: String) = rejectLearning(id)

    /** RAG recall: top matching active memories for [query]. Marks them as used. */
    suspend fun recall(query: String, limit: Int = 5): List<MemoryItemEntity> {
        val items = dao.getRecent(200)
        val ranked = MemoryRetriever.rank(items, query, limit)
        if (ranked.isNotEmpty()) {
            dao.markUsed(ranked.map { it.first.id }, System.currentTimeMillis())
        }
        return ranked.map { it.first }
    }

    suspend fun getRecent(limit: Int): List<MemoryItemEntity> = dao.getRecent(limit)
    suspend fun getByType(type: String): List<MemoryItemEntity> = dao.getByType(type)
    suspend fun learningCandidates(): List<MemoryItemEntity> =
        dao.getByTypeAndStatus(MemoryItemEntity.TYPE_LEARNING, MemoryItemEntity.STATUS_CANDIDATE)

    fun getByTypeFlow(type: String): Flow<List<MemoryItemEntity>> = dao.getByTypeFlow(type)
    fun getRecentFlow(limit: Int): Flow<List<MemoryItemEntity>> = dao.getRecentFlow(limit)

    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun countByType(type: String): Int = dao.countByType(type)
}
