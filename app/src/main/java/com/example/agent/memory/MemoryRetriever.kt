package com.example.agent.memory

import com.example.agent.storage.entity.MemoryItemEntity

/**
 * Priority 2 — lexical RAG retriever over the long-term memory table.
 *
 * Score = keyword overlap ratio (query tokens found in the item's stored keywords and
 * content) x a recency boost. No external embedding service is required, which keeps
 * retrieval fully on-device and free; the trade-off (no synonym matching) is acceptable
 * for a personal agent whose memories are written by its own summarizers.
 *
 * Pure JVM logic, unit-testable without Android.
 */
object MemoryRetriever {

    /** Common Indonesian + English stopwords that would otherwise dominate overlap. */
    private val STOPWORDS = setOf(
        "yang", "dan", "di", "ke", "dari", "untuk", "pada", "dengan", "adalah", "itu", "ini",
        "atau", "juga", "akan", "sudah", "belum", "tidak", "bukan", "saya", "kamu", "kita",
        "kami", "mereka", "dia", "ada", "oleh", "sebagai", "bila", "kalau", "agar", "saja",
        "the", "a", "an", "is", "are", "was", "were", "be", "to", "of", "and", "or", "in",
        "on", "for", "with", "at", "by", "it", "this", "that", "as", "i", "you", "we",
        "they", "he", "she", "do", "does", "did", "have", "has", "had", "not", "no", "yes",
        "apa", "siapa", "kenapa", "bagaimana", "kapan", "dimana", "gimana", "kok"
    )

    /** Lowercase letter/digit tokens minus stopwords, min length 2. */
    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()
    }

    fun extractKeywords(content: String, maxKeywords: Int = 24): String =
        tokenize(content).take(maxKeywords).joinToString(" ")

    /**
     * Overlap score in [0, 1]: fraction of query tokens present in the item text.
     * Returns 0 when the query has no usable tokens.
     */
    fun score(queryTokens: List<String>, item: MemoryItemEntity, nowMs: Long): Double {
        if (queryTokens.isEmpty()) return 0.0
        val haystackTokens = tokenize(item.content + " " + item.keywords).toSet()
        if (haystackTokens.isEmpty()) return 0.0
        var hits = 0
        for (token in queryTokens) {
            if (haystackTokens.contains(token)) hits++
        }
        val overlap = hits.toDouble() / queryTokens.size
        if (overlap <= 0.0) return 0.0

        // Recency boost: up to +25% for items touched in the last 3 days, decaying
        // linearly to zero at 30 days. Purely multiplicative on the overlap.
        val lastTouch = maxOf(item.updatedAt, item.lastUsedAt)
        val ageDays = ((nowMs - lastTouch).coerceAtLeast(0)) / 86_400_000.0
        val recency = when {
            ageDays <= 3.0 -> 1.25
            ageDays >= 30.0 -> 1.0
            else -> 1.25 - 0.25 * ((ageDays - 3.0) / 27.0)
        }
        return overlap * recency
    }

    /**
     * Ranks items for [query], returning the top [limit] with score above [minScore],
     * best first.
     */
    fun rank(
        items: List<MemoryItemEntity>,
        query: String,
        limit: Int,
        minScore: Double = 0.15,
        nowMs: Long = System.currentTimeMillis()
    ): List<Pair<MemoryItemEntity, Double>> {
        val queryTokens = tokenize(query)
        return items
            .map { it to score(queryTokens, it, nowMs) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(limit)
    }
}
