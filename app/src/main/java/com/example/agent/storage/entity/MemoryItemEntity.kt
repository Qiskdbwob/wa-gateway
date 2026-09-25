package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Priority 2 — Long-Term Memory (Phase 11/12 of DOC/context-2.md).
 *
 * One table for the persistent memory layers the agent reads back into its context:
 *  - EPISODIC:  important events from interactions (incl. auto-compact summaries)
 *  - KNOWLEDGE: durable facts the user told the agent to remember
 *  - LEARNING:  lessons derived from errors, promoted candidate → active (learning
 *               pipeline, status in [status])
 *
 * Retrieval is a lexical RAG: a keyword-overlap score over [content] + [keywords]
 * recency-boosted, computed by the MemoryRetriever (no external embedding service).
 */
@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["type"]),
        Index(value = ["status"]),
        Index(value = ["conversationId"]),
        Index(value = ["updatedAt"])
    ]
)
data class MemoryItemEntity(
    @PrimaryKey
    val id: String,
    val type: String, // MemoryItemEntity.TYPE_EPISODIC / TYPE_KNOWLEDGE / TYPE_LEARNING
    val content: String,
    val keywords: String = "", // space-separated lowercase tokens
    val status: String = STATUS_ACTIVE, // STATUS_ACTIVE / STATUS_CANDIDATE / STATUS_OBSOLETE
    val conversationId: String? = null,
    val source: String = "", // e.g. "auto_compact", "remember_tool", "learning_pipeline"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val useCount: Int = 0
) {
    companion object {
        const val TYPE_EPISODIC = "EPISODIC"
        const val TYPE_KNOWLEDGE = "KNOWLEDGE"
        const val TYPE_LEARNING = "LEARNING"

        const val STATUS_ACTIVE = "active"
        const val STATUS_CANDIDATE = "candidate"
        const val STATUS_OBSOLETE = "obsolete"

        const val SOURCE_AUTO_COMPACT = "auto_compact"
        const val SOURCE_MANUAL = "remember_tool"
        const val SOURCE_LEARNING = "learning_pipeline"
    }
}
