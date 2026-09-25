package com.example.agent.search

import com.example.agent.storage.db.AgentDatabase
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.tool.ToolRegistry
import com.example.agent.workspace.Workspace
import java.io.File

/**
 * Real [SearchSources] backed by Room + the agent workspace.
 *
 * Everything here is local and read-only: no network, nothing is mutated, and every list is
 * capped, so the tool can be advertised to the model without an approval step. Files are scanned
 * by name always and by *content* only when they are small text files — a workspace full of
 * binaries or a giant log must not turn one search into a device freeze.
 */
class LocalSearchSources(
    private val database: AgentDatabase,
    private val toolRegistry: () -> ToolRegistry,
    private val workspace: Workspace,
    private val maxFilesScanned: Int = 400,
    private val maxFileBytesRead: Long = 64 * 1024
) : SearchSources {

    override suspend fun memory(term: String, limit: Int): List<SearchHit> =
        database.memoryItemDao().searchItems(term, limit).map { item ->
            SearchHit(
                scope = SearchScope.MEMORY,
                id = item.id,
                title = "${item.type.lowercase()}${statusSuffix(item)}: ${item.content.take(80)}",
                snippet = item.content,
                timestamp = item.updatedAt
            )
        }

    override suspend fun chat(term: String, limit: Int): List<SearchHit> =
        database.messageDao().searchMessages(term, limit).map { message ->
            SearchHit(
                scope = SearchScope.CHAT,
                id = message.sessionId,
                title = "${message.role.lowercase()} · ${message.sessionId}",
                snippet = message.content,
                timestamp = message.timestamp
            )
        }

    override suspend fun tasks(term: String, limit: Int): List<SearchHit> {
        val scheduled = database.scheduledTaskDao().getAll().map { task ->
            SearchHit(
                scope = SearchScope.TASKS,
                id = task.id,
                title = "terjadwal: ${task.name}",
                snippet = "${task.schedule} · ${if (task.enabled) "aktif" else "pause"} · ${task.prompt}",
                timestamp = task.createdAt
            )
        }
        val subAgents = database.agentTaskDao().getRecent(limit * 4).map { task ->
            SearchHit(
                scope = SearchScope.TASKS,
                id = task.id,
                title = "sub-agent: ${task.name} (${task.status})",
                snippet = task.result ?: task.task,
                timestamp = task.createdAt
            )
        }
        return SearchRanker.rank(term, scheduled + subAgents, limit)
    }

    override suspend fun files(term: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        if (SearchRanker.tokenize(term).isEmpty()) return hits

        var scanned = 0
        val queue = ArrayDeque<File>().apply { add(workspace.root) }
        while (queue.isNotEmpty() && scanned < maxFilesScanned && hits.size < limit * 4) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    queue.add(child)
                    continue
                }
                scanned++
                if (scanned > maxFilesScanned) break
                val relative = workspace.relativePath(child)
                val body = if (child.length() in 1..maxFileBytesRead && looksTextual(child)) {
                    runCatching { child.readText() }.getOrDefault("")
                } else {
                    ""
                }
                val hit = SearchHit(
                    scope = SearchScope.FILES,
                    id = relative,
                    title = relative,
                    snippet = body.take(400).ifBlank { "${child.length()} bytes" },
                    timestamp = child.lastModified()
                )
                if (SearchRanker.score(term, hit.title, hit.snippet) > 0) hits.add(hit)
            }
        }
        return SearchRanker.rank(term, hits, limit)
    }

    override fun tools(term: String, limit: Int): List<SearchHit> =
        SearchRanker.rank(
            term,
            toolRegistry().all().map { tool ->
                SearchHit(
                    scope = SearchScope.TOOLS,
                    id = tool.name,
                    title = tool.name,
                    snippet = tool.description,
                )
            },
            limit
        )

    private fun statusSuffix(item: MemoryItemEntity): String =
        if (item.status == MemoryItemEntity.STATUS_ACTIVE) "" else " (${item.status})"

    private fun looksTextual(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in TEXT_EXTENSIONS
    }

    private companion object {
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "csv", "log", "yml", "yaml", "xml", "html", "htm",
            "kt", "java", "py", "js", "ts", "tsx", "sh", "gradle", "properties", "ini", "toml"
        )
    }
}
