package com.example.agent.search

/** One search hit across every local store the agent owns. */
data class SearchHit(
    /** memory / chat / tasks / files / tools */
    val scope: String,
    /** Identifier the agent can act on (memory id, tool name, workspace path, task id). */
    val id: String,
    val title: String,
    val snippet: String,
    val timestamp: Long = 0L
)

/** The scopes the `search` tool may look in. */
object SearchScope {
    const val ALL = "all"
    const val MEMORY = "memory"
    const val CHAT = "chat"
    const val TASKS = "tasks"
    const val FILES = "files"
    const val TOOLS = "tools"

    val all = listOf(MEMORY, CHAT, TASKS, FILES, TOOLS)

    /** Unknown/blank scope means "all" so a sloppy model argument still returns something useful. */
    fun normalize(raw: String?): String {
        val cleaned = raw?.trim()?.lowercase().orEmpty()
        return if (cleaned in all) cleaned else ALL
    }
}

/**
 * Where search hits come from. The bridge supplies a Room + workspace backed implementation;
 * unit tests can supply a fake, which keeps the tool itself free of Android dependencies.
 */
interface SearchSources {
    suspend fun memory(term: String, limit: Int): List<SearchHit> = emptyList()
    suspend fun chat(term: String, limit: Int): List<SearchHit> = emptyList()
    suspend fun tasks(term: String, limit: Int): List<SearchHit> = emptyList()
    suspend fun files(term: String, limit: Int): List<SearchHit> = emptyList()
    fun tools(term: String, limit: Int): List<SearchHit> = emptyList()
}

/**
 * Lexical scorer — same philosophy as the memory retriever: no embeddings, no network, and the
 * ranking is explainable. A phrase hit in the title counts most, then a phrase hit in the body,
 * then individual token hits, so "nama istri" prefers a memory titled with that phrase over one
 * that merely contains both words somewhere.
 *
 * Queries shorter than two characters score nothing on purpose: single letters match everything
 * and would only flood the model's context.
 */
object SearchRanker {

    fun tokenize(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()

    fun score(query: String, title: String, snippet: String): Int {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return 0

        val phrase = query.trim().lowercase()
        val titleLower = title.lowercase()
        val bodyLower = snippet.lowercase()

        var score = 0
        if (phrase.isNotEmpty()) {
            if (titleLower.contains(phrase)) score += 6
            if (bodyLower.contains(phrase)) score += 4
        }
        for (token in tokens) {
            if (titleLower.contains(token)) score += 2
            if (bodyLower.contains(token)) score += 1
        }
        return score
    }

    /** Best match first; equal scores fall back to the newest hit. Non-matches are dropped. */
    fun rank(query: String, hits: List<SearchHit>, limit: Int): List<SearchHit> =
        hits
            .mapNotNull { hit ->
                val score = score(query, hit.title, hit.snippet)
                if (score == 0) null else hit to score
            }
            .sortedWith(
                compareByDescending<Pair<SearchHit, Int>> { it.second }
                    .thenByDescending { it.first.timestamp }
            )
            .take(limit.coerceAtLeast(1))
            .map { it.first }
}
