package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolDefinition
import com.example.agent.model.ToolPermission
import com.example.agent.model.toDefinition
import com.example.agent.workspace.Workspace
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 6 — Tool Registry.
 *
 * The Agent Loop never references a concrete tool: it looks tools up here by name. Adding a
 * tool therefore means registering it, not editing the loop.
 *
 * Only tools that may run without manual approval (SAFE / AUTO_SAFE) are advertised to
 * the model. CONFIRM tools stay invisible until the approval coordinator is attached —
 * that is what keeps the permission boundary from being decoration.
 */
class ToolRegistry(initialTools: List<Tool> = emptyList()) {

    private val tools = ConcurrentHashMap<String, Tool>()

    /**
     * Priority 3 — when an approval coordinator is attached, CONFIRM-class (destructive)
     * tools are also advertised: a pending call is parked behind the approval gate
     * instead of running. With approval disabled they stay invisible — a tool nobody can
     * approve must not be offered to the model.
     */
    @Volatile
    var approvalEnabled: Boolean = false

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

    /** Tools that may run without manual approval (SAFE + AUTO_SAFE). */
    fun safeTools(): List<Tool> = all().filter { it.permission != ToolPermission.CONFIRM }

    /** Tool definitions offered to the model. */
    fun definitions(): List<ToolDefinition> =
        all()
            .filter { approvalEnabled || it.permission != ToolPermission.CONFIRM }
            .map { it.toDefinition() }

    companion object {
        /**
         * Registry with the tools shipped by the app itself.
         *
         * The file tools are registered only when a [workspace] is given: without the Phase 7
         * sandbox there is nothing that may touch files, so the tool must not exist at all
         * instead of failing at call time.
         */
        fun withBuiltIns(workspace: Workspace? = null): ToolRegistry {
            val tools = mutableListOf<Tool>(CurrentTimeTool())
            if (workspace != null) {
                tools += workspaceFileTools(workspace)
            }
            return ToolRegistry(tools)
        }
    }
}
