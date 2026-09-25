package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.search.SearchHit
import com.example.agent.search.SearchRanker
import com.example.agent.search.SearchScope
import com.example.agent.search.SearchSources

/**
 * Unified search — one tool that looks in every local store the agent already owns: chat history,
 * long-term memory, scheduled/sub-agent tasks, workspace files and its own tool registry.
 *
 * Why this exists: the agent used to need several calls (recall_memory, read_file, inspecting the
 * task list) just to find out whether it already knows something. Fewer round-trips means the
 * iteration budget is spent on the actual task, and the hints returned here (memory id, workspace
 * path, tool name) lead straight to the tool that can act on the hit.
 *
 * Read-only, no network, every list capped → AUTO_SAFE like the other read tools.
 */
class SearchEverythingTool(
    private val sources: SearchSources,
    private val defaultLimit: Int = 8
) : Tool {

    override val id: String = "builtin.search"
    override val name: String = "search"
    override val description: String =
        "Mencari sekaligus di riwayat chat (chat), memori jangka panjang (memory), task terjadwal & sub-agent (tasks), file workspace (files), dan daftar tool (tools). " +
            "Gunakan sebelum bertanya ulang ke pengguna atau sebelum mengerjakan tugas yang mungkin sudah pernah dikerjakan."

    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Kata kunci atau frasa yang dicari." },
            "scope": { "type": "string", "description": "all | memory | chat | tasks | files | tools (default all)." },
            "limit": { "type": "integer", "description": "Jumlah hasil maksimum (1-25, default 8)." }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val query = JsonArgs.string(input, "query")?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Argumen `query` wajib diisi, contoh: {\"query\":\"nama istri\",\"scope\":\"memory\"}"
            )
        }
        if (SearchRanker.tokenize(query).isEmpty()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Kata kunci terlalu pendek; pakai minimal dua huruf."
            )
        }

        val scope = SearchScope.normalize(JsonArgs.string(input, "scope"))
        val limit = (JsonArgs.int(input, "limit") ?: defaultLimit).coerceIn(1, MAX_LIMIT)

        val hits = if (scope == SearchScope.ALL) {
            searchAll(query, limit)
        } else {
            searchOne(scope, query, limit)
        }

        if (hits.isEmpty()) {
            return ToolResult(
                success = true,
                output = "Tidak ada hasil untuk \"$query\" (scope=$scope). Coba kata kunci lain atau scope lain.",
                metadata = mapOf("hits" to "0", "scope" to scope)
            )
        }

        val body = hits.mapIndexed { index, hit -> render(index + 1, hit) }.joinToString("\n")
        return ToolResult(
            success = true,
            output = buildString {
                append("Hasil pencarian \"").append(query).append("\" (scope=").append(scope)
                append(", ").append(hits.size).append(" hit):\n")
                append(body)
                append("\n\nTindak lanjuti dengan tool yang sesuai: recall_memory (memory), ")
                append("read_file / list_files (files), get/lihat tab Tugas (tasks), atau panggil tool-nya langsung (tools).")
            },
            metadata = mapOf(
                "hits" to hits.size.toString(),
                "scope" to scope,
                "scopes" to hits.map { it.scope }.distinct().joinToString(",")
            )
        )
    }

    private suspend fun searchOne(scope: String, query: String, limit: Int): List<SearchHit> = when (scope) {
        SearchScope.MEMORY -> sources.memory(query, limit)
        SearchScope.CHAT -> sources.chat(query, limit)
        SearchScope.TASKS -> sources.tasks(query, limit)
        SearchScope.FILES -> sources.files(query, limit)
        SearchScope.TOOLS -> sources.tools(query, limit)
        else -> emptyList()
    }

    /**
     * `all` fans out with a per-scope slice (so a memory-heavy agent cannot crowd out the file that
     * actually answers the question) and then re-ranks the union.
     */
    private suspend fun searchAll(query: String, limit: Int): List<SearchHit> {
        val perScope = (limit / SearchScope.all.size).coerceAtLeast(MIN_PER_SCOPE)
        val merged = mutableListOf<SearchHit>()
        merged += sources.memory(query, perScope)
        merged += sources.chat(query, perScope)
        merged += sources.tasks(query, perScope)
        merged += sources.files(query, perScope)
        merged += sources.tools(query, perScope)
        return SearchRanker.rank(query, merged, limit)
    }

    private fun render(index: Int, hit: SearchHit): String = buildString {
        append(index).append(". [").append(hit.scope).append("] ").append(hit.title.replace('\n', ' '))
        append("\n   id: ").append(hit.id)
        val snippet = hit.snippet.replace('\n', ' ').trim().take(SNIPPET_CHARS)
        if (snippet.isNotEmpty()) append("\n   ").append(snippet)
    }

    private companion object {
        const val MAX_LIMIT = 25
        const val MIN_PER_SCOPE = 2
        const val SNIPPET_CHARS = 240
    }
}
