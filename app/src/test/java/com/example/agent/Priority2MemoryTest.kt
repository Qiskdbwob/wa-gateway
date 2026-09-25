package com.example.agent

import com.example.agent.memory.CompactManager
import com.example.agent.memory.ContextManager
import com.example.agent.memory.MemoryRepository
import com.example.agent.memory.MemoryRetriever
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.storage.InMemoryAgentSessionRepository
import com.example.agent.storage.entity.MemoryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priority 2 — long-term memory, lexical RAG and compaction.
 *
 * These tests pin the behaviour the agent relies on: only relevant memories are recalled,
 * recalled items are marked as used, the learning pipeline keeps a human gate
 * (candidate -> active), and compaction folds old turns into an episodic memory plus a
 * system summary instead of losing them silently.
 */
class Priority2MemoryTest {

    private fun knowledge(
        id: String,
        content: String,
        updatedAt: Long = System.currentTimeMillis()
    ) = MemoryItemEntity(
        id = id,
        type = MemoryItemEntity.TYPE_KNOWLEDGE,
        content = content,
        keywords = MemoryRetriever.extractKeywords(content),
        updatedAt = updatedAt,
        createdAt = updatedAt
    )

    // ==========================================
    // Retriever
    // ==========================================

    @Test
    fun priority2_keywordExtractionDropsStopwordsAndDuplicates() {
        val keywords = MemoryRetriever.extractKeywords("Kapan ulang tahun istri saya? HARI ini!")
        assertTrue(keywords.contains("ulang"))
        assertTrue(keywords.contains("tahun"))
        assertTrue(keywords.contains("istri"))
        assertTrue(keywords.contains("hari"))
        // Stopwords must not be stored, otherwise every memory matches every query.
        assertFalse(keywords.contains("saya"))
        assertFalse(keywords.contains("kapan"))
        // Distinct tokens only.
        assertEquals(keywords.split(" ").distinct().size, keywords.split(" ").size)
    }

    @Test
    fun priority2_rankingPrefersRelevantAndRecentMemories() {
        val now = 1_700_000_000_000L
        val relevant = knowledge(
            id = "m1",
            content = "Ulang tahun istri saya Sari adalah 12 Maret.",
            updatedAt = now
        )
        val alsoRelevant = knowledge(
            id = "m2",
            content = "Istri saya suka kopi tanpa gula.",
            updatedAt = now - 90L * 86_400_000L // very old: no recency boost
        )
        val irrelevant = knowledge(id = "m3", content = "Server produksi memakai PostgreSQL.", updatedAt = now)

        val ranked = MemoryRetriever.rank(
            items = listOf(alsoRelevant, irrelevant, relevant),
            query = "kapan ulang tahun istri saya?",
            limit = 5,
            nowMs = now
        )

        assertEquals(listOf("m1", "m2"), ranked.map { it.first.id })
        assertTrue(ranked.first().second >= ranked.last().second)
        // Irrelevant memory never reaches the prompt.
        assertTrue(ranked.none { it.first.id == "m3" })
    }

    @Test
    fun priority2_rankReturnsNothingForAnUnusableQuery() {
        val now = 1_700_000_000_000L
        val item = knowledge(id = "m1", content = "Fakta penting", updatedAt = now)
        assertTrue(MemoryRetriever.rank(listOf(item), "dan atau", limit = 5, nowMs = now).isEmpty())
        assertTrue(MemoryRetriever.rank(listOf(item), "", limit = 5, nowMs = now).isEmpty())
    }

    // ==========================================
    // Repository + learning pipeline
    // ==========================================

    @Test
    fun priority2_rememberThenRecallMarksTheMemoryAsUsed() = runBlocking {
        val dao = FakeMemoryItemDao()
        val memory = MemoryRepository(dao)

        val stored = memory.remember("Nama kucing saya Milo.", MemoryItemEntity.TYPE_KNOWLEDGE)
        assertTrue(stored.keywords.isNotBlank())
        assertEquals(MemoryItemEntity.STATUS_ACTIVE, stored.status)

        val recalled = memory.recall("siapa nama kucing saya?", limit = 5)
        assertEquals(listOf(stored.id), recalled.map { it.id })
        assertEquals(listOf(stored.id), dao.lastMarkedUsed)
        assertEquals(1, dao.items[stored.id]?.useCount)

        // A query with no overlap must return nothing instead of the whole memory bank.
        assertTrue(memory.recall("berapa harga saham hari ini", limit = 5).isEmpty())
    }

    @Test
    fun priority2_learningPipelineKeepsCandidateUntilPromoted() = runBlocking {
        val dao = FakeMemoryItemDao()
        val memory = MemoryRepository(dao)

        val candidate = memory.recordLearning("Jangan mengirim pesan lebih dari 3 kali.", conversationId = "chat-1")
        assertEquals(MemoryItemEntity.STATUS_CANDIDATE, candidate.status)
        assertEquals(1, memory.learningCandidates().size)

        memory.promoteLearning(candidate.id)
        assertEquals(MemoryItemEntity.STATUS_ACTIVE, dao.items[candidate.id]?.status)
        assertTrue(memory.learningCandidates().isEmpty())

        val second = memory.recordLearning("Hindari menjawab saat API key kosong.", conversationId = null)
        memory.rejectLearning(second.id)
        assertEquals(MemoryItemEntity.STATUS_OBSOLETE, dao.items[second.id]?.status)
        // Obsolete items disappear from type listings (they must not be re-learned).
        assertTrue(memory.getByType(MemoryItemEntity.TYPE_LEARNING).none { it.id == second.id })
    }

    // ==========================================
    // Context manager
    // ==========================================

    @Test
    fun priority2_contextManagerAddsMemoryAndLearningSections() {
        val base = "Kamu adalah asisten WhatsApp."
        val prompt = ContextManager.buildSystemPrompt(
            basePrompt = base,
            relevantMemories = listOf(knowledge("m1", "Ulang tahun istri 12 Maret.")),
            activeLearnings = listOf(
                MemoryItemEntity(
                    id = "l1",
                    type = MemoryItemEntity.TYPE_LEARNING,
                    content = "Selalu konfirmasi sebelum menghapus file.",
                    status = MemoryItemEntity.STATUS_ACTIVE
                )
            )
        )

        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains("## Pelajaran dari pengalaman sebelumnya"))
        assertTrue(prompt.contains("Selalu konfirmasi sebelum menghapus file."))
        assertTrue(prompt.contains("## Memori jangka panjang yang relevan"))
        assertTrue(prompt.contains("Ulang tahun istri 12 Maret."))
    }

    @Test
    fun priority2_contextManagerCapsEachSection() {
        val many = (1..200).map { knowledge("m$it", "Memori nomor $it dengan isi yang cukup panjang untuk mengisi konteks.") }
        val prompt = ContextManager.buildSystemPrompt("base", many, many)
        assertTrue(prompt.contains("...(memori lain dipotong)"))
        // The whole prompt stays bounded (two 1200-char sections + persona + labels).
        assertTrue(prompt.length < 4_000)

        // No memories at all -> the base prompt is returned untouched.
        assertEquals("base", ContextManager.buildSystemPrompt("base", emptyList(), emptyList()))
    }

    // ==========================================
    // Compaction
    // ==========================================

    @Test
    fun priority2_compactFoldsOldTurnsIntoEpisodicMemory() = runBlocking {
        val sessionRepo = InMemoryAgentSessionRepository()
        val memoryDao = FakeMemoryItemDao()
        val memory = MemoryRepository(memoryDao)
        val compactManager = CompactManager(sessionRepo, memory)

        val session = sessionRepo.getOrCreateSession("628111")
        val start = 1_700_000_000_000L
        repeat(12) { index ->
            sessionRepo.saveMessage(
                AgentMessage(
                    sessionId = session.sessionId,
                    role = if (index % 2 == 0) AgentRole.USER else AgentRole.ASSISTANT,
                    content = "Pesan nomor $index tentang rencana perjalanan ke Bandung.",
                    timestamp = start + index * 1_000L
                )
            )
        }
        assertEquals(12, sessionRepo.getMessages(session.sessionId, 100).size)

        val result = compactManager.compactIfNeeded(
            sessionId = session.sessionId,
            conversationId = "628111",
            maxMessages = 5,
            keepRecent = 4,
            force = true,
            provider = null
        )

        assertTrue(result.compacted)
        assertEquals(8, result.summarizedTurns)
        assertNotNull(result.summary)

        // The summary survives as episodic memory bound to the conversation.
        val episodic = memory.getByType(MemoryItemEntity.TYPE_EPISODIC)
        assertEquals(1, episodic.size)
        assertEquals("628111", episodic.first().conversationId)
        assertEquals(MemoryItemEntity.SOURCE_AUTO_COMPACT, episodic.first().source)

        // ...and as a system message the next context build will read.
        val remaining = sessionRepo.getMessages(session.sessionId, 100)
        val summaryMessage = remaining.firstOrNull { it.role == AgentRole.SYSTEM }
        assertNotNull(summaryMessage)
        assertTrue(summaryMessage!!.content.startsWith(CompactManager.SUMMARY_PREFIX))
        // Old raw turns are gone; the recent ones are kept.
        assertEquals(5, remaining.size)
    }

    @Test
    fun priority2_compactIsASafeNoOpOnShortConversations() = runBlocking {
        val sessionRepo = InMemoryAgentSessionRepository()
        val memory = MemoryRepository(FakeMemoryItemDao())
        val compactManager = CompactManager(sessionRepo, memory)

        val session = sessionRepo.getOrCreateSession("628222")
        repeat(3) { index ->
            sessionRepo.saveMessage(
                AgentMessage(
                    sessionId = session.sessionId,
                    role = AgentRole.USER,
                    content = "Pesan singkat $index",
                    timestamp = 1_700_000_000_000L + index * 1_000L
                )
            )
        }

        val result = compactManager.compactIfNeeded(
            sessionId = session.sessionId,
            conversationId = "628222",
            maxMessages = 30,
            force = false,
            provider = null
        )

        assertFalse(result.compacted)
        assertEquals(0, result.summarizedTurns)
        assertEquals(3, sessionRepo.getMessages(session.sessionId, 100).size)
        assertTrue(memory.getByType(MemoryItemEntity.TYPE_EPISODIC).isEmpty())
    }
}
