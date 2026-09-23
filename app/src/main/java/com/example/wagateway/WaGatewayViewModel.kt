package com.example.wagateway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.bridge.WhatsAppAgentBridge
import com.example.agent.loop.AgentState
import com.example.agent.loop.isBusy
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentSession
import com.example.agent.model.Tool
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
    val agentCurrentActivity: StateFlow<String?> = agentBridge.agentLoop.currentActivity

    /** Phase 6: tools registered on the Agent Loop, shown on the Developer screen. */
    val agentTools: List<Tool> = agentBridge.toolRegistry.all()
    val agentSystemPrompt = MutableStateFlow(agentBridge.systemPrompt.value)
    val agentBaseUrl = MutableStateFlow(agentBridge.providerConfig.value.baseUrl)
    val agentApiKey = MutableStateFlow(agentBridge.providerConfig.value.apiKey)
    val agentModelId = MutableStateFlow(agentBridge.providerConfig.value.modelId)
    val useEchoFallback: StateFlow<Boolean> = agentBridge.useEchoFallback

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
                agentModelId.value = config.modelId
            }
        }
        viewModelScope.launch {
            agentBridge.systemPrompt.collect { prompt ->
                agentSystemPrompt.value = prompt
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
            prompt = agentSystemPrompt.value
        )
        _sendFeedback.value = "Pengaturan Agent berhasil disimpan ke Room Database!"
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
}

enum class PairingMode {
    QR,
    CODE
}
