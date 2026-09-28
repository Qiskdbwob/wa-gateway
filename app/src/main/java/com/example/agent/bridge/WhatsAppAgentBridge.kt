package com.example.agent.bridge

import android.content.Context
import android.util.Base64
import com.example.agent.approval.ApprovalCoordinator
import com.example.agent.chat.ChatCommandHandler
import com.example.agent.loop.AgentLoop
import com.example.agent.mcp.McpManager
import com.example.agent.loop.ApprovalGate
import com.example.agent.memory.CompactManager
import com.example.agent.memory.ContextManager
import com.example.agent.memory.MemoryRepository
import com.example.agent.model.Agent
import com.example.agent.model.AgentChannelAdapter
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentResponse
import com.example.agent.model.AgentRole
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.provider.EchoTestProvider
import com.example.agent.provider.GeminiVisionProvider
import com.example.agent.provider.ModelProvider
import com.example.agent.provider.OpenAiCompatibleProvider
import com.example.agent.provider.OpenAiVisionProvider
import com.example.agent.provider.ProviderConfig
import com.example.agent.provider.ProviderDescriptor
import com.example.agent.provider.ProviderDirectory
import com.example.agent.provider.defaultModelHttpClient
import com.example.agent.provider.ProviderKeyPool
import com.example.agent.provider.VisionProvider
import com.example.agent.router.ModelProbeResult
import com.example.agent.router.ModelRouter
import com.example.agent.router.ModelTarget
import com.example.agent.router.RetryPolicy
import com.example.agent.scheduler.SchedulerEngine
import com.example.agent.scheduler.SchedulerWorker
import com.example.agent.storage.ContactAccessRepository
import com.example.agent.storage.RoomAgentSessionRepository
import com.example.agent.storage.SecretCipher
import com.example.agent.storage.agentIdFromJid
import com.example.agent.storage.db.AgentDatabase
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.storage.entity.ProviderEntity
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.subagent.SubAgentManager
import com.example.agent.subagent.SubAgentSpec
import com.example.agent.tool.DelegateTaskTool
import com.example.agent.tool.RecallMemoryTool
import com.example.agent.tool.ReflectTool
import com.example.agent.tool.RememberTool
import com.example.agent.tool.ScheduleTaskTool
import com.example.agent.tool.ToolConversation
import com.example.agent.tool.ToolRegistry
import com.example.agent.tool.WebFetchTool
import com.example.agent.tool.WebSearchTool
import com.example.agent.search.LocalSearchSources
import com.example.agent.tool.SearchEverythingTool
import com.example.agent.tool.ListSkillsTool
import com.example.agent.tool.ReadSkillTool
import com.example.agent.tool.SaveSkillTool
import com.example.agent.tool.CouncilTool
import com.example.agent.tool.BrowserClickTool
import com.example.agent.tool.BrowserClearSessionTool
import com.example.agent.tool.BrowserLoginTool
import com.example.agent.tool.BrowserOpenTool
import com.example.agent.tool.BrowserReadTool
import com.example.agent.tool.BrowserScreenshotTool
import com.example.agent.tool.BrowserScrollTool
import com.example.agent.tool.BrowserTypeTool
import com.example.agent.tool.BrowserUserHelpTool
import com.example.agent.tool.SendFileToChatTool
import com.example.agent.tool.TerminalInfoTool
import com.example.agent.tool.TerminalTool
import com.example.agent.browser.BrowserAutomationManager
import com.example.agent.browser.SiteCredential
import com.example.agent.browser.SiteCredentialStore
import com.example.agent.browser.WebViewBrowserEngine
import com.example.agent.model.ApprovalAwareTool
import com.example.agent.terminal.TerminalManager
import com.example.agent.workspace.Workspace
import com.example.wagateway.OutgoingMessageSender
import com.example.wagateway.WaGatewayManager
import com.example.wagateway.WaMediaMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Metadata key holding the WhatsApp message ID that should be edited with the answer. */
const val EDIT_TARGET_KEY = "whatsappEditTarget"

private const val THINKING_PLACEHOLDER = "⏳ Sedang berpikir..."

/** WhatsApp drops the typing state after a few seconds, so refresh it while working. */
private const val TYPING_REFRESH_MS = 8_000L

/** Cap for the one-off "nomor ini belum di whitelist" notices kept per process. */
private const val MAX_WHITELIST_NOTICES = 200

/** Videos above this size are not sent inline to the vision model (base64 inflates them). */
private const val MAX_INLINE_VIDEO_BYTES = 15 * 1024 * 1024

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

    private val providerDao = AgentDatabase.getInstance(context).providerDao()

    /** One HTTP client shared by every provider instance (see [defaultModelHttpClient]). */
    private val providerHttpClient = defaultModelHttpClient()

    /**
     * Multi-provider: every configured provider in display order, keys already decrypted. This is
     * the single source of truth — the Room table feeds it, and the router targets, the effective
     * [providerConfig] and the settings UI all derive from it.
     */
    private val _providers = MutableStateFlow<List<ProviderDescriptor>>(emptyList())
    val providers: StateFlow<List<ProviderDescriptor>> = _providers.asStateFlow()

    private val _activeProviderId = MutableStateFlow("")
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    /** Per-provider instances, created once and reused by the router. */
    private val providerInstances = mutableMapOf<String, OpenAiCompatibleProvider>()

    /**
     * Effective configuration of the *active* provider. Compaction, subagents, the probe and the
     * legacy save path all ask "which base URL and model do I use right now" without caring which
     * row that came from, so this stays as the one answer — refreshed whenever the list or the
     * selection changes.
     */
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

    /**
     * Instance used by loops that run without a router, and as the pre-router fallback. It reads
     * the effective [providerConfig], which always points at the active provider — so even the
     * router-less path follows the multi-provider selection.
     */
    private val openAiProvider = OpenAiCompatibleProvider(
        configProvider = { providerConfig.value },
        client = providerHttpClient
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
     * Priority 7 — the only place the file tools are allowed to touch. Lives in app-internal
     * storage (`filesDir`), so nothing outside the sandbox is reachable even if the model
     * asks for an absolute path.
     */
    val workspace: Workspace = Workspace.of(context.filesDir, "default-agent")

    // --- Priority 1: contact access control ------------------------------------------
    private val contactRuleDao = AgentDatabase.getInstance(context).contactRuleDao()
    val contactAccess = ContactAccessRepository(contactRuleDao)

    private val _whitelistMode = MutableStateFlow(false)
    val whitelistMode: StateFlow<Boolean> = _whitelistMode.asStateFlow()

    // --- Priority 2: long-term memory -------------------------------------------------
    private val memoryItemDao = AgentDatabase.getInstance(context).memoryItemDao()
    val memoryRepository = MemoryRepository(memoryItemDao)
    val compactManager = CompactManager(sessionRepository, memoryRepository)

    private val _longTermMemoryEnabled = MutableStateFlow(true)
    val longTermMemoryEnabled: StateFlow<Boolean> = _longTermMemoryEnabled.asStateFlow()

    private val _autoCompactEnabled = MutableStateFlow(true)
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    private val _maxContextMessages = MutableStateFlow(30)
    val maxContextMessages: StateFlow<Int> = _maxContextMessages.asStateFlow()

    // --- Priority 3: approvals ---------------------------------------------------------
    private val approvalDao = AgentDatabase.getInstance(context).approvalRequestDao()
    val approvalCoordinator = ApprovalCoordinator(approvalDao)

    private val _approvalEnabled = MutableStateFlow(true)
    val approvalEnabled: StateFlow<Boolean> = _approvalEnabled.asStateFlow()

    private val approvalGate = ApprovalGate { conversationId, toolName, arguments ->
        approvalCoordinator.createRequest(conversationId, toolName, arguments).id
    }

    // --- Priority 4: scheduler ---------------------------------------------------------
    private val scheduledTaskDao = AgentDatabase.getInstance(context).scheduledTaskDao()
    val scheduler = SchedulerEngine(scheduledTaskDao, runTask = { task ->
        executeScheduledTask(task)
    })

    // --- Priority 6: subagents ---------------------------------------------------------
    private val agentTaskDao = AgentDatabase.getInstance(context).agentTaskDao()
    private val subAgentManager = SubAgentManager(
        taskDao = agentTaskDao,
        executeTask = { task -> runSubAgentTurn(task) },
        onTaskFinished = { task, result -> deliverSubAgentResult(task, result) }
    )

    // --- Priority 5: vision ------------------------------------------------------------
    private val _visionConfig = MutableStateFlow(
        VisionConfig(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            apiKey = "",
            modelId = "gemini-2.0-flash",
            isGeminiNative = false
        )
    )
    val visionConfig: StateFlow<VisionConfig> = _visionConfig.asStateFlow()

    data class VisionConfig(
        val baseUrl: String,
        val apiKey: String,
        val modelId: String,
        /** true = native Gemini API, false = OpenAI-compatible endpoint. */
        val isGeminiNative: Boolean
    )

    // ==================================================================================
    // Terminal (built-in shell) — goal: the agent can curl/wget and run bash/python scripts
    // ==================================================================================
    val terminalManager = TerminalManager(workspace)

    private val _terminalEnabled = MutableStateFlow(true)
    val terminalEnabled: StateFlow<Boolean> = _terminalEnabled.asStateFlow()

    // ==================================================================================
    // Browser automation — goal: the agent can drive a real site (e.g. post to a social
    // network), with a human-in-the-loop handoff for captcha / 2FA.
    // ==================================================================================
    private val _browserEnabled = MutableStateFlow(false)
    val browserEnabled: StateFlow<Boolean> = _browserEnabled.asStateFlow()

    private val _browserSites = MutableStateFlow<List<SiteCredential>>(emptyList())
    val browserSites: StateFlow<List<SiteCredential>> = _browserSites.asStateFlow()

    private val _browserUserAgent = MutableStateFlow("")

    /**
     * Context the WebView is created with. The Browser screen swaps this to its Activity while
     * it is open (so rendering, screenshots and input all behave) and back afterwards.
     */
    @Volatile
    var browserHostContext: Context = context

    val browserEngine = WebViewBrowserEngine(
        contextProvider = { browserHostContext },
        userAgentProvider = { _browserUserAgent.value.takeIf { it.isNotBlank() } }
    )

    val browserAutomation = BrowserAutomationManager(
        engine = browserEngine,
        credentials = { _browserSites.value },
        notifyUser = { conversationId, message ->
            if (conversationId.isNotBlank()) gatewayManager.sendText(conversationId, message)
        }
    )

    /** Markdown skills: files in the workspace `skills` folder, indexed into the prompt. */
    val skillLibrary = com.example.agent.skills.SkillLibrary(workspace)

    private val _skills = MutableStateFlow<List<com.example.agent.skills.Skill>>(emptyList())
    val skills: StateFlow<List<com.example.agent.skills.Skill>> = _skills.asStateFlow()

    /** Last model calls (success and failure) for the Developer metrics panel. */
    private val _modelMetrics = MutableStateFlow<List<AgentLoop.ModelCallMetric>>(emptyList())
    val modelMetrics: StateFlow<List<AgentLoop.ModelCallMetric>> = _modelMetrics.asStateFlow()

    /**
     * MCP servers (config + discovered tools). One configured server can add many tools, which is
     * why this manager keeps the registry in sync instead of the bridge doing it by hand.
     */
    val mcpManager = McpManager(
        dao = AgentDatabase.getInstance(context).mcpServerDao(),
        registry = { toolRegistry },
        decrypt = { SecretCipher.decrypt(it) },
        encrypt = { SecretCipher.encrypt(it) },
        log = { tag, message -> agentLoop.log(tag, message) }
    )

    val toolRegistry: ToolRegistry = buildToolRegistry()

    val agentLoop = AgentLoop(
        agent = Agent(
            systemPrompt = systemPrompt.value,
            enabled = false
        ),
        modelProvider = openAiProvider,
        sessionRepository = sessionRepository,
        modelRouter = modelRouter,
        toolRegistry = toolRegistry,
        approvalGate = approvalGate
    )

    private val _isAutoReplyEnabled = MutableStateFlow(false)
    val isAutoReplyEnabled: StateFlow<Boolean> = _isAutoReplyEnabled.asStateFlow()

    /** Periodic self-reflection (runs as a scheduled task so it survives restarts). */
    private val _autoReflectEnabled = MutableStateFlow(false)
    val autoReflectEnabled: StateFlow<Boolean> = _autoReflectEnabled.asStateFlow()

    private val _autoReflectIntervalHours = MutableStateFlow(DEFAULT_AUTO_REFLECT_HOURS)
    val autoReflectIntervalHours: StateFlow<Int> = _autoReflectIntervalHours.asStateFlow()

    private val _useEchoFallback = MutableStateFlow(true)
    val useEchoFallback: StateFlow<Boolean> = _useEchoFallback.asStateFlow()

    /** Latest pending approval surfaced by the gate (for the UI). */
    val pendingApprovals = approvalCoordinator.pendingFlow()

    init {
        // Register WhatsApp outgoing channel adapter to the Agent Loop
        agentLoop.registerChannelAdapter(WhatsAppChannelAdapter(gatewayManager))
        // Model-call metrics feed the Developer panel (numbers, not just log lines).
        agentLoop.onModelCall = { metric ->
            _modelMetrics.value = (listOf(metric) + _modelMetrics.value).take(MAX_MODEL_METRICS)
        }
        updateModelRouter()

        // Load persisted configuration from Room on startup
        scope.launch {
            try {
                val saved = sessionRepository.getConfig()
                if (saved != null) {
                    _isAutoReplyEnabled.value = saved.isAutoReplyEnabled
                    _useEchoFallback.value = saved.useEchoFallback
                    _whitelistMode.value = saved.whitelistMode
                    _longTermMemoryEnabled.value = saved.longTermMemoryEnabled
                    _autoCompactEnabled.value = saved.autoCompactEnabled
                    _maxContextMessages.value = saved.maxContextMessages
                    _visionConfig.value = VisionConfig(
                        baseUrl = saved.visionBaseUrl,
                        apiKey = SecretCipher.decrypt(saved.visionApiKey),
                        modelId = saved.visionModelId,
                        isGeminiNative = saved.visionBaseUrl.contains("generativelanguage.googleapis.com") &&
                            !saved.visionBaseUrl.contains("/openai")
                    )
                    providerConfig.value = ProviderConfig(
                        baseUrl = saved.baseUrl,
                        apiKey = SecretCipher.decrypt(saved.apiKey),
                        modelId = saved.modelId,
                        apiKeys = ProviderKeyPool.parse(SecretCipher.decrypt(saved.apiKeys))
                    )
                    systemPrompt.value = saved.systemPrompt
                    agentLoop.agent = agentLoop.agent.copy(
                        enabled = saved.isAutoReplyEnabled,
                        systemPrompt = saved.systemPrompt,
                        modelId = saved.modelId
                    )
                    _autoReflectEnabled.value = saved.autoReflectEnabled
                    _autoReflectIntervalHours.value = saved.autoReflectIntervalHours
                    _terminalEnabled.value = saved.terminalEnabled
                    _browserEnabled.value = saved.browserEnabled
                    _browserUserAgent.value = saved.browserUserAgent
                    _browserSites.value = SiteCredentialStore.decode(
                        SecretCipher.decrypt(saved.browserSites)
                    )
                    applyTerminalTools(toolRegistry, saved.terminalEnabled)
                    applyBrowserTools(toolRegistry, saved.browserEnabled)
                    _activeProviderId.value = saved.activeProviderId
                    updateModelRouter()
                }
                // Multi-provider: turn a pre-multi-provider install into the first provider row
                // (only while the table is still empty), then follow the rows from now on.
                seedProvidersFromLegacy(saved)
                scope.launch { observeProviders() }
                // Approvals that expired while the app was closed.
                approvalCoordinator.expireStale()
                // Destructive tools are advertised only while approval routing is on.
                toolRegistry.approvalEnabled = _approvalEnabled.value
                // Start the scheduler ticker (checks every 60 s) and register the WorkManager
                // wake-up so due tasks still run after the process was killed.
                SchedulerEngine.startTicking(scheduler, scope = scope)
                SchedulerWorker.enqueue(context)
                // Make sure the optional periodic self-reflection exists / is paused as configured.
                syncAutoReflection()
                refreshSkills()
                // Discover MCP tools. Network call: every failure is reported per server in the
                // Settings status line and never blocks startup.
                mcpManager.refresh()
            } catch (_: Exception) {
                // Ignore initialization error and continue with defaults
            }
        }

        // Register listeners to gateway incoming messages
        gatewayManager.addMessageListener { sender, chat, isGroup, text, messageId, timestamp ->
            handleIncomingMessage(sender, chat, isGroup, text, messageId, timestamp)
        }
        gatewayManager.addMediaListener { media ->
            handleIncomingMedia(media)
        }
    }

    private fun buildToolRegistry(): ToolRegistry {
        val registry = ToolRegistry.withBuiltIns(workspace)
        // Priority 3 — read-only network tools run automatically (AUTO_SAFE).
        registry.register(WebSearchTool())
        registry.register(WebFetchTool())
        // Priority 2 — memory tools.
        registry.register(RememberTool(memoryRepository))
        registry.register(RecallMemoryTool(memoryRepository))
        // Unified search — one call across memory, chat, tasks, files and the tool list.
        registry.register(
            SearchEverythingTool(
                LocalSearchSources(
                    database = AgentDatabase.getInstance(context),
                    toolRegistry = { toolRegistry },
                    workspace = workspace
                )
            )
        )
        // Markdown skills — index in the prompt, body on demand, new ones written by the agent.
        registry.register(ListSkillsTool(skillLibrary))
        registry.register(ReadSkillTool(skillLibrary))
        registry.register(SaveSkillTool(skillLibrary, workspace))
        // Priority 6 — subagent + reflection.
        registry.register(
            DelegateTaskTool { spec, conversationId ->
                subAgentManager.launchTask(spec, conversationId).id
            }
        )
        registry.register(ReflectTool(memoryRepository))
        // Priority 6 — council (bounded debate of 2 personas).
        registry.register(
            CouncilTool { topic, conversationId -> runCouncil(topic, conversationId) }
        )
        // Priority 4 — scheduler.
        registry.register(
            ScheduleTaskTool { name, schedule, prompt, conversationId ->
                scheduler.create(name, schedule, prompt, conversationId).id
            }
        )
        // Terminal tools — the agent can run curl/wget/bash/python, with per-command approval.
        applyTerminalTools(registry, _terminalEnabled.value)
        // Browser automation tools — off until the user opts in from Settings.
        applyBrowserTools(registry, _browserEnabled.value)
        // Lets the agent hand a produced file (screenshot, report, download) to the user.
        registry.register(
            SendFileToChatTool { conversationId, path, caption ->
                sendWorkspaceFile(conversationId, path, caption)
            }
        )
        return registry
    }

    /** Registers or removes the terminal tools when the user toggles them in Settings. */
    private fun applyTerminalTools(registry: ToolRegistry, enabled: Boolean) {
        if (enabled) {
            registry.register(
                TerminalTool(
                    manager = terminalManager,
                    approvalEnabled = { _approvalEnabled.value },
                    requestApproval = { conversationId, arguments ->
                        approvalCoordinator.createRequest(conversationId, "run_command", arguments).id
                    }
                )
            )
            registry.register(TerminalInfoTool(terminalManager))
        } else {
            registry.unregister("run_command")
            registry.unregister("terminal_info")
        }
    }

    /** Registers or removes the browser automation tools when the user toggles them. */
    private fun applyBrowserTools(registry: ToolRegistry, enabled: Boolean) {
        if (enabled) {
            registry.register(BrowserOpenTool(browserAutomation))
            registry.register(BrowserReadTool(browserAutomation))
            registry.register(BrowserClickTool(browserAutomation))
            registry.register(BrowserTypeTool(browserAutomation))
            registry.register(BrowserScrollTool(browserAutomation))
            registry.register(
                BrowserScreenshotTool(browserAutomation) { bytes, fileName ->
                    saveScreenshot(bytes, fileName)
                }
            )
            registry.register(BrowserLoginTool(browserAutomation))
            registry.register(BrowserUserHelpTool(browserAutomation))
            registry.register(BrowserClearSessionTool(browserAutomation))
        } else {
            BROWSER_TOOL_NAMES.forEach { registry.unregister(it) }
        }
    }

    /** Stores a browser screenshot inside the workspace and returns its relative path. */
    private fun saveScreenshot(bytes: ByteArray, fileName: String): String {
        val directory = terminalManager.runner.outputDirectory
        val file = File(directory, fileName.substringAfterLast('/').ifBlank { "screenshot.png" })
        file.writeBytes(bytes)
        return workspace.relativePath(file)
    }

    /** Minimal MIME lookup so WhatsApp receives a sensible type for a workspace file. */
    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "log", "json", "csv" -> "text/plain"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        // WhatsApp voice notes are Ogg/Opus; the container is what the client checks.
        "ogg", "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    /** Sends a workspace file to the requesting WhatsApp chat (image as photo, otherwise document). */
    private suspend fun sendWorkspaceFile(
        conversationId: String,
        path: String,
        caption: String
    ): ToolResult {
        val file = try {
            workspace.resolve(path)
        } catch (e: Exception) {
            return ToolResult(false, "", "Path ditolak: ${e.message}")
        }
        if (!file.exists() || !file.isFile) {
            return ToolResult(false, "", "File tidak ada di workspace: $path")
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return ToolResult(false, "", "Gagal membaca file: ${e.message}")
        }
        if (bytes.size > MAX_SEND_BYTES) {
            return ToolResult(
                false,
                "",
                "File terlalu besar (${bytes.size / (1024 * 1024)} MB); batas pengiriman 16 MB."
            )
        }
        if (!conversationId.contains('@')) {
            return ToolResult(
                false,
                "",
                "Percakapan ini bukan chat WhatsApp, jadi file tidak bisa dikirim. " +
                    "File tersimpan di workspace: ${workspace.relativePath(file)}"
            )
        }
        val mime = guessMimeType(file.name)
        // Route by media type: WhatsApp only renders a playable video clip or an Ogg/Opus
        // voice note when the file arrives through the matching API, so a generic document
        // upload would lose that (and the SendAudio/SendVideo bindings would stay unusable).
        val result = when {
            mime.startsWith("image/") -> gatewayManager.sendImage(conversationId, bytes, mime, caption)
            mime.startsWith("video/") -> gatewayManager.sendVideo(conversationId, bytes, mime, caption)
            mime.startsWith("audio/") ->
                gatewayManager.sendAudio(conversationId, bytes, mime, voiceNote = mime == "audio/ogg")
            else -> gatewayManager.sendDocument(conversationId, bytes, mime, file.name)
        }
        return if (result.isSuccess) {
            ToolResult(
                success = true,
                output = "File \"${file.name}\" terkirim ke chat.",
                metadata = mapOf("path" to workspace.relativePath(file))
            )
        } else {
            ToolResult(false, "", "Gagal mengirim file: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Rebuilds the router targets from the provider list: the active provider first, then every
     * other usable provider in display order, then the optional echo fallback.
     *
     * This is the whole failover policy for multiple providers: the Agent Loop already walks its
     * targets by priority and only gives up when the budget is spent, so "second provider" needs
     * no new logic in the loop — it is simply the second target. Key rotation inside one provider
     * stays where it was, in [OpenAiCompatibleProvider].
     */
    private fun updateModelRouter() {
        val ordered = ProviderDirectory.failoverOrder(_providers.value, _activeProviderId.value)
        val targets = ordered.mapIndexed { index, descriptor ->
            ModelTarget(
                id = "provider-${descriptor.id}",
                provider = instanceFor(descriptor),
                modelId = descriptor.modelId,
                priority = index,
                enabled = true
            )
        }.toMutableList()

        if (_useEchoFallback.value) {
            targets.add(
                ModelTarget(
                    id = "echo-fallback",
                    provider = echoProvider,
                    modelId = "echo-model-v1",
                    priority = targets.size,
                    enabled = true
                )
            )
        }
        modelRouter.setTargets(targets)
        refreshEffectiveProvider(ordered.firstOrNull())
    }

    /**
     * Points the shared "which model right now" state at the selected provider. Called whenever the
     * list or the selection changes, so nothing has to re-derive it at the call site.
     */
    private fun refreshEffectiveProvider(active: ProviderDescriptor? = null) {
        val current = active ?: ProviderDirectory.resolveActive(_providers.value, _activeProviderId.value) ?: return
        providerConfig.value = ProviderConfig(
            baseUrl = current.baseUrl,
            apiKey = current.keys.firstOrNull().orEmpty(),
            modelId = current.modelId,
            apiKeys = current.keys.drop(1)
        )
        agentLoop.agent = agentLoop.agent.copy(modelId = current.modelId)
    }

    /**
     * One instance per provider row. The instance reads its row on every call, so editing a key or
     * a base URL applies immediately without rebuilding the router.
     */
    private fun instanceFor(descriptor: ProviderDescriptor): OpenAiCompatibleProvider =
        providerInstances.getOrPut(descriptor.id) {
            OpenAiCompatibleProvider(
                configProvider = {
                    val row = _providers.value.firstOrNull { it.id == descriptor.id }
                    if (row == null) {
                        ProviderConfig(baseUrl = descriptor.baseUrl, modelId = descriptor.modelId)
                    } else {
                        ProviderConfig(
                            baseUrl = row.baseUrl,
                            apiKey = row.keys.firstOrNull().orEmpty(),
                            modelId = row.modelId,
                            apiKeys = row.keys.drop(1)
                        )
                    }
                },
                client = providerHttpClient
            )
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

    fun setWhitelistMode(enabled: Boolean) {
        _whitelistMode.value = enabled
        persistConfig()
    }

    fun setLongTermMemoryEnabled(enabled: Boolean) {
        _longTermMemoryEnabled.value = enabled
        persistConfig()
    }

    fun setAutoCompactEnabled(enabled: Boolean) {
        _autoCompactEnabled.value = enabled
        persistConfig()
    }

    fun setMaxContextMessages(count: Int) {
        _maxContextMessages.value = count.coerceIn(10, 200)
        persistConfig()
    }

    fun setApprovalEnabled(enabled: Boolean) {
        _approvalEnabled.value = enabled
        toolRegistry.approvalEnabled = enabled
        persistConfig()
    }

    fun setVisionConfig(baseUrl: String, apiKey: String, modelId: String, geminiNative: Boolean) {
        _visionConfig.value = VisionConfig(
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            modelId = modelId.trim(),
            isGeminiNative = geminiNative
        )
        persistConfig()
    }

    // ==================================================================================
    // Terminal + browser automation settings
    // ==================================================================================

    fun setTerminalEnabled(enabled: Boolean) {
        _terminalEnabled.value = enabled
        applyTerminalTools(toolRegistry, enabled)
        persistConfig()
    }

    /** Turning the browser on is the user's consent for the agent to drive a real session. */
    fun setBrowserEnabled(enabled: Boolean) {
        _browserEnabled.value = enabled
        applyBrowserTools(toolRegistry, enabled)
        persistConfig()
    }

    fun setBrowserUserAgent(userAgent: String) {
        _browserUserAgent.value = userAgent.trim()
        persistConfig()
    }

    /** Current custom User-Agent (empty = engine default). */
    fun browserUserAgentValue(): String = _browserUserAgent.value

    /** Adds or replaces the stored account for one site (password is encrypted at rest). */
    fun addBrowserSite(site: String, loginUrl: String, username: String, password: String) {
        val trimmedSite = site.trim()
        if (trimmedSite.isEmpty()) return
        val remaining = _browserSites.value.filterNot { it.site.equals(trimmedSite, ignoreCase = true) }
        _browserSites.value = remaining + SiteCredential(
            site = trimmedSite,
            loginUrl = loginUrl.trim(),
            username = username.trim(),
            password = password,
            notes = ""
        )
        persistConfig()
    }

    fun removeBrowserSite(site: String) {
        _browserSites.value = _browserSites.value.filterNot { it.site.equals(site, ignoreCase = true) }
        persistConfig()
    }

    /**
     * Saves the model configuration. [apiKeyPool] is the optional "keys pool" blob (one extra key
     * per line) that the provider rotates through when a key hits its rate limit or quota.
     */
    fun updateConfig(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        apiKeyPool: String = ""
    ) {
        providerConfig.value = ProviderConfig(
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            modelId = modelId.trim(),
            apiKeys = ProviderKeyPool.parse(apiKeyPool)
        )
        systemPrompt.value = prompt.trim()
        agentLoop.agent = agentLoop.agent.copy(
            systemPrompt = prompt.trim(),
            modelId = modelId.trim()
        )
        updateModelRouter()
        persistConfig()
        // The legacy single-provider save path now edits the active provider row too, so the old
        // columns and the new table cannot drift apart.
        scope.launch {
            val active = ProviderDirectory.resolveActive(_providers.value, _activeProviderId.value)
                ?: return@launch
            writeProvider(
                active.copy(
                    baseUrl = baseUrl.trim(),
                    modelId = modelId.trim(),
                    keys = ProviderDirectory.sanitizeKeys(listOf(apiKey) + ProviderKeyPool.parse(apiKeyPool))
                )
            )
        }
    }

    // ==================================================================================
    // Multi-provider (UI surface)
    //
    // Several providers can be configured, each with its own key pool. The active one serves
    // requests; when its keys are exhausted the router moves on to the next usable provider, in
    // the order shown in Settings. Everything below writes to Room and lets the table feed the
    // in-memory list, so there is exactly one source of truth.
    // ==================================================================================

    /** Adds a provider; the first one added also becomes the active one. */
    suspend fun addProvider(
        label: String,
        baseUrl: String,
        modelId: String,
        keysRaw: String
    ): String {
        val keys = ProviderDirectory.sanitizeKeys(ProviderKeyPool.parse(keysRaw))
        val descriptor =
            ProviderDescriptor(
                id = ProviderDirectory.newProviderId(_providers.value.map { it.id }),
                label = label.trim().ifBlank { "Provider ${_providers.value.size + 1}" },
                baseUrl = baseUrl.trim().ifBlank { ProviderConfig().baseUrl },
                modelId = modelId.trim().ifBlank { ProviderConfig().modelId },
                keys = keys,
                enabled = true,
                sortOrder = ProviderDirectory.nextSortOrder(_providers.value)
            )
        writeProvider(descriptor)
        if (_activeProviderId.value.isBlank()) setActiveProvider(descriptor.id)
        val keyNote = if (keys.size > 1) "${keys.size} kunci (dirotasi otomatis)" else "${keys.size} kunci"
        return "Provider \"${descriptor.label}\" ditambahkan dengan $keyNote."
    }

    /** Edits a provider in place; its keys become whatever the field now holds. */
    suspend fun updateProvider(
        id: String,
        label: String,
        baseUrl: String,
        modelId: String,
        keysRaw: String
    ): String {
        val existing = _providers.value.firstOrNull { it.id == id } ?: return "Provider tidak ditemukan."
        val keys = ProviderDirectory.sanitizeKeys(ProviderKeyPool.parse(keysRaw))
        writeProvider(
            existing.copy(
                label = label.trim().ifBlank { existing.label },
                baseUrl = baseUrl.trim(),
                modelId = modelId.trim(),
                keys = keys
            )
        )
        return "Provider \"${existing.displayLabel}\" diperbarui (${keys.size} kunci)."
    }

    suspend fun deleteProvider(id: String): String {
        val existing = _providers.value.firstOrNull { it.id == id } ?: return "Provider tidak ditemukan."
        providerInstances.remove(id)
        providerDao.delete(id)
        if (_activeProviderId.value == id) {
            _activeProviderId.value = ""
            persistConfig()
        }
        return "Provider \"${existing.displayLabel}\" dihapus."
    }

    /** Selects the provider that serves requests. */
    fun setActiveProvider(id: String) {
        _activeProviderId.value = id
        persistConfig()
        updateModelRouter()
    }

    suspend fun setProviderEnabled(id: String, enabled: Boolean) {
        val existing = _providers.value.firstOrNull { it.id == id } ?: return
        writeProvider(existing.copy(enabled = enabled))
    }

    /** Moves a provider one slot in the failover order. */
    suspend fun moveProvider(id: String, delta: Int): String {
        val reordered = ProviderDirectory.move(_providers.value, id, delta)
        reordered.forEachIndexed { index, descriptor ->
            if (descriptor.sortOrder != index) writeProvider(descriptor.copy(sortOrder = index))
        }
        return "Urutan provider diperbarui."
    }

    /** Writes a row, keeping the original creation timestamp on edits. */
    private suspend fun writeProvider(descriptor: ProviderDescriptor) {
        val existing = providerDao.getAll().firstOrNull { it.id == descriptor.id }
        providerDao.upsert(
            ProviderEntity(
                id = descriptor.id,
                label = descriptor.label,
                baseUrl = descriptor.baseUrl,
                modelId = descriptor.modelId,
                keys = SecretCipher.encrypt(ProviderKeyPool.format(descriptor.keys)),
                enabled = descriptor.enabled,
                sortOrder = descriptor.sortOrder,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun ProviderEntity.toDescriptor(): ProviderDescriptor =
        ProviderDescriptor(
            id = id,
            label = label,
            baseUrl = baseUrl,
            modelId = modelId,
            keys = ProviderDirectory.sanitizeKeys(ProviderKeyPool.parse(SecretCipher.decrypt(keys))),
            enabled = enabled,
            sortOrder = sortOrder
        )

    /**
     * One-time upgrade path: an install that was configured before multi-provider existed becomes
     * the first row, keys and all, so the user does not have to re-enter anything.
     */
    private suspend fun seedProvidersFromLegacy(saved: AgentConfigEntity?) {
        if (providerDao.count() > 0) return
        val primaryKey = SecretCipher.decrypt(saved?.apiKey.orEmpty())
        val poolKeys = ProviderKeyPool.parse(SecretCipher.decrypt(saved?.apiKeys.orEmpty()))
        val baseUrl = saved?.baseUrl.orEmpty()
        if (baseUrl.isBlank() && primaryKey.isBlank() && poolKeys.isEmpty()) return
        writeProvider(
            ProviderDirectory.seedFromLegacy(
                baseUrl = baseUrl,
                modelId = saved?.modelId.orEmpty(),
                keys = listOf(primaryKey) + poolKeys
            )
        )
    }

    /** Follows the provider table; every change re-derives the router targets. */
    private suspend fun observeProviders() {
        providerDao.observeAll().collect { rows ->
            _providers.value = rows.map { it.toDescriptor() }
            ensureActiveProvider()
            updateModelRouter()
        }
    }

    /** Keeps the selection pointing at a provider that exists and can answer. */
    private fun ensureActiveProvider() {
        val resolved = ProviderDirectory.resolveActive(_providers.value, _activeProviderId.value)
        val nextId = resolved?.id.orEmpty()
        if (nextId != _activeProviderId.value) {
            _activeProviderId.value = nextId
            persistConfig()
        }
    }

    // ==================================================================================
    // MCP connector (UI surface)
    // ==================================================================================

    /** Adds a server and discovers its tools; the message is shown in Settings. */
    suspend fun addMcpServer(name: String, url: String, headers: String): String =
        mcpManager.add(name, url, headers).fold(
            onSuccess = { server ->
                val tools = mcpManager.toolNames().size
                "MCP '${server.name}' ditambahkan. Total tool MCP terdaftar: $tools."
            },
            onFailure = { error -> error.message ?: "Gagal menambah MCP server." }
        )

    suspend fun removeMcpServer(id: String) = mcpManager.remove(id)

    suspend fun setMcpServerEnabled(id: String, enabled: Boolean) = mcpManager.setEnabled(id, enabled)

    suspend fun refreshMcpTools(): String {
        mcpManager.refresh()
        val statuses = mcpManager.statuses.value
        val failed = statuses.count { it.value.startsWith("error") }
        if (failed == 0) {
            return "${mcpManager.toolNames().size} tool MCP terdaftar dari ${statuses.size} server."
        }
        val names = mcpManager.servers.value.associate { it.id to it.name }
        val detail = statuses.filterValues { it.startsWith("error") }
            .map { (id, status) -> "${names[id] ?: id} → ${status.removePrefix("error: ")}" }
            .joinToString("; ")
        return "$failed server MCP gagal: $detail"
    }

    /** One-key-per-line rendering of the pool, for the Settings field. */
    val apiKeyPoolText: String get() = ProviderKeyPool.format(providerConfig.value.apiKeys)

    /** How many distinct keys the provider may rotate through (1 = single key, no pool). */
    val apiKeyPoolSize: Int get() = providerConfig.value.keyPool().size

    /** Probes whichever provider is currently selected (the Settings "Uji" button). */
    suspend fun probeCurrentModel(): ModelProbeResult {
        val active = ProviderDirectory.resolveActive(_providers.value, _activeProviderId.value)
            ?: return ModelProbeResult(
                targetId = "none",
                available = false,
                latencyMs = 0,
                error = "Belum ada provider aktif dengan API key"
            )
        return probeProvider(active)
    }

    /** Probes one provider by id; used by the per-provider test action. */
    suspend fun probeProvider(id: String): ModelProbeResult {
        val descriptor = _providers.value.firstOrNull { it.id == id }
            ?: return ModelProbeResult(
                targetId = id,
                available = false,
                latencyMs = 0,
                error = "Provider tidak ditemukan"
            )
        return probeProvider(descriptor)
    }

    private suspend fun probeProvider(descriptor: ProviderDescriptor): ModelProbeResult {
        if (descriptor.keys.isEmpty()) {
            return ModelProbeResult(
                targetId = "provider-${descriptor.id}",
                available = false,
                latencyMs = 0,
                error = "Provider ini belum punya API key"
            )
        }
        return modelRouter.probeModel(
            ModelTarget(
                id = "provider-${descriptor.id}",
                provider = instanceFor(descriptor),
                modelId = descriptor.modelId
            )
        )
    }

    /**
     * Resolves the current model provider for compact/summarize calls and the other one-shot
     * paths: the active provider's own instance, so those calls honour its base URL and key pool.
     */
    private fun resolveProviderPair(): Pair<ModelProvider, String?> {
        val active = ProviderDirectory.resolveActive(_providers.value, _activeProviderId.value)
            ?: return openAiProvider to providerConfig.value.modelId
        return instanceFor(active) to active.modelId
    }

    /** The vision provider to use plus the model id that belongs to it. */
    private data class VisionTarget(val provider: VisionProvider, val modelId: String)

    /**
     * Picks who reads the incoming media. A dedicated vision key wins; without one the active
     * chat provider is reused, so a photo is understood out of the box whenever that provider
     * can see images. The model id has to travel with the provider (the vision default and the
     * chat default are different names), which is why both are returned together.
     */
    private fun visionTarget(): VisionTarget? {
        val config = _visionConfig.value
        if (config.apiKey.isNotBlank()) {
            val provider = if (config.isGeminiNative) {
                GeminiVisionProvider(apiKey = config.apiKey, defaultModel = config.modelId)
            } else {
                OpenAiVisionProvider(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    defaultModel = config.modelId
                )
            }
            return VisionTarget(provider, config.modelId)
        }

        val active = providerConfig.value
        if (active.apiKey.isBlank() || active.baseUrl.isBlank()) return null
        val geminiNative = active.baseUrl.contains("generativelanguage.googleapis.com") &&
            !active.baseUrl.contains("/openai")
        val provider = if (geminiNative) {
            GeminiVisionProvider(apiKey = active.apiKey, defaultModel = active.modelId)
        } else {
            OpenAiVisionProvider(
                baseUrl = active.baseUrl,
                apiKey = active.apiKey,
                defaultModel = active.modelId
            )
        }
        return VisionTarget(provider, active.modelId)
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
                        apiKeys = SecretCipher.encrypt(ProviderKeyPool.format(providerConfig.value.apiKeys)),
                        modelId = providerConfig.value.modelId,
                        activeProviderId = _activeProviderId.value,
                        systemPrompt = systemPrompt.value,
                        whitelistMode = _whitelistMode.value,
                        longTermMemoryEnabled = _longTermMemoryEnabled.value,
                        autoCompactEnabled = _autoCompactEnabled.value,
                        maxContextMessages = _maxContextMessages.value,
                        visionBaseUrl = _visionConfig.value.baseUrl,
                        visionApiKey = SecretCipher.encrypt(_visionConfig.value.apiKey),
                        visionModelId = _visionConfig.value.modelId,
                        autoReflectEnabled = _autoReflectEnabled.value,
                        autoReflectIntervalHours = _autoReflectIntervalHours.value,
                        terminalEnabled = _terminalEnabled.value,
                        browserEnabled = _browserEnabled.value,
                        browserSites = SecretCipher.encrypt(SiteCredentialStore.encode(_browserSites.value)),
                        browserUserAgent = _browserUserAgent.value,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // Ignore storage error
            }
        }
    }

    // ==================================================================================
    // Incoming WhatsApp TEXT
    // ==================================================================================

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

        // Priority 1 — contact access control runs before ANY processing or reply.
        val contactId = agentIdFromJid(conversationId)
        scope.launch {
            val access = contactAccess.getAccess(contactId, _whitelistMode.value)
            if (access.decision == ContactAccessRepository.Decision.BLOCK) {
                val ruleLabel = access.rule?.mode ?: "none"
                agentLoop.log(
                    "CONTACT_BLOCKED",
                    "Pesan dari '$contactId' diblokir (rule=$ruleLabel, " +
                        "whitelistMode=${_whitelistMode.value})."
                )
                // A number blocked only because the whitelist is ON and it was never added
                // would otherwise get no answer at all, which reads as a broken agent.
                // Explicit blacklist/PENDING rules keep their silence.
                if (access.rule == null) notifyNotWhitelisted(conversationId, contactId)
                return@launch
            }

            // Chat commands (/help, /whitelist, /approve, ...) are handled by the bot
            // itself and never reach the model.
            val session = sessionRepository.getOrCreateSession(conversationId)
            val commandResult = chatCommandHandler().handle(
                text = text,
                conversationId = conversationId,
                contactId = contactId,
                sessionId = session.sessionId
            )
            if (commandResult is ChatCommandHandler.Result.Handled) {
                gatewayManager.sendText(conversationId, commandResult.reply)
                return@launch
            }

            processAgentTurn(conversationId, sender, text, messageId, timestamp, mediaInfo = null)
        }
    }

    /**
     * Contacts already told that their number is not whitelisted. Kept per process so a
     * stranger cannot make the agent answer every single message with the same notice.
     */
    private val notWhitelistNotified: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Tells a sender why the agent stayed silent: their number is not in the whitelist.
     * Sent at most once per contact per process; the owner adds numbers in
     * Pengaturan → Mode whitelist.
     */
    private suspend fun notifyNotWhitelisted(conversationId: String, contactId: String) {
        if (notWhitelistNotified.size >= MAX_WHITELIST_NOTICES) return
        if (!notWhitelistNotified.add(contactId)) return
        agentLoop.log(
            "CONTACT_NOT_WHITELISTED",
            "Memberi tahu '$contactId' bahwa nomornya belum ada di whitelist."
        )
        gatewayManager.sendText(
            conversationId,
            "🔐 Nomor ini ($contactId) belum ada di whitelist, jadi pesannya belum diteruskan ke agent.\n" +
                "Tambahkan nomornya di Pengaturan → Mode whitelist supaya bisa mengobrol dengan agent."
        )
    }

    /** Lazily builds the command handler (needs the loop and repos, all singletons). */
    private fun chatCommandHandler(): ChatCommandHandler = ChatCommandHandler(
        contactAccess = contactAccess,
        approvals = approvalCoordinator,
        memory = memoryRepository,
        compact = compactManager,
        scheduledTaskDao = scheduledTaskDao,
        agentLoop = agentLoop,
        isWhitelistMode = { _whitelistMode.value },
        setWhitelistMode = { setWhitelistMode(it) },
        executeApprovedTool = { toolName, arguments, conversationId ->
            executeApprovedToolCall(toolName, arguments, conversationId)
        },
        resolveProvider = { resolveProviderPair() },
        maxContextMessages = { _maxContextMessages.value },
        runTerminalCommand = { command, conversationId -> runTerminalFromChat(command, conversationId) },
        openBrowserUrl = { url, conversationId -> openBrowserFromChat(url, conversationId) }
    )

    /**
     * `/terminal <cmd>` — same policy as the `run_command` tool: a destructive command becomes
     * an approval request (the user then answers /approve <id>) instead of running silently.
     */
    private suspend fun runTerminalFromChat(command: String, conversationId: String): String {
        if (!_terminalEnabled.value) {
            return "Tool terminal sedang nonaktif. Aktifkan dulu di Pengaturan → Terminal."
        }
        val verdict = com.example.agent.terminal.ShellPolicy.assess(command)
        if (verdict.destructive) {
            val arguments = "{\"command\":\"" + command
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"}"
            val request = approvalCoordinator.createRequest(conversationId, "run_command", arguments)
            return "🔐 Perintah ini perlu persetujuan (${verdict.reason}).\n" +
                "Balas /approve ${request.id} untuk menjalankan atau /reject ${request.id} untuk membatalkan."
        }
        val result = terminalManager.runner.run(command)
        val body = result.output.ifBlank { "(tanpa output)" }
        return "$ $command\n(exit ${result.exitCode})\n$body"
    }

    /** `/browser <url>` — opens a page with the agent's browser session and returns its text. */
    private suspend fun openBrowserFromChat(url: String, conversationId: String): String {
        if (!_browserEnabled.value) {
            return "Browser automation sedang nonaktif. Aktifkan dulu di Pengaturan → Browser."
        }
        val opened = browserAutomation.open(url)
        if (!opened.ok) return "Gagal membuka $url: ${opened.error ?: "tidak diketahui"}"
        return browserAutomation.describe(browserAutomation.read(3_000), 3_000)
    }

    /**
     * Executes a previously approved CONFIRM tool call (after `/approve <id>`).
     * The tool is looked up by name and executed directly — approval already happened.
     */
    private suspend fun executeApprovedToolCall(
        toolName: String,
        arguments: String,
        conversationId: String
    ): String {
        val tool = toolRegistry.get(toolName)
            ?: return "Tool '$toolName' tidak ditemukan."
        // The call was approved by the user, so it runs directly here — the gate is only
        // bypassed for this single, explicitly confirmed invocation. Tools whose permission
        // depends on their arguments (run_command) get executeApproved so the policy does not
        // ask for the same approval a second time.
        return try {
            val result = if (tool is ApprovalAwareTool) {
                tool.executeApproved(arguments)
            } else {
                tool.execute(arguments)
            }
            agentLoop.log(
                "APPROVAL_EXECUTED",
                "tool=$toolName, success=${result.success}, conversation=$conversationId"
            )
            if (result.success) result.output else "Gagal: ${result.error ?: "tanpa detail"}"
        } catch (e: Exception) {
            "Gagal: ${e.message}"
        }
    }

    /**
     * One full agent turn: instant ack bubble → typing indicator → memory context →
     * agent loop → answer (edited into the ack bubble). Also used by media turns with a
     * textual [mediaInfo] description.
     */
    private suspend fun processAgentTurn(
        conversationId: String,
        sender: String,
        content: String,
        messageId: String,
        timestamp: Long,
        mediaInfo: String?
    ) {
        updateModelRouter()

        // Priority 3 — CONFIRM tools are only advertised when approval routing is on.
        toolRegistry.approvalEnabled = _approvalEnabled.value

        var input = AgentInput(
            conversationId = conversationId,
            senderId = sender,
            content = content,
            timestamp = if (timestamp > 0) timestamp * 1000L else System.currentTimeMillis(),
            channel = "whatsapp",
            metadata = buildMap {
                put("source", "whatsmeow")
                if (messageId.isNotBlank()) put("waMessageId", messageId)
                if (mediaInfo != null) put("mediaInfo", mediaInfo)
            }
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
            // Local clock + Priority 2 memory (RAG) + active learnings are folded into the
            // system prompt for this turn only.
            val effectivePrompt = buildEffectivePrompt(agentLoop.agent.systemPrompt, content)
            val result = agentLoop.processInput(
                input = input,
                onProgress = { progress ->
                    // Only edit a bubble that actually exists, and never let a failed edit
                    // of a progress note break the reply itself.
                    if (!ackId.isNullOrBlank()) {
                        scope.launch { gatewayManager.editText(conversationId, ackId, progress) }
                    }
                },
                systemPromptOverride = effectivePrompt
            )
            if (result.isFailure && !ackId.isNullOrBlank()) {
                // Do not leave the placeholder hanging when the agent could not answer.
                gatewayManager.editText(
                    conversationId,
                    ackId,
                    "⚠️ ${result.exceptionOrNull()?.message ?: "Agent tidak dapat memproses pesan ini."}"
                )
            }

            // Priority 2 — auto compact when the session grows past the configured bound.
            if (_autoCompactEnabled.value && result.isSuccess) {
                try {
                    val session = sessionRepository.getOrCreateSession(conversationId)
                    if (session.messageCount > _maxContextMessages.value) {
                        val (provider, modelId) = resolveProviderPair()
                        compactManager.compactIfNeeded(
                            sessionId = session.sessionId,
                            conversationId = conversationId,
                            maxMessages = _maxContextMessages.value,
                            provider = provider,
                            modelId = modelId
                        )
                        agentLoop.log("AUTO_COMPACT", "conversation=$conversationId compacted")
                    }
                } catch (_: Exception) {
                    // Compaction must never break the reply flow.
                }
            }
        } finally {
            typingKeepAlive.cancel()
            gatewayManager.setTyping(conversationId, false)
        }
    }

    // ==================================================================================
    // Per-turn system prompt: local clock + Priority 2 memory context
    // ==================================================================================

    /**
     * Builds the effective system prompt for a single turn:
     *
     *   persona/agent prompt
     *   + current local date & time on the device
     *   + RAG memories relevant to the message + active learnings (Priority 2)
     *
     * The clock block is refreshed every turn and is *always* included — a model cannot know
     * "now" on its own — while the memory/learning sections honour the memory toggle.
     */
    private suspend fun buildEffectivePrompt(basePrompt: String, userMessage: String): String {
        val timedPrompt = basePrompt.trim() + "\n\n" + ContextManager.timeContext()
        // The skill index is cheap (name + description per line) and independent of the memory
        // toggle: knowing which procedures exist is useful even with long-term memory off.
        val skillIndex = skillLibrary.index()
        if (!_longTermMemoryEnabled.value) {
            return ContextManager.buildSystemPrompt(timedPrompt, emptyList(), emptyList(), skillIndex)
        }
        return try {
            val memories = memoryRepository.recall(userMessage, limit = 5)
            val learnings = memoryRepository.getByType(MemoryItemEntity.TYPE_LEARNING)
                .filter { it.status == MemoryItemEntity.STATUS_ACTIVE }
            ContextManager.buildSystemPrompt(timedPrompt, memories, learnings, skillIndex)
        } catch (_: Exception) {
            ContextManager.buildSystemPrompt(timedPrompt, emptyList(), emptyList(), skillIndex)
        }
    }

    /** Reloads the skill list for the UI (the tools read the folder directly on every call). */
    fun refreshSkills() {
        scope.launch { _skills.value = skillLibrary.load() }
    }

    /**
     * Runs due scheduled tasks once. Called by the in-app ticker and by SchedulerWork, so both
     * paths share exactly one implementation of the scheduling rules.
     */
    suspend fun runSchedulerTick() = scheduler.tick()

    // ==================================================================================
    // Auto reflection — periodic self-review through the existing scheduler
    // ==================================================================================

    /**
     * Creates, updates or pauses the scheduled self-reflection task.
     *
     * Reflection used to depend on the model *remembering* to call `reflect`. This reuses the
     * scheduler the app already ships (persistent row, 60 s ticker, results delivered to the chat)
     * instead of inventing a second timer, so a long-running agent actually accumulates lessons.
     * The report goes to the most recent conversation; if no chat exists yet there is nothing to
     * report to, so the task is simply not created.
     */
    private suspend fun syncAutoReflection() {
        val existing = scheduledTaskDao.getAll().firstOrNull { it.name == AUTO_REFLECT_TASK_NAME }
        if (!_autoReflectEnabled.value) {
            if (existing != null && existing.enabled) scheduler.setEnabled(existing.id, false)
            return
        }

        val hours = _autoReflectIntervalHours.value.coerceIn(1, MAX_AUTO_REFLECT_HOURS)
        val schedule = "interval:${hours * 3600L}"
        val conversationId = existing?.conversationId?.takeIf { it.isNotBlank() }
            ?: sessionRepository.getAllSessions().firstOrNull()?.conversationId
            ?: return

        if (existing == null) {
            scheduler.create(AUTO_REFLECT_TASK_NAME, schedule, AUTO_REFLECT_PROMPT, conversationId)
            agentLoop.log("AUTO_REFLECT", "Aktif: refleksi tiap $hours jam ke $conversationId")
            return
        }

        val intervalSeconds = SchedulerEngine.parseIntervalSeconds(schedule) ?: return
        scheduledTaskDao.upsert(
            existing.copy(
                schedule = schedule,
                prompt = AUTO_REFLECT_PROMPT,
                conversationId = conversationId,
                enabled = true,
                nextRunAt = System.currentTimeMillis() + intervalSeconds * 1000
            )
        )
        agentLoop.log("AUTO_REFLECT", "Diperbarui: refleksi tiap $hours jam")
    }

    fun setAutoReflectEnabled(enabled: Boolean) {
        _autoReflectEnabled.value = enabled
        scope.launch { syncAutoReflection() }
        persistConfig()
    }

    fun setAutoReflectIntervalHours(hours: Int) {
        _autoReflectIntervalHours.value = hours.coerceIn(1, MAX_AUTO_REFLECT_HOURS)
        scope.launch { syncAutoReflection() }
        persistConfig()
    }

    /** Runs the self-reflection immediately (same task the scheduler would run). */
    fun runAutoReflectionNow() = scope.launch {
        syncAutoReflection()
        val task = scheduledTaskDao.getAll().firstOrNull { it.name == AUTO_REFLECT_TASK_NAME }
        if (task == null) {
            agentLoop.log("AUTO_REFLECT", "Belum ada task refleksi (aktifkan dulu atau mulai satu chat).")
            return@launch
        }
        scheduler.runNow(task.id)
    }

    // ==================================================================================
    // Priority 5 — incoming WhatsApp MEDIA
    // ==================================================================================

    private fun handleIncomingMedia(media: WaMediaMessage) {
        if (!_isAutoReplyEnabled.value) return
        if (media.isGroup) return

        val contactId = agentIdFromJid(media.chat)
        scope.launch {
            val access = contactAccess.getAccess(contactId, _whitelistMode.value)
            if (access.decision == ContactAccessRepository.Decision.BLOCK) {
                val ruleLabel = access.rule?.mode ?: "none"
                agentLoop.log(
                    "CONTACT_BLOCKED",
                    "Media dari '$contactId' diblokir (rule=$ruleLabel, " +
                        "whitelistMode=${_whitelistMode.value})."
                )
                if (access.rule == null) {
                    notifyNotWhitelisted(media.chat.ifBlank { media.sender }, contactId)
                }
                return@launch
            }

            val conversationId = media.chat.ifBlank { media.sender }

            // Instant confirmation per the requested UX: tell the user the media agent
            // is on it, results follow in this same chat.
            gatewayManager.sendText(
                conversationId,
                "📎 Media diterima (${media.mediaType}). Tunggu sebentar, saya analisis dulu ya…"
            )

            val description = analyzeMedia(media)
            val prompt = buildString {
                append("Pengguna mengirim ")
                append(media.mediaType)
                if (media.mimetype.isNotBlank()) append(" (${media.mimetype})")
                append(".\nHasil analisis media oleh sub-agent:\n")
                append(description)
                if (media.caption.isNotBlank()) {
                    append("\n\nCaption dari pengguna: \"").append(media.caption).append("\"")
                }
                append("\n\nBalas pengguna berdasarkan hasil analisis ini.")
            }

            processAgentTurn(
                conversationId = conversationId,
                sender = media.sender,
                content = prompt,
                messageId = media.messageId,
                timestamp = media.timestamp,
                mediaInfo = "${media.mediaType}:${media.mimetype}"
            )
        }
    }

    /**
     * Downloads the media and runs the vision subagent. When no vision provider is
     * configured, documents are still readable as text; images/video/audio honestly
     * report that vision is not configured (no fake understanding).
     */
    private suspend fun analyzeMedia(media: WaMediaMessage): String = withContext(Dispatchers.IO) {
        // The download result is kept so the failure reason reaches the user instead of a
        // silent "(Gagal mengunduh media dari WhatsApp.)" with no way to tell what broke.
        val download = try {
            gatewayManager.downloadMedia(media.payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
        val data = download.getOrNull()
        if (data == null) {
            val reason = download.exceptionOrNull()?.message ?: "penyebab tidak diketahui"
            agentLoop.log(
                "MEDIA_DOWNLOAD_FAILED",
                "type=${media.mediaType}, mimetype=${media.mimetype}, error=$reason"
            )
            return@withContext "(Gagal mengunduh ${media.mediaType} dari WhatsApp: $reason. Coba kirim ulang file-nya.)"
        }

        // Documents: read as text directly (best effort, no vision needed).
        if (media.mediaType == "document" && media.mimetype.startsWith("text/")) {
            val text = data.toString(Charsets.UTF_8).take(12_000)
            return@withContext "Isi dokumen \"${media.filename}\":\n$text"
        }

        // A photo or video shared "as a file" arrives as a document with an image/video MIME
        // type; route it by MIME type so vision still runs instead of reporting "not
        // supported" for media the agent can actually read.
        val effectiveType = when {
            media.mediaType != "document" -> media.mediaType
            media.mimetype.startsWith("image/") -> "image"
            media.mimetype.startsWith("video/") -> "video"
            else -> "document"
        }

        if (effectiveType != "image" && effectiveType != "video") {
            return@withContext if (media.mediaType == "document") {
                val documentType = media.mimetype.ifBlank { "tanpa tipe" }
                "(Dokumen $documentType diterima, tapi agent belum bisa " +
                    "membaca format ini. Kirim isinya sebagai teks, atau screenshot halamannya " +
                    "kalau berupa gambar.)"
            } else {
                "(Media ${media.mediaType} diterima; analisis otomatis untuk tipe ini belum tersedia.)"
            }
        }

        // Video bytes go to the model inline (base64), so an oversized clip would fail with
        // an unhelpful provider error; refuse honestly instead.
        if (effectiveType == "video" && data.size > MAX_INLINE_VIDEO_BYTES) {
            return@withContext "(Video ${data.size / (1024 * 1024)} MB terlalu besar untuk dianalisis " +
                "langsung (batas ${MAX_INLINE_VIDEO_BYTES / (1024 * 1024)} MB). Kirim klip yang lebih " +
                "pendek atau screenshot bagian pentingnya.)"
        }

        // Vision runs on the bytes downloaded above; video is sent inline (base64), which is
        // why the size gate above already refused anything too large to send.
        val target = visionTarget()
            ?: return@withContext "(Belum ada model yang bisa melihat ${effectiveType} ini. Isi API key " +
                "vision di Pengaturan, atau pakai provider utama yang modelnya mendukung gambar.)"

        val prompt = if (media.caption.isNotBlank()) {
            "Jelaskan ${effectiveType} ini secara ringkas dan jawab kebutuhan pengguna. Caption pengguna: \"${media.caption}\""
        } else {
            "Jelaskan ${effectiveType} ini secara ringkas: apa isinya, objek/teks penting, dan kesimpulannya."
        }

        try {
            withTimeout(90_000L) {
                target.provider.describeImage(
                    com.example.agent.provider.ImageUnderstandingRequest(
                        prompt = prompt,
                        imagesBase64 = listOf(Base64.encodeToString(data, Base64.NO_WRAP)),
                        mimeType = media.mimetype.ifBlank { "image/jpeg" },
                        modelId = target.modelId
                    )
                ).getOrElse { e ->
                    "(Analisis ${effectiveType} gagal: ${e.message})"
                }
            }
        } catch (e: Exception) {
            "(Analisis ${effectiveType} gagal: ${e.message})"
        }
    }

    // ==================================================================================
    // Priority 6 — subagent execution + result delivery
    // ==================================================================================

    /**
     * Runs one subagent turn synchronously (called on the subagent's background
     * coroutine). Uses the same model router but the subagent's persona prompt.
     */
    private suspend fun runSubAgentTurn(task: AgentTaskEntity): String {
        val persona = task.systemPrompt.ifBlank { SubAgentManager.DEFAULT_PERSONA }
        val subLoop = AgentLoop(
            agent = agentLoop.agent.copy(
                systemPrompt = persona,
                toolsEnabled = task.toolsEnabled
            ),
            modelProvider = openAiProvider,
            sessionRepository = sessionRepository,
            modelRouter = modelRouter,
            toolRegistry = if (task.toolsEnabled) toolRegistry else null
        )
        val result = subLoop.processInput(
            input = AgentInput(
                conversationId = "subagent-${task.id}",
                senderId = "main-agent",
                content = task.task,
                channel = "internal_subagent"
            ),
            // Per-turn persona: the subagent prompt never leaks into other conversations.
            systemPromptOverride = persona
        )
        return result.fold(
            onSuccess = { it.content },
            onFailure = { throw IllegalStateException(it.message ?: "Sub-agent gagal") }
        )
    }

    /** Pushes a finished subagent result into the requesting chat as a new message. */
    private suspend fun deliverSubAgentResult(task: AgentTaskEntity, result: com.example.agent.subagent.TaskResult) {
        val chat = task.conversationId
        if (chat.isBlank()) return
        val message = if (result.status == AgentTaskEntity.STATUS_COMPLETED) {
            "🤖 Sub-agent '${task.name}' selesai:\n\n${result.summary}"
        } else {
            "⚠️ Sub-agent '${task.name}' gagal: ${result.error ?: "tanpa detail"}"
        }
        try {
            gatewayManager.sendText(chat, message)
        } catch (_: Exception) {
            agentLoop.log("SUBAGENT_DELIVERY_ERROR", "task=${task.id} chat=$chat")
        }
    }

    /**
     * Council: two bounded personas debate [topic], the moderator synthesizes.
     * Synchronous by design (the main agent asked for a debate) but strictly bounded —
     * two subagents, one round each, short answers.
     */
    private suspend fun runCouncil(topic: String, conversationId: String): String {
        val supporter = runBlockingPersona(
            name = "Pendukung",
            persona = "Kamu adalah debater PENDUKUNG. Berikan 3 argumen terkuat MENDUKUNG gagasan berikut, ringkas (maks 120 kata).",
            topic = topic
        )
        val critic = runBlockingPersona(
            name = "Kritikus",
            persona = "Kamu adalah debater KRITIKUS. Berikan 3 argumen terkuat MENOLAK atau melemahkan gagasan berikut, ringkas (maks 120 kata).",
            topic = topic
        )
        val synthesis = runBlockingPersona(
            name = "Moderator",
            persona = "Kamu adalah moderator netral. Rangkum debat berikut menjadi rekomendasi akhir yang seimbang (maks 150 kata).",
            topic = "Gagasan: $topic\n\nArgumen PENDUKUNG:\n$supporter\n\nArgumen KRITIKUS:\n$critic"
        )
        return "🏛️ Council tentang: \"$topic\"\n\n" +
            "• Pendukung: $supporter\n\n" +
            "• Kritikus: $critic\n\n" +
            "• Sintesis moderator: $synthesis"
    }

    /** One bounded persona turn through the primary model. */
    private suspend fun runBlockingPersona(name: String, persona: String, topic: String): String {
        return try {
            withTimeout(120_000L) {
                val subLoop = AgentLoop(
                    agent = agentLoop.agent.copy(systemPrompt = persona),
                    modelProvider = openAiProvider,
                    sessionRepository = sessionRepository,
                    modelRouter = modelRouter
                )
                subLoop.processInput(
                    input = AgentInput(
                        conversationId = "council-$name",
                        senderId = "main-agent",
                        content = topic,
                        channel = "internal_subagent"
                    ),
                    systemPromptOverride = persona
                ).fold(
                    onSuccess = { it.content },
                    onFailure = { "(gagal: ${it.message})" }
                )
            }
        } catch (e: Exception) {
            "(gagal: ${e.message})"
        }
    }

    // ==================================================================================
    // Priority 4 — scheduled task execution
    // ==================================================================================

    /** Runs a scheduled task prompt through the agent loop and delivers the answer. */
    private suspend fun executeScheduledTask(task: com.example.agent.storage.entity.ScheduledTaskEntity): String {
        val answer = agentLoop.processInput(
            AgentInput(
                conversationId = task.conversationId,
                senderId = "scheduler",
                content = task.prompt,
                channel = "whatsapp",
                metadata = mapOf("source" to "scheduler", "scheduledTaskId" to task.id)
            )
        ).fold(
            onSuccess = { it.content },
            onFailure = { throw IllegalStateException(it.message ?: "Task gagal") }
        )
        // The channel adapter inside processInput already sent the reply via WhatsApp
        // (channel = "whatsapp"); the returned text is recorded on the task row.
        return answer
    }

    // ==================================================================================
    // UI surface — access control, memory, approvals, tasks, vision
    // ==================================================================================

    // --- Terminal & browser surfaces for the UI ----------------------------------------

    /** Live terminal state (output lines, cwd, busy, exit code) for the Terminal screen. */
    val terminalState: StateFlow<com.example.agent.terminal.TerminalState> = terminalManager.state

    /** Device toolchain report (which binaries exist) for the Terminal screen header. */
    val terminalCapabilities =
        MutableStateFlow<Map<String, String>>(emptyMap())

    /** A captcha/2FA step the user has to finish by hand in the Browser screen. */
    val browserPendingUserAction = browserAutomation.pendingUserAction

    suspend fun refreshTerminalCapabilities(force: Boolean = false) {
        terminalCapabilities.value = terminalManager.loadCapabilities(force = force)
    }

    /** Runs a command in the persistent terminal session (Terminal screen + /terminal). */
    suspend fun runInTerminal(command: String): Boolean = terminalManager.send(command)

    fun clearTerminal() = terminalManager.clear()

    fun stopTerminal() = terminalManager.stop()

    /** The live WebView, so the Browser screen can show the session the agent is driving. */
    fun browserView(): android.webkit.WebView? = browserEngine.viewOrNull()

    /** Background subagent runs, newest first (Tasks screen). */
    val subAgentTasksFlow: kotlinx.coroutines.flow.Flow<List<AgentTaskEntity>> =
        agentTaskDao.getRecentFlow(50)

    /** Scheduled (cron) tasks, newest first (Tasks screen). */
    val scheduledTasksFlow: kotlinx.coroutines.flow.Flow<List<com.example.agent.storage.entity.ScheduledTaskEntity>> =
        scheduledTaskDao.getAllFlow()

    fun allowContact(contactId: String, label: String? = null) =
        scope.launch { contactAccess.allow(contactId, label) }

    fun blockContact(contactId: String, label: String? = null) =
        scope.launch { contactAccess.block(contactId, label) }

    fun removeContactRule(contactId: String) =
        scope.launch { contactAccess.remove(contactId) }

    /** Manual memory entry from the UI (same path as the `/remember` chat command). */
    fun rememberManually(content: String, type: String = MemoryItemEntity.TYPE_KNOWLEDGE) {
        if (content.isBlank()) return
        scope.launch {
            memoryRepository.remember(
                content = content,
                type = type,
                source = MemoryItemEntity.SOURCE_MANUAL
            )
        }
    }

    fun deleteMemory(id: String) = scope.launch { memoryRepository.delete(id) }

    fun promoteLearning(id: String) = scope.launch { memoryRepository.promoteLearning(id) }

    fun rejectLearning(id: String) = scope.launch { memoryRepository.rejectLearning(id) }

    /**
     * UI counterpart of `/approve` and `/reject`: resolves [id] and, when approved, runs the
     * parked destructive tool call. Returns a message the UI can show.
     */
    suspend fun resolveApproval(id: String, approved: Boolean): String {
        val request = approvalCoordinator.resolve(id, approved)
            ?: return "Permintaan \"$id\" tidak ditemukan, sudah diputuskan, atau kedaluwarsa."
        if (!approved) {
            return "Permintaan ${request.toolName} ($id) ditolak. Tidak ada yang dijalankan."
        }
        val output = try {
            executeApprovedToolCall(request.toolName, request.arguments, request.conversationId)
        } catch (e: Exception) {
            "Gagal: ${e.message}"
        }
        return "${request.toolName} ($id) dijalankan: $output"
    }

    fun runScheduledTaskNow(id: String) = scope.launch { scheduler.runNow(id) }

    fun setScheduledTaskEnabled(id: String, enabled: Boolean) =
        scope.launch { scheduler.setEnabled(id, enabled) }

    fun deleteScheduledTask(id: String) = scope.launch { scheduler.delete(id) }

    // ==================================================================================
    // Direct chat (app UI)
    // ==================================================================================

    suspend fun directChat(conversationId: String, prompt: String): Result<String> {
        updateModelRouter()
        val wasEnabled = agentLoop.agent.enabled
        if (!wasEnabled) agentLoop.agent = agentLoop.agent.copy(enabled = true)

        return try {
            // Local clock + memory-aware prompt is scoped to this turn via systemPromptOverride,
            // so two concurrent chats can never see each other's recalled memories.
            val effectivePrompt = buildEffectivePrompt(agentLoop.agent.systemPrompt, prompt)
            val input = AgentInput(
                conversationId = conversationId,
                senderId = "user",
                content = prompt,
                channel = "internal_chat",
                metadata = mapOf("source" to "chat_ui")
            )
            withContext(ToolConversation(conversationId)) {
                agentLoop.processInput(input, systemPromptOverride = effectivePrompt)
            }.map { it.content }
        } finally {
            if (!wasEnabled) agentLoop.agent = agentLoop.agent.copy(enabled = false)
        }
    }

    suspend fun testChat(prompt: String): Result<String> {
        return directChat(conversationId = "test-console", prompt = prompt)
    }

    companion object {
        /** 16 MB — WhatsApp's own practical limit for a single document send. */
        private const val MAX_SEND_BYTES = 16 * 1024 * 1024

        private val BROWSER_TOOL_NAMES = listOf(
            "browser_open",
            "browser_read",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_screenshot",
            "browser_login",
            "browser_ask_user",
            "browser_logout"
        )

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
