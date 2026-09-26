package com.example.wagateway

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.bridge.WhatsAppAgentBridge
import com.example.agent.browser.BrowserAutomationManager
import com.example.agent.browser.SiteCredential
import com.example.agent.loop.AgentLoop
import com.example.agent.skills.Skill
import com.example.agent.terminal.TerminalState
import com.example.agent.loop.AgentState
import com.example.agent.loop.isBusy
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentSession
import com.example.agent.model.Tool
import com.example.agent.provider.ProviderKeyPool
import com.example.agent.storage.ContactAccessRepository
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.McpServerEntity
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.storage.entity.ScheduledTaskEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class WaGatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val gatewayManager = WaGatewayManager.getInstance(application)
    private val agentBridge = WhatsAppAgentBridge.getInstance(application)

    val connectionStatus: StateFlow<String> = gatewayManager.connectionStatus
    val qrCode: StateFlow<String?> = gatewayManager.qrCode
    val pairingCode: StateFlow<String?> = gatewayManager.pairingCode
    val isLoggedIn: StateFlow<Boolean> = gatewayManager.isLoggedIn
    val isConnected: StateFlow<Boolean> = gatewayManager.isConnected
    val messages: StateFlow<List<WaMessage>> = gatewayManager.messages
    val logs: StateFlow<List<String>> = gatewayManager.logs

    val pairingPhone = MutableStateFlow("")
    val pairingMode = MutableStateFlow(PairingMode.QR)

    private val _isRequestingCode = MutableStateFlow(false)
    val isRequestingCode: StateFlow<Boolean> = _isRequestingCode.asStateFlow()

    val targetPhone = MutableStateFlow("")
    val messageText = MutableStateFlow("")

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendFeedback = MutableStateFlow<String?>(null)
    val sendFeedback: StateFlow<String?> = _sendFeedback.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // Agent Core States
    val isAgentAutoReply: StateFlow<Boolean> = agentBridge.isAutoReplyEnabled
    val agentState: StateFlow<AgentState> = agentBridge.agentLoop.state
    val agentLastError: StateFlow<String?> = agentBridge.agentLoop.lastError
    val agentLogs: StateFlow<List<String>> = agentBridge.agentLoop.activityLogs

    /** Last model calls with latency + tokens (Developer panel). */
    val modelMetrics: StateFlow<List<AgentLoop.ModelCallMetric>> = agentBridge.modelMetrics

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()
    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    /** Markdown skills in the workspace (files under the skills folder). */
    val skills: StateFlow<List<Skill>> = agentBridge.skills

    /** Periodic self-reflection settings (executed by the scheduler). */
    val autoReflectEnabled: StateFlow<Boolean> = agentBridge.autoReflectEnabled
    val autoReflectIntervalHours: StateFlow<Int> = agentBridge.autoReflectIntervalHours

    /** MCP servers + their discovery status. */
    val mcpServers: StateFlow<List<McpServerEntity>> = agentBridge.mcpManager.servers
    val mcpStatuses: StateFlow<Map<String, String>> = agentBridge.mcpManager.statuses
    val mcpNameInput = MutableStateFlow("")
    val mcpUrlInput = MutableStateFlow("")
    val mcpHeadersInput = MutableStateFlow("")

    /** Probes the configured model and reports availability + latency. */
    fun probeModel() {
        if (_isProbing.value) return
        viewModelScope.launch {
            _isProbing.value = true
            _probeResult.value = "Menghubungi model…"
            try {
                val probe = agentBridge.probeCurrentModel()
                _probeResult.value = if (probe.available) {
                    "✅ ${agentBridge.providerConfig.value.modelId} siap — ${probe.latencyMs} ms"
                } else {
                    "❌ ${probe.error ?: "model tidak merespons"} (${probe.latencyMs} ms)"
                }
            } catch (e: Exception) {
                _probeResult.value = "❌ Probe gagal: ${e.message}"
            } finally {
                _isProbing.value = false
            }
        }
    }

    fun refreshSkills() = agentBridge.refreshSkills()

    fun setAutoReflectEnabled(enabled: Boolean) = agentBridge.setAutoReflectEnabled(enabled)

    fun setAutoReflectIntervalHours(hours: Int) = agentBridge.setAutoReflectIntervalHours(hours)

    fun runAutoReflectionNow() {
        agentBridge.runAutoReflectionNow()
        _sendFeedback.value = "Refleksi otomatis dijalankan; hasilnya dikirim ke chat terakhir."
    }

    fun addMcpServer() {
        val name = mcpNameInput.value
        val url = mcpUrlInput.value
        val headers = mcpHeadersInput.value
        viewModelScope.launch {
            _sendFeedback.value = "Menghubungi MCP server…"
            val message = agentBridge.addMcpServer(name, url, headers)
            _sendFeedback.value = message
            if (!message.contains("Gagal") && !message.contains("wajib") && !message.contains("harus")) {
                mcpNameInput.value = ""
                mcpUrlInput.value = ""
                mcpHeadersInput.value = ""
            }
        }
    }

    fun removeMcpServer(id: String) {
        viewModelScope.launch {
            agentBridge.removeMcpServer(id)
            _sendFeedback.value = "MCP server dihapus."
        }
    }

    fun setMcpServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { agentBridge.setMcpServerEnabled(id, enabled) }
    }

    fun refreshMcpTools() {
        viewModelScope.launch { _sendFeedback.value = agentBridge.refreshMcpTools() }
    }
    val agentCurrentActivity: StateFlow<String?> = agentBridge.agentLoop.currentActivity

    /** Phase 6: tools registered on the Agent Loop, shown on the Developer screen. */
    val agentTools: List<Tool> = agentBridge.toolRegistry.all()

    /** Phase 7: sandbox root that the file tools can never leave. */
    val agentWorkspacePath: String = agentBridge.workspace.rootPath
    val agentSystemPrompt = MutableStateFlow(agentBridge.systemPrompt.value)
    val agentBaseUrl = MutableStateFlow(agentBridge.providerConfig.value.baseUrl)
    val agentApiKey = MutableStateFlow(agentBridge.providerConfig.value.apiKey)
    /** Optional keys pool: one extra API key per line, rotated on rate limit/quota. */
    val agentApiKeyPool = MutableStateFlow(agentBridge.apiKeyPoolText)
    val agentModelId = MutableStateFlow(agentBridge.providerConfig.value.modelId)
    val useEchoFallback: StateFlow<Boolean> = agentBridge.useEchoFallback

    // ==================================================================================
    // Priority 1 — contact access control (whitelist / blacklist)
    // ==================================================================================
    val whitelistMode: StateFlow<Boolean> = agentBridge.whitelistMode

    val whitelistContacts: StateFlow<List<ContactRuleEntity>> = agentBridge.contactAccess.whitelistFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val blacklistContacts: StateFlow<List<ContactRuleEntity>> = agentBridge.contactAccess.blacklistFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val contactNumberInput = MutableStateFlow("")
    val contactLabelInput = MutableStateFlow("")

    fun setWhitelistMode(enabled: Boolean) = agentBridge.setWhitelistMode(enabled)

    fun addWhitelistContact() = addContactRule(ContactRuleEntity.MODE_ALLOW)

    fun addBlacklistContact() = addContactRule(ContactRuleEntity.MODE_BLOCK)

    private fun addContactRule(mode: String) {
        val number = ContactAccessRepository.normalizePhone(contactNumberInput.value)
        if (number.length < 6) {
            _sendFeedback.value = "Nomor tidak valid. Gunakan format internasional, contoh 628123456789."
            return
        }
        val label = contactLabelInput.value.trim().ifBlank { null }
        if (mode == ContactRuleEntity.MODE_ALLOW) {
            agentBridge.allowContact(number, label)
            _sendFeedback.value = "$number ditambahkan ke whitelist."
        } else {
            agentBridge.blockContact(number, label)
            _sendFeedback.value = "$number diblokir dari agent."
        }
        contactNumberInput.value = ""
        contactLabelInput.value = ""
    }

    fun removeContactRule(contactId: String) {
        agentBridge.removeContactRule(contactId)
        _sendFeedback.value = "$contactId dihapus dari daftar."
    }

    // ==================================================================================
    // Priority 2 — long-term memory (episodic / knowledge / learning)
    // ==================================================================================
    val longTermMemoryEnabled: StateFlow<Boolean> = agentBridge.longTermMemoryEnabled
    val autoCompactEnabled: StateFlow<Boolean> = agentBridge.autoCompactEnabled
    val maxContextMessages: StateFlow<Int> = agentBridge.maxContextMessages

    val episodicMemories: StateFlow<List<MemoryItemEntity>> = agentBridge.memoryRepository
        .getByTypeFlow(MemoryItemEntity.TYPE_EPISODIC)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledgeMemories: StateFlow<List<MemoryItemEntity>> = agentBridge.memoryRepository
        .getByTypeFlow(MemoryItemEntity.TYPE_KNOWLEDGE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val learnings: StateFlow<List<MemoryItemEntity>> = agentBridge.memoryRepository
        .getByTypeFlow(MemoryItemEntity.TYPE_LEARNING)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val newMemoryInput = MutableStateFlow("")

    fun setLongTermMemoryEnabled(enabled: Boolean) = agentBridge.setLongTermMemoryEnabled(enabled)

    fun setAutoCompactEnabled(enabled: Boolean) = agentBridge.setAutoCompactEnabled(enabled)

    fun setMaxContextMessages(count: Int) = agentBridge.setMaxContextMessages(count)

    fun saveMemory() {
        val content = newMemoryInput.value.trim()
        if (content.isEmpty()) {
            _sendFeedback.value = "Isi memori masih kosong."
            return
        }
        agentBridge.rememberManually(content)
        newMemoryInput.value = ""
        _sendFeedback.value = "Memori tersimpan."
    }

    fun deleteMemory(id: String) = agentBridge.deleteMemory(id)

    fun promoteLearning(id: String) = agentBridge.promoteLearning(id)

    fun rejectLearning(id: String) = agentBridge.rejectLearning(id)

    // ==================================================================================
    // Priority 3 — approvals for destructive tools
    // ==================================================================================
    val approvalEnabled: StateFlow<Boolean> = agentBridge.approvalEnabled

    val pendingApprovals: StateFlow<List<ApprovalRequestEntity>> = agentBridge.pendingApprovals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setApprovalEnabled(enabled: Boolean) = agentBridge.setApprovalEnabled(enabled)

    fun approveRequest(id: String) {
        viewModelScope.launch {
            _sendFeedback.value = agentBridge.resolveApproval(id, approved = true)
        }
    }

    fun rejectRequest(id: String) {
        viewModelScope.launch {
            _sendFeedback.value = agentBridge.resolveApproval(id, approved = false)
        }
    }

    // ==================================================================================
    // Priorities 4 & 6 — scheduled tasks and background subagents
    // ==================================================================================
    val subAgentTasks: StateFlow<List<AgentTaskEntity>> = agentBridge.subAgentTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scheduledTasks: StateFlow<List<ScheduledTaskEntity>> = agentBridge.scheduledTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun runScheduledTaskNow(id: String) = agentBridge.runScheduledTaskNow(id)

    fun setScheduledTaskEnabled(id: String, enabled: Boolean) =
        agentBridge.setScheduledTaskEnabled(id, enabled)

    fun deleteScheduledTask(id: String) = agentBridge.deleteScheduledTask(id)

    // ==================================================================================
    // Priority 5 — vision subagent configuration
    // ==================================================================================
    val visionBaseUrl = MutableStateFlow(agentBridge.visionConfig.value.baseUrl)
    val visionApiKey = MutableStateFlow(agentBridge.visionConfig.value.apiKey)
    val visionModelId = MutableStateFlow(agentBridge.visionConfig.value.modelId)
    val visionGeminiNative = MutableStateFlow(agentBridge.visionConfig.value.isGeminiNative)

    fun saveVisionSettings() {
        agentBridge.setVisionConfig(
            baseUrl = visionBaseUrl.value,
            apiKey = visionApiKey.value,
            modelId = visionModelId.value,
            geminiNative = visionGeminiNative.value
        )
        _sendFeedback.value = "Konfigurasi vision tersimpan."
    }

    // Phase 2: Session & Persistence
    val sessions: StateFlow<List<AgentSession>> = agentBridge.sessionRepository.getAllSessionsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val selectedSessionId = MutableStateFlow<String?>(null)

    val selectedSessionMessages: StateFlow<List<AgentMessage>> = selectedSessionId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                agentBridge.sessionRepository.getMessagesFlow(id)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val chatInputText = MutableStateFlow("")

    val testPromptText = MutableStateFlow("Halo Agent, apa fungsi kamu?")
    private val _testResponseText = MutableStateFlow<String?>(null)
    val testResponseText: StateFlow<String?> = _testResponseText.asStateFlow()
    private val _isTestingAgent = MutableStateFlow(false)
    val isTestingAgent: StateFlow<Boolean> = _isTestingAgent.asStateFlow()

    init {
        // Sync configuration fields when loaded from Room storage
        viewModelScope.launch {
            agentBridge.providerConfig.collect { config ->
                agentBaseUrl.value = config.baseUrl
                agentApiKey.value = config.apiKey
                agentApiKeyPool.value = ProviderKeyPool.format(config.apiKeys)
                agentModelId.value = config.modelId
            }
        }
        viewModelScope.launch {
            agentBridge.systemPrompt.collect { prompt ->
                agentSystemPrompt.value = prompt
            }
        }
        viewModelScope.launch {
            agentBridge.visionConfig.collect { vision ->
                visionBaseUrl.value = vision.baseUrl
                visionApiKey.value = vision.apiKey
                visionModelId.value = vision.modelId
                visionGeminiNative.value = vision.isGeminiNative
            }
        }
    }

    fun connect() {
        gatewayManager.connect()
    }

    fun disconnect() {
        gatewayManager.disconnect()
    }

    fun logout() {
        gatewayManager.logout()
    }

    /** Unlinks the stored session so the device can be paired again from scratch. */
    fun resetSession() {
        gatewayManager.resetSession()
    }

    fun sendTextMessage() {
        val target = targetPhone.value.trim()
        val text = messageText.value.trim()

        if (target.isBlank()) {
            _sendFeedback.value = "Please enter target phone number"
            return
        }
        if (text.isBlank()) {
            _sendFeedback.value = "Please enter message text"
            return
        }

        viewModelScope.launch {
            _isSending.value = true
            _sendFeedback.value = null
            val result = gatewayManager.sendText(target, text)
            _isSending.value = false
            if (result.isSuccess) {
                _sendFeedback.value = "Message sent successfully!"
                messageText.value = ""
            } else {
                _sendFeedback.value = "Send error: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun requestPairingCode() {
        val phone = pairingPhone.value.trim()
        if (phone.isBlank()) {
            _sendFeedback.value = "Masukkan nomor WhatsApp dengan kode negara (contoh: 62812...)"
            return
        }

        viewModelScope.launch {
            _isRequestingCode.value = true
            _sendFeedback.value = null
            val result = gatewayManager.requestPairingCode(phone)
            _isRequestingCode.value = false
            if (result.isSuccess) {
                _sendFeedback.value = "Kode pairing berhasil didapatkan! Masukkan kode ini di WhatsApp HP Anda."
            } else {
                _sendFeedback.value = "Gagal meminta kode pairing: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun setPairingMode(mode: PairingMode) {
        pairingMode.value = mode
    }

    fun toggleService() {
        val newState = !_isServiceRunning.value
        _isServiceRunning.value = newState
        if (newState) {
            WaGatewayService.start(getApplication())
        } else {
            WaGatewayService.stop(getApplication())
        }
    }

    fun clearFeedback() {
        _sendFeedback.value = null
    }

    fun toggleAgentAutoReply() {
        agentBridge.setAutoReplyEnabled(!isAgentAutoReply.value)
    }

    fun setAgentAutoReply(enabled: Boolean) {
        agentBridge.setAutoReplyEnabled(enabled)
    }

    fun setUseEchoFallback(useEcho: Boolean) {
        agentBridge.setUseEchoFallback(useEcho)
    }

    fun saveAgentSettings() {
        agentBridge.updateConfig(
            baseUrl = agentBaseUrl.value,
            apiKey = agentApiKey.value,
            modelId = agentModelId.value,
            prompt = agentSystemPrompt.value,
            apiKeyPool = agentApiKeyPool.value
        )
        val poolSize = agentBridge.apiKeyPoolSize
        _sendFeedback.value = if (poolSize > 1) {
            "Pengaturan Agent disimpan. Keys pool aktif: $poolSize kunci akan dirotasi."
        } else {
            "Pengaturan Agent berhasil disimpan ke Room Database!"
        }
    }

    fun selectSession(sessionId: String?) {
        selectedSessionId.value = sessionId
    }

    fun clearSessionHistory(sessionId: String) {
        viewModelScope.launch {
            agentBridge.sessionRepository.clearSession(sessionId)
            _sendFeedback.value = "Riwayat percakapan sesi berhasil dibersihkan!"
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            agentBridge.sessionRepository.deleteSession(sessionId)
            if (selectedSessionId.value == sessionId) {
                selectedSessionId.value = null
            }
            _sendFeedback.value = "Sesi percakapan berhasil dihapus!"
        }
    }

    fun sendDirectChatMessage(textToSend: String? = null) {
        if (_isTestingAgent.value || agentState.value.isBusy) return
        val text = (textToSend ?: chatInputText.value).trim()
        if (text.isEmpty()) return
        chatInputText.value = ""

        viewModelScope.launch {
            _isTestingAgent.value = true
            val currentSession = sessions.value.find { it.sessionId == selectedSessionId.value }
            val convId = currentSession?.conversationId ?: "personal-agent"
            val result = agentBridge.directChat(convId, text)
            _isTestingAgent.value = false

            if (selectedSessionId.value == null) {
                val session = agentBridge.sessionRepository.getOrCreateSession(convId)
                selectedSessionId.value = session.sessionId
            }

            if (result.isFailure) {
                _sendFeedback.value = "Agent mengalami kendala saat memproses pesan: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun startNewConversation(convId: String = "chat-" + System.currentTimeMillis().toString().takeLast(6)) {
        viewModelScope.launch {
            val session = agentBridge.sessionRepository.getOrCreateSession(convId)
            selectedSessionId.value = session.sessionId
        }
    }

    fun testAgentLoop() {
        if (_isTestingAgent.value || agentState.value.isBusy) return
        val prompt = testPromptText.value.trim()
        if (prompt.isEmpty()) return

        viewModelScope.launch {
            _isTestingAgent.value = true
            _testResponseText.value = null
            saveAgentSettings()
            val result = agentBridge.testChat(prompt)
            _isTestingAgent.value = false
            if (result.isSuccess) {
                _testResponseText.value = result.getOrThrow()
            } else {
                _testResponseText.value = "Agent mengalami error: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    // ==================================================================================
    // Built-in terminal (agent: curl/wget/bash/python; user: interactive shell)
    // ==================================================================================
    val terminalEnabled: StateFlow<Boolean> = agentBridge.terminalEnabled
    val terminalState: StateFlow<TerminalState> = agentBridge.terminalState
    val terminalCapabilities: StateFlow<Map<String, String>> = agentBridge.terminalCapabilities
    val terminalInput = MutableStateFlow("")

    fun setTerminalEnabled(enabled: Boolean) = agentBridge.setTerminalEnabled(enabled)

    fun sendTerminalCommand() {
        val command = terminalInput.value.trim()
        if (command.isEmpty()) return
        terminalInput.value = ""
        viewModelScope.launch { agentBridge.runInTerminal(command) }
    }

    fun clearTerminal() = agentBridge.clearTerminal()

    fun stopTerminal() = agentBridge.stopTerminal()

    fun refreshTerminalCapabilities() {
        viewModelScope.launch { agentBridge.refreshTerminalCapabilities(force = true) }
    }

    // ==================================================================================
    // Browser automation (with the captcha / 2FA handoff)
    // ==================================================================================
    val browserEnabled: StateFlow<Boolean> = agentBridge.browserEnabled
    val browserSites: StateFlow<List<SiteCredential>> = agentBridge.browserSites
    val browserPendingUserAction: StateFlow<BrowserAutomationManager.UserActionRequest?> =
        agentBridge.browserPendingUserAction

    val browserSiteInput = MutableStateFlow("")
    val browserLoginUrlInput = MutableStateFlow("")
    val browserUsernameInput = MutableStateFlow("")
    val browserPasswordInput = MutableStateFlow("")
    val browserUserAgentInput = MutableStateFlow(agentBridge.browserUserAgentValue())

    fun setBrowserEnabled(enabled: Boolean) = agentBridge.setBrowserEnabled(enabled)

    fun saveBrowserUserAgent() {
        agentBridge.setBrowserUserAgent(browserUserAgentInput.value)
        _sendFeedback.value = "User-Agent browser disimpan."
    }

    fun addBrowserSite() {
        val site = browserSiteInput.value.trim()
        if (site.isEmpty()) {
            _sendFeedback.value = "Nama situs wajib diisi (contoh: instagram)."
            return
        }
        agentBridge.addBrowserSite(
            site = site,
            loginUrl = browserLoginUrlInput.value,
            username = browserUsernameInput.value,
            password = browserPasswordInput.value
        )
        browserSiteInput.value = ""
        browserLoginUrlInput.value = ""
        browserUsernameInput.value = ""
        browserPasswordInput.value = ""
        _sendFeedback.value = "Akun $site disimpan (password terenkripsi)."
    }

    fun removeBrowserSite(site: String) = agentBridge.removeBrowserSite(site)

    /** The Browser screen attaches the live WebView; this keeps the engine's context in sync. */
    fun bindBrowserHostContext(context: Context?) {
        agentBridge.browserHostContext = context ?: getApplication()
    }

    fun browserView(): WebView? = agentBridge.browserView()

    fun completeBrowserUserAction() = agentBridge.browserAutomation.completeUserAction()

    fun abandonBrowserUserAction() = agentBridge.browserAutomation.abandonUserAction()

    fun clearBrowserSession() {
        viewModelScope.launch { agentBridge.browserAutomation.clearSession() }
        _sendFeedback.value = "Sesi browser dibersihkan."
    }
}

enum class PairingMode {
    QR,
    CODE
}
