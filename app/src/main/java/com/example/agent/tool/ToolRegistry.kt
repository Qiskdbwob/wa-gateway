package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolDefinition
import com.example.agent.model.ToolPermission
import com.example.agent.model.toDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 6 — Tool Registry.
 *
 * The Agent Loop never references a concrete tool: it looks tools up here by name. Adding a
 * tool therefore means registering it, not editing the loop.
 *
 * Only [ToolPermission.SAFE] tools are advertised to the model. Tools that need manual
 * approval stay invisible until the approval layer (Phase 9) can answer for them — that is
 * what keeps the permission boundary from being decoration.
 */
class ToolRegistry(initialTools: List<Tool> = emptyList()) {

    private val tools = ConcurrentHashMap<String, Tool>()

    init {
        initialTools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.sortedBy { it.name }

    fun isEmpty(): Boolean = tools.isEmpty()

    /** Tools that may run without manual approval. */
    fun safeTools(): List<Tool> = all().filter { it.permission == ToolPermission.SAFE }

    /** Tool definitions offered to the model. */
    fun definitions(): List<ToolDefinition> = safeTools().map { it.toDefinition() }

    companion object {
        /** Registry with the tools shipped by the app itself. */
        fun withBuiltIns(): ToolRegistry = ToolRegistry(listOf(CurrentTimeTool()))
    }
}
