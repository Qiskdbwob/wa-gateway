package com.example.agent.bridge

import android.content.Context
import com.example.agent.loop.AgentLoop
import com.example.agent.model.Agent
import com.example.agent.model.AgentChannelAdapter
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentResponse
import com.example.agent.provider.EchoTestProvider
import com.example.agent.provider.ModelProvider
import com.example.agent.provider.OpenAiCompatibleProvider
import com.example.agent.provider.ProviderConfig
import com.example.agent.router.ModelProbeResult
import com.example.agent.router.ModelRouter
import com.example.agent.router.ModelTarget
import com.example.agent.router.RetryPolicy
import com.example.agent.storage.RoomAgentSessionRepository
import com.example.agent.storage.SecretCipher
import com.example.agent.storage.db.AgentDatabase
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.tool.ToolRegistry
import com.example.wagateway.OutgoingMessageSender
import com.example.wagateway.WaGatewayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Metadata key holding the WhatsApp message ID that should be edited with the answer. */
const val EDIT_TARGET_KEY = "whatsappEditTarget"

private const val THINKING_PLACEHOLDER = "⏳ Sedang berpikir..."

/** WhatsApp drops the typing state after a few seconds, so refresh it while working. */
private const val TYPING_REFRESH_MS = 8_000L

class WhatsAppChannelAdapter(
    private val gatewayManager: OutgoingMessageSender
) : AgentChannelAdapter {
    override val channelName: String = "whatsapp"

    override suspend fun sendResponse(input: AgentInput, response: AgentResponse): Result<Unit> {
        if (response.content.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot send empty response to WhatsApp"))
        }

        // Turn the "sedang berpikir..." placeholder into the real answer when possible, so
        // the chat keeps one bubble instead of two. Edits can fail (WhatsApp only accepts
        // them for a limited window), in which case we fall back to a normal send.
        val editTarget = input.metadata[EDIT_TARGET_KEY]
        if (!editTarget.isNullOrBlank()) {
            val edited = gatewayManager.editText(input.conversationId, editTarget, response.content)
            if (edited.isSuccess) {
                return Result.success(Unit)
            }
        }

        return gatewayManager.sendText(input.conversationId, response.content).map { }
    }
}

class WhatsAppAgentBridge private constructor(
    private val context: Context,
    private val gatewayManager: WaGatewayManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessionRepository: RoomAgentSessionRepository = RoomAgentSessionRepository(
        AgentDatabase.getInstance(context)
    )

    val providerConfig = MutableStateFlow(
        ProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            modelId = "gpt-4o-mini"
        )
    )

    val systemPrompt = MutableStateFlow(
        "You are an intelligent, polite, and helpful AI assistant responding via WhatsApp. Keep responses concise, natural, and formatted nicely for WhatsApp."
    )

    private val openAiProvider = OpenAiCompatibleProvider(
        configProvider = { providerConfig.value }
    )
    private val echoProvider = EchoTestProvider()

    val modelRouter = ModelRouter(
        initialTargets = listOf(
            ModelTarget(
                id = "openai-primary",
                provider = openAiProvider,
                modelId = "gpt-4o-mini",
                priority = 0,
                enabled = true
            ),
            ModelTarget(
                id = "echo-fallback",
                provider = echoProvider,
                modelId = "echo-model-v1",
                priority = 1,
                enabled = true
            )
        ),
        retryPolicy = RetryPolicy(
            maxAttemptsPerModel = 2,
            initialBackoffMs = 150L,
            maxTotalAttemptsBudget = 5,
            enabled = true
        )
    )

    /**
     * Phase 6 — tools the Agent Loop may call. Registering a tool here is the only step
     * needed to make it available to the model; the loop itself never references one.
     */
    val toolRegistry: ToolRegistry = ToolRegistry.withBuiltIns()

    val agentLoop = AgentLoop(
        agent = Agent(
            systemPrompt = systemPrompt.value,
            enabled = false
        ),
        modelProvider = openAiProvider,
        sessionRepository = sessionRepository,
        modelRouter = modelRouter,
        toolRegistry = toolRegistry
    )

    private val _isAutoReplyEnabled = MutableStateFlow(false)
    val isAutoReplyEnabled: StateFlow<Boolean> = _isAutoReplyEnabled.asStateFlow()

    private val _useEchoFallback = MutableStateFlow(true)
    val useEchoFallback: StateFlow<Boolean> = _useEchoFallback.asStateFlow()

    init {
        // Register WhatsApp outgoing channel adapter to the Agent Loop
        agentLoop.registerChannelAdapter(WhatsAppChannelAdapter(gatewayManager))
        updateModelRouter()

        // Load persisted configuration from Room on startup
        scope.launch {
            try {
                val saved = sessionRepository.getConfig()
                if (saved != null) {
                    _isAutoReplyEnabled.value = saved.isAutoReplyEnabled
                    _useEchoFallback.value = saved.useEchoFallback
                    providerConfig.value = ProviderConfig(
                        baseUrl = saved.baseUrl,
                        apiKey = SecretCipher.decrypt(saved.apiKey),
                        modelId = saved.modelId
                    )
                    systemPrompt.value = saved.systemPrompt
                    agentLoop.agent = agentLoop.agent.copy(
                        enabled = saved.isAutoReplyEnabled,
                        systemPrompt = saved.systemPrompt,
                        modelId = saved.modelId
                    )
                    updateModelRouter()
                }
            } catch (_: Exception) {
                // Ignore initialization error and continue with defaults
            }
        }

        // Register listener to gateway incoming messages
        gatewayManager.addMessageListener { sender, chat, isGroup, text, messageId, timestamp ->
            handleIncomingMessage(sender, chat, isGroup, text, messageId, timestamp)
        }
    }

    private fun updateModelRouter() {
        val targets = mutableListOf<ModelTarget>()
        targets.add(
            ModelTarget(
                id = "openai-primary",
                provider = openAiProvider,
                modelId = providerConfig.value.modelId,
                priority = 0,
                enabled = true
            )
        )
        if (_useEchoFallback.value) {
            targets.add(
                ModelTarget(
                    id = "echo-fallback",
                    provider = echoProvider,
                    modelId = "echo-model-v1",
                    priority = 1,
                    enabled = true
                )
            )
        }
        modelRouter.setTargets(targets)
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        _isAutoReplyEnabled.value = enabled
        agentLoop.agent = agentLoop.agent.copy(enabled = enabled)
        persistConfig()
    }

    fun setUseEchoFallback(useEcho: Boolean) {
        _useEchoFallback.value = useEcho
        updateModelRouter()
        persistConfig()
    }

    fun updateConfig(baseUrl: String, apiKey: String, modelId: String, prompt: String) {
        providerConfig.value = ProviderConfig(
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            modelId = modelId.trim()
        )
        systemPrompt.value = prompt.trim()
        agentLoop.agent = agentLoop.agent.copy(
            systemPrompt = prompt.trim(),
            modelId = modelId.trim()
        )
        updateModelRouter()
        persistConfig()
    }

    suspend fun probeCurrentModel(): ModelProbeResult {
        val target = ModelTarget(
            id = "openai-primary",
            provider = openAiProvider,
            modelId = providerConfig.value.modelId
        )
        return modelRouter.probeModel(target)
    }

    private fun persistConfig() {
        scope.launch {
            try {
                sessionRepository.saveConfig(
                    AgentConfigEntity(
                        isAutoReplyEnabled = _isAutoReplyEnabled.value,
                        useEchoFallback = _useEchoFallback.value,
                        baseUrl = providerConfig.value.baseUrl,
                        apiKey = SecretCipher.encrypt(providerConfig.value.apiKey),
                        modelId = providerConfig.value.modelId,
                        systemPrompt = systemPrompt.value,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // Ignore storage error
            }
        }
    }

    private fun handleIncomingMessage(
        sender: String,
        chat: String,
        isGroup: Boolean,
        text: String,
        messageId: String,
        timestamp: Long
    ) {
        if (!_isAutoReplyEnabled.value) return
        if (text.isBlank() || sender.isBlank()) return

        // Group auto-reply is intentionally out of scope for now: the agent must not
        // post into a group conversation without explicit configuration.
        if (isGroup) {
            agentLoop.log(
                "MESSAGE_IGNORED",
                "Pesan grup dari '$chat' diabaikan (auto-reply hanya untuk chat pribadi)."
            )
            return
        }

        // Reply target must be the conversation JID, not the (possibly device specific)
        // sender JID, so the answer lands in the right chat.
        val conversationId = chat.ifBlank { sender }

        scope.launch {
            updateModelRouter()

            var input = AgentInput(
                conversationId = conversationId,
                senderId = sender,
                content = text,
                timestamp = if (timestamp > 0) timestamp * 1000L else System.currentTimeMillis(),
                channel = "whatsapp",
                metadata = mapOf("source" to "whatsmeow", "waMessageId" to messageId)
            )

            // Send an instant acknowledgement so the user is never left staring at a
            // silent chat while a reasoning model (or a fallback chain) works.
            val ackId = gatewayManager.sendText(conversationId, THINKING_PLACEHOLDER).getOrNull()
            if (!ackId.isNullOrBlank()) {
                input = input.copy(metadata = input.metadata + mapOf(EDIT_TARGET_KEY to ackId))
            }

            // Typing indicator is refreshed while the loop works and cleared afterwards.
            gatewayManager.setTyping(conversationId, true)
            val typingKeepAlive = scope.launch {
                while (isActive) {
                    delay(TYPING_REFRESH_MS)
                    gatewayManager.setTyping(conversationId, true)
                }
            }

            try {
                val result = agentLoop.processInput(input) { progress ->
                    // Only edit a bubble that actually exists, and never let a failed edit
                    // of a progress note break the reply itself.
                    if (!ackId.isNullOrBlank()) {
                        scope.launch { gatewayManager.editText(conversationId, ackId, progress) }
                    }
                }
                if (result.isFailure && !ackId.isNullOrBlank()) {
                    // Do not leave the placeholder hanging when the agent could not answer.
                    gatewayManager.editText(
                        conversationId,
                        ackId,
                        "⚠️ ${result.exceptionOrNull()?.message ?: "Agent tidak dapat memproses pesan ini."}"
                    )
                }
            } finally {
                typingKeepAlive.cancel()
                gatewayManager.setTyping(conversationId, false)
            }
        }
    }

    suspend fun directChat(conversationId: String, prompt: String): Result<String> {
        updateModelRouter()
        val wasEnabled = agentLoop.agent.enabled
        if (!wasEnabled) agentLoop.agent = agentLoop.agent.copy(enabled = true)
        return try {
            val input = AgentInput(
                conversationId = conversationId,
                senderId = "user",
                content = prompt,
                channel = "internal_chat",
                metadata = mapOf("source" to "chat_ui")
            )
            agentLoop.processInput(input).map { it.content }
        } finally {
            if (!wasEnabled) agentLoop.agent = agentLoop.agent.copy(enabled = false)
        }
    }

    suspend fun testChat(prompt: String): Result<String> {
        return directChat(conversationId = "test-console", prompt = prompt)
    }

    companion object {
        @Volatile
        private var instance: WhatsAppAgentBridge? = null

        fun getInstance(context: Context): WhatsAppAgentBridge {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: WhatsAppAgentBridge(
                    appContext,
                    WaGatewayManager.getInstance(appContext)
                ).also { instance = it }
            }
        }
    }
}
