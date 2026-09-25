package com.example.agent.subagent

import com.example.agent.model.Agent
import com.example.agent.storage.dao.AgentTaskDao
import com.example.agent.storage.entity.AgentTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Priority 6 — subagent specification the main agent fills when delegating.
 * Mirrors Phase 14 of DOC/context-2.md (persona, model, tools, timeout) while keeping
 * only what the current runtime can honour.
 */
data class SubAgentSpec(
    val name: String = "SubAgent",
    val task: String,
    /** Persona/system prompt for the subagent. Empty = a neutral researcher persona. */
    val persona: String = "",
    /** Model id for the subagent. Empty = the main model. */
    val modelId: String = "",
    val toolsEnabled: Boolean = false,
    val timeoutMs: Long = 5 * 60 * 1000L
)

data class TaskResult(
    val taskId: String,
    val status: String,
    val summary: String,
    val durationMs: Long,
    val error: String? = null
)

/**
 * Runs subagents in the background on their own coroutines.
 *
 * Key behaviour (from the user's requirement): the MAIN agent answers the user
 * immediately after delegating — it never waits for the subagent. When the subagent
 * finishes, [onTaskFinished] pushes the result into the requesting chat as a new agent
 * message.
 */
class SubAgentManager(
    private val taskDao: AgentTaskDao,
    /** Executes one subagent turn and returns the final text (implemented by the bridge). */
    private val executeTask: suspend (task: AgentTaskEntity) -> String,
    /** Delivers the finished result to the requesting chat (implemented by the bridge). */
    private val onTaskFinished: suspend (task: AgentTaskEntity, result: TaskResult) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts [spec] in the background and returns the created task id immediately.
     * The main agent sends this id (plus a short confirmation) to the user.
     */
    fun launchTask(
        spec: SubAgentSpec,
        conversationId: String,
        channel: String = "whatsapp",
        scheduledTaskId: String? = null
    ): AgentTaskEntity {
        val entity = AgentTaskEntity(
            id = "sub-" + UUID.randomUUID().toString().take(8),
            name = spec.name,
            task = spec.task,
            agentName = spec.name,
            modelId = spec.modelId.ifBlank { "(model utama)" },
            systemPrompt = spec.persona,
            toolsEnabled = spec.toolsEnabled,
            conversationId = conversationId,
            channel = channel,
            status = AgentTaskEntity.STATUS_QUEUED,
            parentConversationId = conversationId,
            scheduledTaskId = scheduledTaskId
        )

        scope.launch {
            taskDao.upsert(entity.copy(status = AgentTaskEntity.STATUS_RUNNING, startedAt = System.currentTimeMillis()))
            val startedAt = System.currentTimeMillis()
            try {
                val output = withContext(Dispatchers.IO) { executeTask(entity) }
                val finished = entity.copy(
                    status = AgentTaskEntity.STATUS_COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    result = output
                )
                taskDao.upsert(finished)
                onTaskFinished(
                    finished,
                    TaskResult(
                        taskId = entity.id,
                        status = AgentTaskEntity.STATUS_COMPLETED,
                        summary = output,
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                )
            } catch (e: Exception) {
                val failed = entity.copy(
                    status = AgentTaskEntity.STATUS_FAILED,
                    completedAt = System.currentTimeMillis(),
                    error = e.message ?: e.javaClass.simpleName
                )
                taskDao.upsert(failed)
                onTaskFinished(
                    failed,
                    TaskResult(
                        taskId = entity.id,
                        status = AgentTaskEntity.STATUS_FAILED,
                        summary = "",
                        durationMs = System.currentTimeMillis() - startedAt,
                        error = failed.error
                    )
                )
            }
        }

        return entity
    }

    suspend fun getRecent(limit: Int = 50): List<AgentTaskEntity> = taskDao.getRecent(limit)
    suspend fun get(id: String): AgentTaskEntity? = taskDao.findById(id)

    companion object {
        /** Default persona used when the main agent does not specify one. */
        const val DEFAULT_PERSONA: String =
            "Kamu adalah sub-agent peneliti yang fokus, objektif, dan ringkas. " +
                "Kerjakan tugas yang diberikan, jawab dengan fakta dari konteks yang tersedia, " +
                "dan sebutkan ketidakpastian bila ada."
    }
}
