package com.example.agent.loop

import com.example.agent.model.Agent
import com.example.agent.model.AgentChannelAdapter
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentResponse
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.model.ToolResult
import com.example.agent.provider.EchoTestProvider
import com.example.agent.provider.ModelProvider
import com.example.agent.router.ModelRouter
import com.example.agent.router.ModelTarget
import com.example.agent.router.RetryPolicy
import com.example.agent.storage.AgentSessionRepository
import com.example.agent.tool.ApprovalDecision
import com.example.agent.tool.ApprovalRequest
import com.example.agent.tool.AutoApproveAll
import com.example.agent.tool.ContextAwareTool
import com.example.agent.tool.ToolApproval
import com.example.agent.tool.ToolExecutionContext
import com.example.agent.tool.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AgentState {
    IDLE,
    THINKING,
    CALLING_TOOL,
    WAITING_TOOL,
    DELEGATING,
    WAITING_SUB_AGENT,
    REFLECTING,
    RETRYING,
    FALLBACK,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED
}

/**
 * True while the agent is still working on the current turn. The UI uses it to keep the
 * composer disabled and to show the matching activity bubble while a tool runs.
 */
val AgentState.isBusy: Boolean
    get() = when (this) {
        AgentState.THINKING,
        AgentState.CALLING_TOOL,
        AgentState.WAITING_TOOL,
        AgentState.RETRYING,
        AgentState.FALLBACK,
        AgentState.DELEGATING,
        AgentState.WAITING_SUB_AGENT,
        AgentState.REFLECTING,
        AgentState.WAITING_APPROVAL -> true

        AgentState.IDLE,
        AgentState.COMPLETED,
        AgentState.FAILED -> false
    }

/**
 * Backstop for tool output forwarded back to the model. Individual tools (e.g. `read_file`) cut
 * their own output earlier and say so, which the model understands better than a silent clip here.
 */
private const val TOOL_OUTPUT_LIMIT = 20_000

class AgentLoop(
    var agent: Agent = Agent(),
    modelProvider: ModelProvider = EchoTestProvider(),
    private val sessionRepository: AgentSessionRepository,
    var modelRouter: ModelRouter? = null,
    var retryPolicy: RetryPolicy = RetryPolicy(),
    /**
     * Phase 6 — tools the loop may look up by name. A null or empty registry simply means the
     * model is asked to answer without tools; the loop never references a concrete tool.
     */
    var toolRegistry: ToolRegistry? = null,
    /**
     * Phase 9 — approval layer for tools whose permission is CONFIRM. Defaults to
     * [AutoApproveAll] so the loop never blocks when no interactive surface is wired up;
     * the WhatsApp bridge can swap in an [ApprovalGate] to require human confirmation.
     */
    var approval: ToolApproval = AutoApproveAll
) {
    var modelProvider: ModelProvider = modelProvider
        set(value) {
            field = value
            modelRouter?.addTarget(ModelTarget("primary", value, agent.modelId, priority = 0, enabled = true))
        }

    init {
        if (modelRouter == null) {
            modelRouter = ModelRouter(
                initialTargets = listOf(
                    ModelTarget("primary", modelProvider, agent.modelId, priority = 0, enabled = true)
                ),
                retryPolicy = retryPolicy
            )
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<String>>(emptyList())
    val activityLogs: StateFlow<List<String>> = _activityLogs.asStateFlow()

    /** Human readable tool activity of the running turn, e.g. "Menjalankan tool current_time...". */
    private val _currentActivity = MutableStateFlow<String?>(null)
    val currentActivity: StateFlow<String?> = _currentActivity.asStateFlow()

    // Per-conversation mutex to prevent race conditions across concurrent messages
    private val conversationMutexes = ConcurrentHashMap<String, Mutex>()

    // Registered adapters for output dispatching (e.g. WhatsApp, etc.)
    private val channelAdapters = ConcurrentHashMap<String, AgentChannelAdapter>()



    fun registerChannelAdapter(adapter: AgentChannelAdapter) {
        channelAdapters[adapter.channelName] = adapter
    }

    fun unregisterChannelAdapter(channelName: String) {
        channelAdapters.remove(channelName)
    }

    private fun getMutex(conversationId: String): Mutex {
        return conversationMutexes.computeIfAbsent(conversationId) { Mutex() }
    }

    fun log(tag: String, message: String) {
        val entry = "[${timeFormat.format(Date())}] [$tag] $message"
        _activityLogs.value = listOf(entry) + _activityLogs.value.take(99)
    }

    /**
     * Phase 4 Enhanced Agent Loop:
     * Incoming Message (AgentInput)
     *       ↓
     * Load Session
     *       ↓
     * Build Context (System Prompt + History + Current Msg)
     *       ↓
     * Model Router & Fallback Chain
     *       ↓
     * Call Model with Error Classification & Retry Policy
     *       ↓
     * Process Model Response (detect Empty Response & Context Overflow)
     *       ↓
     * Fallback to secondary model if primary fails
     *       ↓
     * Persist Response (Assistant Message in Session)
     *       ↓
     * Send Response (via Channel Adapter)
     *
     * When a Tool Registry is attached (Phase 6) the model may answer with tool calls instead
     * of text; the loop then runs those tools through the registry and asks the model again,
     * bounded by `agent.maxToolIterations`.
     *
     * @param onProgress optional callback used to surface long running work (retries,
     *   fallbacks and tool calls) to the user. It is scoped to this single
     *   call — never global state — so concurrent chats cannot cross-talk. For WhatsApp
     *   the bridge uses it to update the "sedang berpikir..." bubble.
     */
    suspend fun processInput(
        input: AgentInput,
        onProgress: ((String) -> Unit)? = null
    ): Result<AgentResponse> {
        if (!agent.enabled) {
            log("MESSAGE_RECEIVED", "Ignored message for conv='${input.conversationId}' because agent is disabled.")
            return Result.failure(IllegalStateException("Agent is currently disabled"))
        }

        if (input.content.isBlank()) {
            log("MESSAGE_RECEIVED", "Ignored empty/blank message for conv='${input.conversationId}'.")
            return Result.failure(IllegalArgumentException("Message content cannot be blank"))
        }

        // Per-session synchronization ensures concurrent messages for the same chat do not race
        val mutex = getMutex(input.conversationId)
        return mutex.withLock {
            _state.value = AgentState.THINKING
            _lastError.value = null

            // Scoped to this call so concurrent conversations never share a sink.
            val emitProgress: (String) -> Unit = { message ->
                try {
                    onProgress?.invoke(message)
                } catch (e: Exception) {
                    log("PROGRESS_ERROR", "Failed to emit progress update: ${e.message}")
                }
            }

            log(
                "MESSAGE_RECEIVED",
                "id=${input.messageId}, conv=${input.conversationId}, sender=${input.senderId}, channel=${input.channel}, preview=\"${input.content.take(40)}\""
            )

            try {
                // 1. Load or Create Session
                val session = sessionRepository.getOrCreateSession(input.conversationId, agent.id)
                log(
                    "SESSION_LOADED",
                    "sessionId=${session.sessionId}, conv=${session.conversationId}, msgCount=${session.messageCount}"
                )

                // 2. Persist Incoming User Message
                val userMsg = AgentMessage(
                    id = input.messageId,
                    sessionId = session.sessionId,
                    role = AgentRole.USER,
                    content = input.content,
                    timestamp = input.timestamp
                )
                sessionRepository.saveMessage(userMsg)
                log("MESSAGE_PERSISTED", "User message saved id=${userMsg.id}, role=USER")

                // 3. Build Context (System prompt + persistent session history)
                val initialHistory = sessionRepository.getMessages(session.sessionId, limit = 20)
                var activeHistory = initialHistory.toMutableList()

                // 4. Resolve Target Models from ModelRouter / Fallback Chain
                val configuredTargets = modelRouter?.getActiveTargets()?.ifEmpty { null }
                    ?: listOf(
                        ModelTarget(
                            id = "primary",
                            provider = modelProvider,
                            modelId = agent.modelId,
                            priority = 0,
                            enabled = true
                        )
                    )

                val effectiveRetryPolicy = modelRouter?.retryPolicy ?: retryPolicy

                // Phase 6 — tools come from the registry, never hardcoded here. Only SAFE tools
                // are advertised (see ToolRegistry.definitions()), so a tool that would need
                // manual approval is not offered while there is no approval layer yet.
                val availableTools = toolRegistry?.definitions().orEmpty()
                var advertiseTools = agent.toolsEnabled && availableTools.isNotEmpty()
                val maxToolIterations = agent.maxToolIterations.coerceIn(0, 10)
                if (advertiseTools) {
                    log(
                        "TOOLS_AVAILABLE",
                        "${availableTools.size} tool ditawarkan (${availableTools.joinToString { it.name }}), maxIterasi=$maxToolIterations"
                    )
                }

                var totalAttemptsUsed = 0
                val maxTotalBudget = effectiveRetryPolicy.maxTotalAttemptsBudget

                var lastFailureKind: ModelErrorKind? = null
                var lastErrorMessage: String? = null
                var lastFailedTarget: ModelTarget? = null
                var finalAgentResponse: AgentResponse? = null

                targetLoop@ for ((targetIndex, target) in configuredTargets.withIndex()) {
                    if (totalAttemptsUsed >= maxTotalBudget) {
                        log("AGENT_FAILURE", "Execution budget exceeded ($totalAttemptsUsed attempts). Terminating loop.")
                        break@targetLoop
                    }

                    if (targetIndex > 0) {
                        _state.value = AgentState.FALLBACK
                        val prevTarget = configuredTargets[targetIndex - 1]
                        log("FALLBACK_STARTED", "Target '${prevTarget.id}' failed. Switching to fallback target '${target.id}' (${target.provider.name})")
                        emitProgress("⇄ Model utama tidak merespons, beralih ke model cadangan (${target.modelId ?: agent.modelId})...")
                        log("FALLBACK_MODEL_SELECTED", "target=${target.id}, provider=${target.provider.name}, model=${target.modelId ?: agent.modelId}, priority=${target.priority}")
                    }

                    var modelAttempt = 0
                    while (modelAttempt < effectiveRetryPolicy.maxAttemptsPerModel && totalAttemptsUsed < maxTotalBudget) {
                        modelAttempt++
                        totalAttemptsUsed++

                        if (modelAttempt > 1) {
                            _state.value = AgentState.RETRYING
                            log(
                                "RETRY_STARTED",
                                "attempt=$modelAttempt/${effectiveRetryPolicy.maxAttemptsPerModel}, target=${target.id}, provider=${target.provider.name}, model=${target.modelId ?: agent.modelId}"
                            )
                            emitProgress("↻ Mencoba ulang (${modelAttempt}/${effectiveRetryPolicy.maxAttemptsPerModel})...")
                        }

                        val requestModel = target.modelId?.ifBlank { null } ?: agent.modelId
                        val requestTools = if (advertiseTools) availableTools else emptyList()
                        log(
                            "MODEL_REQUEST",
                            "target=${target.id}, provider=${target.provider.name}, model=$requestModel, attempt=$modelAttempt, totalAttempts=$totalAttemptsUsed, contextCount=${activeHistory.size}, tools=${requestTools.size}"
                        )

                        val modelRequest = ModelRequest(
                            messages = activeHistory,
                            systemPrompt = agent.systemPrompt,
                            modelId = requestModel,
                            tools = requestTools
                        )

                        val callStart = System.currentTimeMillis()
                        val modelResult = target.provider.generate(modelRequest)
                        val latencyMs = System.currentTimeMillis() - callStart

                        if (modelResult.isSuccess) {
                            var modelResponse = modelResult.getOrThrow()
                            var toolLoopError: Throwable? = null
                            var toolLoopLatencyMs = latencyMs
                            var toolBudgetExhausted = false
                            var toolIteration = 0

                            // Phase 6 — tool round trips. The model may answer with tool calls
                            // instead of text; each round appends the assistant tool-call turn plus
                            // one message per tool result and asks the model again. Bounded by
                            // agent.maxToolIterations so a model that keeps asking for tools can
                            // never spin forever.
                            while (modelResponse.toolCalls.isNotEmpty() && toolLoopError == null) {
                                val toolCalls = modelResponse.toolCalls
                                if (toolIteration >= maxToolIterations) {
                                    toolBudgetExhausted = true
                                    log(
                                        "TOOL_BUDGET_EXCEEDED",
                                        "Model masih meminta tool setelah $maxToolIterations iterasi (${toolCalls.joinToString { it.name }}). Iterasi dihentikan."
                                    )
                                    break
                                }
                                toolIteration++
                                _state.value = AgentState.CALLING_TOOL
                                log(
                                    "TOOL_CALLS_REQUESTED",
                                    "iteration=$toolIteration/$maxToolIterations, target=${target.id}, model=$requestModel, tools=${toolCalls.joinToString { it.name }}"
                                )

                                // The assistant turn that asked for the calls has to be part of the
                                // follow-up request, otherwise providers reject the tool results
                                // that answer it.
                                activeHistory = (activeHistory + AgentMessage(
                                    id = "tool-call-${input.messageId}-$toolIteration",
                                    sessionId = session.sessionId,
                                    role = AgentRole.ASSISTANT,
                                    content = modelResponse.content,
                                    timestamp = System.currentTimeMillis(),
                                    toolCalls = toolCalls
                                )).toMutableList()

                                for (call in toolCalls) {
                                    _state.value = AgentState.WAITING_TOOL
                                    _currentActivity.value = "Menjalankan tool ${call.name}..."
                                    emitProgress("🔧 Menggunakan tool: ${call.name}...")

                                    val tool = toolRegistry?.get(call.name)
                                    val toolStart = System.currentTimeMillis()
                                    val toolResult: ToolResult = if (tool == null) {
                                        log("TOOL_ERROR", "Tool '${call.name}' tidak terdaftar di Tool Registry.")
                                        ToolResult(
                                            success = false,
                                            output = "",
                                            error = "Tool '${call.name}' tidak tersedia pada agent ini."
                                        )
                                    } else {
                                        try {
                                            // Phase 9 — tools whose permission is CONFIRM must be
                                            // approved before they run. SAFE tools (the read-only
                                            // file/terminal tools) are never blocked here.
                                            if (!approval.isAllowed(tool.permission)) {
                                                val request = ApprovalRequest(
                                                    toolName = tool.name,
                                                    permission = tool.permission,
                                                    reason = toolPermissionReason(tool),
                                                    conversationId = input.conversationId
                                                )
                                                _state.value = AgentState.WAITING_APPROVAL
                                                emitProgress("⏳ Menunggu persetujuan untuk ${tool.name}...")
                                                log("TOOL_APPROVAL_REQUESTED", "tool=${tool.name}, permission=${tool.permission}")
                                                val decision = approval.requestApproval(request)
                                                _state.value = AgentState.CALLING_TOOL
                                                when (decision) {
                                                    ApprovalDecision.Approved -> {
                                                        log("TOOL_APPROVAL_GRANTED", "tool=${tool.name}")
                                                    }
                                                    ApprovalDecision.Denied -> {
                                                        log("TOOL_APPROVAL_DENIED", "tool=${tool.name}")
                                                        return@try ToolResult(
                                                            success = false,
                                                            output = "",
                                                            error = "Penggunaan tool '${tool.name}' ditolak."
                                                        )
                                                    }
                                                    ApprovalDecision.Unavailable -> {
                                                        log("TOOL_APPROVAL_UNAVAILABLE", "tool=${tool.name}, falling back to policy")
                                                        return@try ToolResult(
                                                            success = false,
                                                            output = "",
                                                            error = "Persetujuan tidak tersedia untuk ${tool.name}."
                                                        )
                                                    }
                                                }
                                            }
                                            val executionContext = ToolExecutionContext(
                                                agentId = agent.id,
                                                sessionId = session.sessionId,
                                                conversationId = input.conversationId,
                                                channel = input.channel,
                                                metadata = input.metadata
                                            )
                                            if (tool is ContextAwareTool) {
                                                tool.execute(call.arguments, executionContext)
                                            } else {
                                                tool.execute(call.arguments)
                                            }
                                        } catch (e: Exception) {
                                            log("TOOL_ERROR", "Tool '${call.name}' gagal dieksekusi: ${e.message}")
                                            result = ToolResult(
                                                success = false,
                                                output = "",
                                                error = "Tool '${call.name}' gagal: ${e.message}"
                                            )
                                        }
                                        result ?: ToolResult(success = false, output = "", error = "Tool result tidak tersedia.")
                                    }
                                    val toolLatencyMs = System.currentTimeMillis() - toolStart

                                    log(
                                        "TOOL_RESULT",
                                        "tool=${call.name}, success=${toolResult.success}, latency=${toolLatencyMs}ms, output=\"${toolResult.output.take(120)}\", error=${toolResult.error?.take(120) ?: "none"}"
                                    )
                                    emitProgress(
                                        if (toolResult.success) {
                                            "🛠️ ${call.name} selesai (${toolLatencyMs}ms). Menyusun jawaban..."
                                        } else {
                                            "🛠️ ${call.name} gagal: ${toolResult.error ?: "tanpa detail"}"
                                        }
                                    )

                                    activeHistory = (activeHistory + AgentMessage(
                                        id = UUID.randomUUID().toString(),
                                        sessionId = session.sessionId,
                                        role = AgentRole.TOOL,
                                        content = describeToolResult(call.name, toolResult),
                                        timestamp = System.currentTimeMillis(),
                                        toolCallId = call.id,
                                        toolName = call.name
                                    )).toMutableList()
                                }

                                _currentActivity.value = null
                                _state.value = AgentState.THINKING
                                log(
                                    "MODEL_REQUEST",
                                    "target=${target.id}, provider=${target.provider.name}, model=$requestModel, attempt=$modelAttempt, phase=tool-follow-up, toolIteration=$toolIteration, contextCount=${activeHistory.size}, tools=${if (advertiseTools) availableTools.size else 0}"
                                )

                                val followUpStart = System.currentTimeMillis()
                                val followUpResult = target.provider.generate(
                                    ModelRequest(
                                        messages = activeHistory,
                                        systemPrompt = agent.systemPrompt,
                                        modelId = requestModel,
                                        tools = if (advertiseTools) availableTools else emptyList()
                                    )
                                )
                                toolLoopLatencyMs = System.currentTimeMillis() - followUpStart

                                if (followUpResult.isFailure) {
                                    toolLoopError = followUpResult.exceptionOrNull()
                                        ?: Exception("Tool follow-up model request failed")
                                } else {
                                    modelResponse = followUpResult.getOrThrow()
                                }
                            }

                            _currentActivity.value = null

                            if (toolLoopError != null) {
                                // A failed follow-up call is still a model failure, so the Phase 4
                                // retry/fallback policy applies instead of the loop dying silently.
                                val toolError = toolLoopError ?: Exception("Tool follow-up model request failed")
                                val classification = ModelErrorClassifier.classify(toolError)
                                lastFailureKind = classification.kind
                                lastErrorMessage = toolError.message
                                lastFailedTarget = target

                                log(
                                    "MODEL_ERROR",
                                    "errorType=${classification.kind}, attempt=$modelAttempt, target=${target.id}, provider=${target.provider.name}, model=$requestModel, latency=${toolLoopLatencyMs}ms, phase=tool-follow-up, error=${toolError.message}"
                                )

                                if (effectiveRetryPolicy.enabled && classification.isRetryable &&
                                    modelAttempt < effectiveRetryPolicy.maxAttemptsPerModel &&
                                    totalAttemptsUsed < maxTotalBudget
                                ) {
                                    _state.value = AgentState.RETRYING
                                    val backoff = classification.retryAfterMs ?: (effectiveRetryPolicy.initialBackoffMs * modelAttempt)
                                    log("MODEL_RETRY", "target=${target.id}, attempt=${modelAttempt + 1}, backoff=${backoff}ms, phase=tool-follow-up")
                                    emitProgress("↻ Terjadi kendala saat memakai tool, mencoba ulang...")
                                    delay(backoff)
                                    continue // retry on same model
                                } else if (classification.canFallback) {
                                    _state.value = AgentState.FALLBACK
                                    log("FALLBACK_STARTED", "Target '${target.id}' failed on a tool turn (${classification.kind}). Advancing to fallback chain.")
                                    break // advance to next fallback target
                                } else {
                                    log("AGENT_FAILURE", "Target '${target.id}' failed on a tool turn with non-fallbackable error: ${classification.kind}")
                                    break@targetLoop
                                }
                            }

                            if (toolBudgetExhausted) {
                                lastFailureKind = ModelErrorKind.TOOL_ERROR
                                lastErrorMessage = "Agent berhenti karena terlalu banyak memakai tool untuk satu permintaan."
                                lastFailedTarget = target
                                log("AGENT_FAILURE", "Tool iteration budget exhausted on target '${target.id}'.")
                                break@targetLoop
                            }

                            // Check for empty response (Section 9)
                            if (modelResponse.content.isBlank()) {
                                lastFailureKind = ModelErrorKind.EMPTY_RESPONSE
                                lastErrorMessage = "Model returned empty response content"
                                lastFailedTarget = target

                                log(
                                    "MODEL_ERROR",
                                    "errorType=EMPTY_RESPONSE, attempt=$modelAttempt, target=${target.id}, provider=${target.provider.name}, model=$requestModel, latency=${latencyMs}ms"
                                )

                                if (effectiveRetryPolicy.enabled && modelAttempt < effectiveRetryPolicy.maxAttemptsPerModel && totalAttemptsUsed < maxTotalBudget) {
                                    _state.value = AgentState.RETRYING
                                    val backoff = effectiveRetryPolicy.initialBackoffMs * modelAttempt
                                    log("MODEL_RETRY", "target=${target.id}, attempt=${modelAttempt + 1}, backoff=${backoff}ms, reason=EMPTY_RESPONSE")
                                    emitProgress("↻ Jawaban kosong, mencoba ulang...")
                                    delay(backoff)
                                    continue // retry on same model
                                } else {
                                    _state.value = AgentState.FALLBACK
                                    log("FALLBACK_STARTED", "Empty response retries exhausted on target '${target.id}'. Advancing to fallback.")
                                    break // advance to next fallback target
                                }
                            }

                            // Model succeeded with non-empty content
                            if (modelAttempt > 1) {
                                log("RETRY_COMPLETED", "target=${target.id}, attempt=$modelAttempt")
                            }

                            finalAgentResponse = AgentResponse(
                                content = modelResponse.content,
                                finishReason = modelResponse.finishReason,
                                usage = modelResponse.usage,
                                model = modelResponse.model,
                                provider = modelResponse.provider,
                                metadata = modelResponse.metadata
                            )

                            log(
                                "MODEL_SUCCESS",
                                "target=${target.id}, provider=${finalAgentResponse.provider}, model=${finalAgentResponse.model}, length=${finalAgentResponse.content.length}, latency=${modelResponse.latencyMs}ms, tokens=${finalAgentResponse.usage?.totalTokens ?: 0}"
                            )
                            break@targetLoop

                        } else {
                            // Model call failed
                            val error = modelResult.exceptionOrNull() ?: Exception("Unknown model error")
                            val classification = ModelErrorClassifier.classify(error)
                            lastFailureKind = classification.kind
                            lastErrorMessage = error.message
                            lastFailedTarget = target

                            log(
                                "MODEL_ERROR",
                                "errorType=${classification.kind}, attempt=$modelAttempt, target=${target.id}, provider=${target.provider.name}, model=$requestModel, latency=${latencyMs}ms, error=${error.message}"
                            )

                            // Phase 6 — some OpenAI-compatible endpoints reject the "tools" field
                            // outright (HTTP 400). Retry the same model without tools (this consumes
                            // one retry attempt) instead of spending the whole fallback chain on a
                            // schema the endpoint cannot parse.
                            if (advertiseTools && classification.kind == ModelErrorKind.INVALID_REQUEST) {
                                advertiseTools = false
                                _state.value = AgentState.RETRYING
                                log("TOOL_SCHEMA_REJECTED", "target=${target.id}, provider=${target.provider.name}. Endpoint menolak skema tool; permintaan diulang tanpa tool.")
                                emitProgress("Model ini belum mendukung tool, mengulang tanpa tool...")
                                continue
                            }

                            // Context overflow mitigation (Section 8)
                            var canRetryOverflow = false
                            if (classification.kind == ModelErrorKind.CONTEXT_OVERFLOW) {
                                if (activeHistory.size > 2) {
                                    activeHistory = mutableListOf(activeHistory.first(), activeHistory.last())
                                    log("CONTEXT_OVERFLOW", "Context messages reduced to ${activeHistory.size} to mitigate overflow.")
                                    canRetryOverflow = true
                                }
                            }

                            // Determine if we should retry this model or move to fallback
                            if (effectiveRetryPolicy.enabled && (classification.isRetryable || canRetryOverflow) && modelAttempt < effectiveRetryPolicy.maxAttemptsPerModel && totalAttemptsUsed < maxTotalBudget) {
                                _state.value = AgentState.RETRYING
                                val backoff = classification.retryAfterMs ?: (effectiveRetryPolicy.initialBackoffMs * modelAttempt)
                                log("MODEL_RETRY", "target=${target.id}, attempt=${modelAttempt + 1}, backoff=${backoff}ms")
                                emitProgress("↻ Terjadi kendala (${classification.kind.name.lowercase()}), mencoba ulang...")
                                delay(backoff)
                                continue // retry on same model
                            } else {
                                if (classification.canFallback) {
                                    _state.value = AgentState.FALLBACK
                                    log("FALLBACK_STARTED", "Target '${target.id}' failed (${classification.kind}). Advancing to fallback chain.")
                                    break // advance to next target in fallback chain
                                } else {
                                    log("AGENT_FAILURE", "Target '${target.id}' encountered non-fallbackable error: ${classification.kind}")
                                    break@targetLoop // cannot fallback, stop loop
                                }
                            }
                        }
                    }
                }

                if (finalAgentResponse == null) {
                    _state.value = AgentState.FAILED
                    _lastError.value = lastErrorMessage
                    log("AGENT_ERROR", "Execution failed: $lastErrorMessage")
                    log(
                        "AGENT_FAILURE",
                        "All attempts failed. totalAttempts=$totalAttemptsUsed, lastTarget=${lastFailedTarget?.id}, lastErrorType=$lastFailureKind, error=$lastErrorMessage"
                    )
                    // Return failure with details
                    return@withLock Result.failure(
                        Exception(lastErrorMessage ?: "Agent tidak dapat memproses pesan saat ini. Silakan coba lagi nanti.")
                    )
                }

                // 6. Persist Assistant Response in Session
                val assistantMsg = AgentMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.sessionId,
                    role = AgentRole.ASSISTANT,
                    content = finalAgentResponse.content,
                    timestamp = System.currentTimeMillis()
                )
                sessionRepository.saveMessage(assistantMsg)
                log("MESSAGE_PERSISTED", "Assistant response saved id=${assistantMsg.id}, role=ASSISTANT")

                // 7. Send Response via Channel Adapter if registered
                val adapter = channelAdapters[input.channel]
                if (adapter != null) {
                    val sendResult = adapter.sendResponse(input, finalAgentResponse)
                    if (sendResult.isSuccess) {
                        log("RESPONSE_SENT", "channel=${input.channel}, target=${input.conversationId}")
                    } else {
                        val sendErr = sendResult.exceptionOrNull()
                        log("AGENT_ERROR", "Failed sending response via channel '${input.channel}': ${sendErr?.message}")
                    }
                } else {
                    log("RESPONSE_SENT", "channel=${input.channel} (persisted in session, no external adapter)")
                }

                _state.value = AgentState.COMPLETED
                log("AGENT_COMPLETED", "Response delivered for conv=${input.conversationId}")
                Result.success(finalAgentResponse)

            } catch (e: Exception) {
                _state.value = AgentState.FAILED
                _lastError.value = e.message
                log("AGENT_FAILURE", "Agent loop unhandled exception: ${e.message}")
                Result.failure(Exception("Agent tidak dapat memproses pesan saat ini. Silakan coba lagi nanti."))
            } finally {
                if (_state.value == AgentState.COMPLETED) {
                    _state.value = AgentState.IDLE
                }
            }
        }
    }

    /**
     * Formats a [ToolResult] into the text that a `tool` role message carries. Plain text (not
     * JSON) so every OpenAI-compatible endpoint can read it, and truncated so one chatty tool
     * cannot flood the context window.
     */
    /** Human-readable reason surfaced to the user when a CONFIRM tool awaits approval. */
    private fun toolPermissionReason(tool: com.example.agent.model.Tool): String = when (tool.permission) {
        com.example.agent.model.ToolPermission.SAFE -> "aman (read-only)"
        com.example.agent.model.ToolPermission.CONFIRM -> "membutuhkan konfirmasi: aksi yang dapat mengubah state"
    }

    private fun describeToolResult(toolName: String, result: ToolResult): String {
        val status = if (result.success) "sukses" else "gagal"
        val body = result.output.ifBlank { result.error ?: "(tanpa keluaran)" }
        val truncated = if (body.length > TOOL_OUTPUT_LIMIT) {
            body.take(TOOL_OUTPUT_LIMIT) + "\n...(dipotong)"
        } else {
            body
        }
        return "Hasil tool $toolName ($status):\n$truncated"
    }

    /**
     * Backward-compatible helper method for simple text interactions
     */
    suspend fun processMessage(conversationId: String, userText: String): Result<String> {
        val input = AgentInput(
            conversationId = conversationId,
            senderId = conversationId,
            content = userText,
            channel = "internal_chat"
        )
        return processInput(input).map { it.content }
    }
}

