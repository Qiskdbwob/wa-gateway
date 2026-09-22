package com.example.agent.router

import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.provider.ModelProvider

data class ModelTarget(
    val id: String,
    val provider: ModelProvider,
    val modelId: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true
)

data class RetryPolicy(
    val maxAttemptsPerModel: Int = 2,
    val initialBackoffMs: Long = 100L,
    val backoffMultiplier: Double = 1.5,
    val maxTotalAttemptsBudget: Int = 5,
    val enabled: Boolean = true
)

data class ModelProbeResult(
    val targetId: String,
    val available: Boolean,
    val latencyMs: Long,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class ModelRouter(
    initialTargets: List<ModelTarget> = emptyList(),
    var retryPolicy: RetryPolicy = RetryPolicy()
) {
    private val _targets = mutableListOf<ModelTarget>()

    init {
        _targets.addAll(initialTargets)
    }

    fun setTargets(targets: List<ModelTarget>) {
        synchronized(_targets) {
            _targets.clear()
            _targets.addAll(targets)
        }
    }

    fun addTarget(target: ModelTarget) {
        synchronized(_targets) {
            _targets.removeAll { it.id == target.id }
            _targets.add(target)
        }
    }

    fun removeTarget(targetId: String) {
        synchronized(_targets) {
            _targets.removeAll { it.id == targetId }
        }
    }

    fun getActiveTargets(): List<ModelTarget> {
        synchronized(_targets) {
            return _targets
                .filter { it.enabled }
                .sortedBy { it.priority }
        }
    }

    /**
     * Model Probe (Phase 4):
     * Simple "1 + 1 =" probe to verify model availability and measure latency.
     * IMPORTANT: This probe only measures availability/latency, NOT model quality.
     */
    suspend fun probeModel(target: ModelTarget): ModelProbeResult {
        val startTime = System.currentTimeMillis()
        val probeRequest = ModelRequest(
            messages = listOf(
                AgentMessage(sessionId = "probe-session", role = AgentRole.USER, content = "1 + 1 =")
            ),
            modelId = target.modelId
        )
        return try {
            val res = target.provider.generate(probeRequest)
            val latency = System.currentTimeMillis() - startTime
            if (res.isSuccess) {
                ModelProbeResult(
                    targetId = target.id,
                    available = true,
                    latencyMs = latency,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                val err = res.exceptionOrNull()?.message ?: "Probe generation failed"
                ModelProbeResult(
                    targetId = target.id,
                    available = false,
                    latencyMs = latency,
                    error = err,
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ModelProbeResult(
                targetId = target.id,
                available = false,
                latencyMs = latency,
                error = e.message,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
