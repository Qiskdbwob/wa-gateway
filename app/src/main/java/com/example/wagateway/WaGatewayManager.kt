package com.example.wagateway

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wagateway.Client
import wagateway.WaEventListener
import wagateway.Wagateway
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WaGatewayManager private constructor(context: Context) : WaEventListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var client: Client? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _qrCode = MutableStateFlow<String?>(null)
    val qrCode: StateFlow<String?> = _qrCode.asStateFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _messages = MutableStateFlow<List<WaMessage>>(emptyList())
    val messages: StateFlow<List<WaMessage>> = _messages.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<(sender: String, text: String, timestamp: Long) -> Unit>()

    fun addMessageListener(listener: (sender: String, text: String, timestamp: Long) -> Unit) {
        messageListeners.add(listener)
    }

    fun removeMessageListener(listener: (sender: String, text: String, timestamp: Long) -> Unit) {
        messageListeners.remove(listener)
    }

    init {
        scope.launch(Dispatchers.IO) {
            initClient()
        }
    }

    @Synchronized
    private fun initClient() {
        try {
            go.Seq.setContext(appContext)
            appContext.filesDir.mkdirs()
            // App-specific internal storage for SQLite session database
            val dbFile = File(appContext.filesDir, "wagateway.db")
            addLog("Initializing WhatsApp Gateway with DB: ${dbFile.name}")
            
            client = Wagateway.newClient(dbFile.absolutePath, this)
            val loggedIn = client?.isLoggedIn() ?: false
            _isLoggedIn.value = loggedIn
            addLog("Client initialized. Has previous session: $loggedIn")
        } catch (e: Exception) {
            addLog("Error initializing client: ${e.message}")
            _connectionStatus.value = "Init Error: ${e.message}"
        }
    }

    fun connect() {
        scope.launch(Dispatchers.IO) {
            try {
                if (client == null) {
                    initClient()
                }
                addLog("Connecting to WhatsApp...")
                _connectionStatus.value = "Connecting..."
                client?.connect()
                val loggedIn = client?.isLoggedIn() ?: false
                val connected = client?.isConnected() ?: false
                _isLoggedIn.value = loggedIn
                _isConnected.value = connected
            } catch (e: Exception) {
                addLog("Connection failed: ${e.message}")
                _connectionStatus.value = "Connection Error: ${e.message}"
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                addLog("Disconnecting...")
                client?.disconnect()
                _isConnected.value = false
                _connectionStatus.value = "Disconnected"
                _qrCode.value = null
                _pairingCode.value = null
            } catch (e: Exception) {
                addLog("Disconnect error: ${e.message}")
            }
        }
    }

    suspend fun requestPairingCode(phone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (client == null) {
                initClient()
            }
            val c = client ?: return@withContext Result.failure(IllegalStateException("Gateway client not initialized"))
            addLog("Requesting pairing code for phone: $phone")
            _connectionStatus.value = "Requesting pairing code..."
            val code = c.pairPhone(phone)
            _pairingCode.value = code
            addLog("Pairing code received: $code")
            Result.success(code)
        } catch (e: Exception) {
            addLog("Pairing code error: ${e.message}")
            _connectionStatus.value = "Code Error: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun sendText(target: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val c = client ?: return@withContext Result.failure(IllegalStateException("Gateway not initialized"))
            if (!c.isConnected) {
                return@withContext Result.failure(IllegalStateException("Gateway is not connected to WhatsApp"))
            }
            c.sendText(target, text)
            addLog("Message sent to $target (length=${text.length})")

            val outgoing = WaMessage(
                sender = "Me (Gateway)",
                text = text,
                timestamp = System.currentTimeMillis() / 1000,
                isOutgoing = true
            )
            _messages.value = listOf(outgoing) + _messages.value
            Result.success(Unit)
        } catch (e: Exception) {
            addLog("Failed to send message: ${e.message}")
            Result.failure(e)
        }
    }

    fun logout() {
        scope.launch(Dispatchers.IO) {
            try {
                addLog("Logging out session...")
                client?.logout()
                _isLoggedIn.value = false
                _isConnected.value = false
                _qrCode.value = null
                _pairingCode.value = null
                _connectionStatus.value = "Logged out"
            } catch (e: Exception) {
                addLog("Logout error: ${e.message}")
            }
        }
    }

    private fun addLog(message: String) {
        val entry = "[${timeFormat.format(Date())}] $message"
        _logs.value = listOf(entry) + _logs.value.take(99)
    }

    // Callbacks from Go WaEventListener interface
    override fun onQRCode(code: String) {
        scope.launch {
            _qrCode.value = code
            _isLoggedIn.value = false
            addLog("New QR code received for pairing")
        }
    }

    override fun onPairingCode(code: String) {
        scope.launch {
            _pairingCode.value = code
            _isLoggedIn.value = false
            addLog("Received pairing code")
        }
    }

    override fun onConnectionStatus(status: String) {
        scope.launch {
            _connectionStatus.value = status
            val loggedIn = client?.isLoggedIn() ?: false
            val connected = client?.isConnected() ?: false
            _isLoggedIn.value = loggedIn
            _isConnected.value = connected
            if (status == "Connected") {
                _qrCode.value = null
                _pairingCode.value = null
            }
            addLog("Status updated: $status")
        }
    }

    override fun onMessage(sender: String, text: String, timestamp: Long) {
        scope.launch {
            val msg = WaMessage(
                sender = sender,
                text = text,
                timestamp = timestamp,
                isOutgoing = false
            )
            _messages.value = listOf(msg) + _messages.value
            addLog("Received message from $sender")

            for (listener in messageListeners) {
                try {
                    listener(sender, text, timestamp)
                } catch (e: Exception) {
                    addLog("Error in message listener: ${e.message}")
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: WaGatewayManager? = null

        fun getInstance(context: Context): WaGatewayManager {
            return instance ?: synchronized(this) {
                instance ?: WaGatewayManager(context).also { instance = it }
            }
        }
    }
}
