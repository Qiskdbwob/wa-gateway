package com.example.agent.loop

import com.example.agent.model.Agent
import com.example.agent.model.AgentChannelAdapter
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentResponse
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.provider.EchoTestProvider
import com.example.agent.provider.ModelProvider
import com.example.agent.router.ModelRouter
import com.example.agent.router.ModelTarget
import com.example.agent.router.RetryPolicy
import com.example.agent.storage.AgentSessionRepository
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

class AgentLoop(
    var agent: Agent = Agent(),
    modelProvider: ModelProvider = EchoTestProvider(),
    private val sessionRepository: AgentSessionRepository,
    var modelRouter: ModelRouter? = null,
    var retryPolicy: RetryPolicy = RetryPolicy()
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
     * @param onProgress optional callback used to surface long running work (retries,
     *   fallbacks and, later on, tool calls) to the user. It is scoped to this single
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
                        log(
                            "MODEL_REQUEST",
                            "target=${target.id}, provider=${target.provider.name}, model=$requestModel, attempt=$modelAttempt, totalAttempts=$totalAttemptsUsed, contextCount=${activeHistory.size}"
                        )

                        val modelRequest = ModelRequest(
                            messages = activeHistory,
                            systemPrompt = agent.systemPrompt,
                            modelId = requestModel
                        )

                        val callStart = System.currentTimeMillis()
                        val modelResult = target.provider.generate(modelRequest)
                        val latencyMs = System.currentTimeMillis() - callStart

                        if (modelResult.isSuccess) {
                            val modelResponse = modelResult.getOrThrow()

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

