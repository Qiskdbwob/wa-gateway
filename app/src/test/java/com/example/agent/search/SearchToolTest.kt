package com.example.agent.search

import com.example.agent.tool.SearchEverythingTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unified search (`search` tool).
 *
 * The tool exists to cut round-trips, so the tests pin the two things that decide whether it
 * actually helps: the ranking is relevance-first (and never matches on a single letter), and the
 * `all` scope cannot let one store crowd out the others.
 */
class SearchToolTest {

    private fun hit(scope: String, id: String, title: String, snippet: String, at: Long = 0L) =
        SearchHit(scope = scope, id = id, title = title, snippet = snippet, timestamp = at)

    private class FakeSources(
        private val memoryHits: List<SearchHit> = emptyList(),
        private val chatHits: List<SearchHit> = emptyList(),
        private val taskHits: List<SearchHit> = emptyList(),
        private val fileHits: List<SearchHit> = emptyList(),
        private val toolHits: List<SearchHit> = emptyList()
    ) : SearchSources {
        val calls = mutableListOf<String>()

        override suspend fun memory(term: String, limit: Int): List<SearchHit> {
            calls += "memory"
            return memoryHits
        }

        override suspend fun chat(term: String, limit: Int): List<SearchHit> {
            calls += "chat"
            return chatHits
        }

        override suspend fun tasks(term: String, limit: Int): List<SearchHit> {
            calls += "tasks"
            return taskHits
        }

        override suspend fun files(term: String, limit: Int): List<SearchHit> {
            calls += "files"
            return fileHits
        }

        override fun tools(term: String, limit: Int): List<SearchHit> {
            calls += "tools"
            return toolHits
        }
    }

    // ==========================================
    // Ranker
    // ==========================================

    @Test
    fun rankerPrefersPhraseInTitleOverScatteredTokens() {
        val phraseTitle = hit("memory", "m1", "nama istri pengguna", "Sinta")
        val scattered = hit("memory", "m2", "catatan", "nama kucing dan istri tetangga")

        assertTrue(
            SearchRanker.score("nama istri", phraseTitle.title, phraseTitle.snippet) >
                SearchRanker.score("nama istri", scattered.title, scattered.snippet)
        )
    }

    @Test
    fun rankerIgnoresSingleLetterQueriesAndDropsNonMatches() {
        assertEquals(0, SearchRanker.score("a", "apa saja", "ada dimana-mana"))
        assertTrue(SearchRanker.tokenize("a b cd e").contains("cd"))
        assertFalse(SearchRanker.tokenize("a b").contains("a"))

        val ranked = SearchRanker.rank(
            query = "sinta",
            hits = listOf(
                hit("memory", "m1", "istri", "Sinta suka kopi"),
                hit("memory", "m2", "hobi", "bersepeda")
            ),
            limit = 5
        )
        assertEquals(listOf("m1"), ranked.map { it.id })
    }

    @Test
    fun rankerBreaksTiesByNewestAndHonoursLimit() {
        val ranked = SearchRanker.rank(
            query = "proyek",
            hits = listOf(
                hit("chat", "old", "proyek", "proyek lama", at = 100),
                hit("chat", "new", "proyek", "proyek baru", at = 200)
            ),
            limit = 1
        )
        assertEquals(listOf("new"), ranked.map { it.id })
    }

    @Test
    fun scopeNormalisationFallsBackToAll() {
        assertEquals(SearchScope.MEMORY, SearchScope.normalize("MEMORY"))
        assertEquals(SearchScope.FILES, SearchScope.normalize(" files "))
        assertEquals(SearchScope.ALL, SearchScope.normalize("chat-history"))
        assertEquals(SearchScope.ALL, SearchScope.normalize(null))
    }

    // ==========================================
    // Tool
    // ==========================================

    @Test
    fun toolRejectsMissingOrTooShortQueryWithoutCallingSources() = runBlocking {
        val sources = FakeSources()
        val tool = SearchEverythingTool(sources)

        val missing = tool.execute("{}")
        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("query"))

        val tooShort = tool.execute("""{"query":"a"}""")
        assertFalse(tooShort.success)

        assertTrue(sources.calls.isEmpty())
    }

    @Test
    fun toolSingleScopeOnlyTouchesThatSource() = runBlocking {
        val sources = FakeSources(
            memoryHits = listOf(hit("memory", "m1", "fakta penting", "Sinta ulang tahun 1 Mei"))
        )
        val tool = SearchEverythingTool(sources)

        val result = tool.execute("""{"query":"sinta","scope":"memory"}""")

        assertTrue(result.success)
        assertTrue(result.output.contains("m1"))
        assertEquals(listOf("memory"), sources.calls)
    }

    @Test
    fun toolAllScopeMergesScopesAndRespectsLimit() = runBlocking {
        val sources = FakeSources(
            memoryHits = listOf(hit("memory", "m1", "sinta", "sinta istri pengguna")),
            chatHits = listOf(hit("chat", "c1", "percakapan", "bicara soal sinta tadi")),
            taskHits = listOf(hit("tasks", "t1", "sub-agent: riset", "cari kado untuk sinta")),
            fileHits = listOf(hit("files", "notes/sinta.md", "notes/sinta.md", "catatan sinta")),
            toolHits = listOf(hit("tools", "search", "search", "mencari di semua sumber"))
        )
        val tool = SearchEverythingTool(sources)

        val result = tool.execute("""{"query":"sinta","limit":3}""")

        assertTrue(result.success)
        // Every scope was consulted...
        assertEquals(setOf("memory", "chat", "tasks", "files", "tools"), sources.calls.toSet())
        // ...but the answer stays small.
        assertEquals("3", result.metadata["hits"])
    }

    @Test
    fun toolReportsEmptyResultHonestly() = runBlocking {
        val tool = SearchEverythingTool(FakeSources())

        val result = tool.execute("""{"query":"tidak ada apa-apa"}""")

        assertTrue(result.success)
        assertTrue(result.output.contains("Tidak ada hasil"))
        assertEquals("0", result.metadata["hits"])
    }
}
