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
import java.util.UUID

/**
 * The subset of the gateway used to push text back to WhatsApp. It exists as an
 * interface so the channel adapter (edit-the-placeholder logic) can be unit tested
 * without the native gateway.
 */
interface OutgoingMessageSender {
    /** Sends a message and returns its WhatsApp message ID. */
    suspend fun sendText(target: String, text: String): Result<String>

    /** Replaces the content of a message we already sent. */
    suspend fun editText(target: String, messageId: String, text: String): Result<Unit>
}

class WaGatewayManager private constructor(context: Context) : WaEventListener, OutgoingMessageSender {

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

    /**
     * True when a linked WhatsApp session is stored on this device. This survives app
     * restarts (it is read from the SQLite session store), so it is what the UI uses to
     * decide between "hubungkan ulang" and "tautkan perangkat".
     */
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _messages = MutableStateFlow<List<WaMessage>>(emptyList())
    val messages: StateFlow<List<WaMessage>> = _messages.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<IncomingMessageListener>()

    fun addMessageListener(listener: IncomingMessageListener) {
        messageListeners.add(listener)
    }

    fun removeMessageListener(listener: IncomingMessageListener) {
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
            refreshState()

            if (_isLoggedIn.value) {
                addLog("Sesi WhatsApp tersimpan ditemukan — menyambung ulang otomatis (tanpa QR).")
                connect()
            } else {
                addLog("Belum ada sesi tersimpan — perlu tautkan perangkat (QR / kode pairing).")
            }
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing/incompatible native library raises
            // UnsatisfiedLinkError, and the app must degrade to an error state instead
            // of crashing at startup.
            addLog("Error initializing client: ${e.message}")
            _connectionStatus.value = "Init Error: ${e.message}"
        }
    }

    /** Re-reads session/connection state from the native client. */
    private fun refreshState() {
        val c = client ?: return
        _isLoggedIn.value = c.hasSession() || c.isLoggedIn
        _isConnected.value = c.isConnected
    }

    fun connect(reason: String = "manual") {
        scope.launch(Dispatchers.IO) {
            try {
                if (client == null) {
                    initClient()
                }
                addLog("Connecting to WhatsApp ($reason)...")
                _connectionStatus.value = "Connecting..."
                client?.connect()
                refreshState()
            } catch (e: Throwable) {
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
            } catch (e: Throwable) {
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
        } catch (e: Throwable) {
            addLog("Pairing code error: ${e.message}")
            _connectionStatus.value = "Code Error: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Sends a text message. Returns the WhatsApp message ID, which can later be passed
     * to [editText] (used for the "sedang berpikir" placeholder).
     */
    override suspend fun sendText(target: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val c = client ?: return@withContext Result.failure(IllegalStateException("Gateway not initialized"))
            if (!c.isConnected) {
                return@withContext Result.failure(IllegalStateException("Gateway is not connected to WhatsApp"))
            }
            val messageId = c.sendText(target, text)
            addLog("Message sent to $target (length=${text.length}, id=$messageId)")

            val outgoing = WaMessage(
                id = messageId.ifBlank { UUID.randomUUID().toString() },
                sender = "Me (Gateway)",
                text = text,
                timestamp = System.currentTimeMillis() / 1000,
                isOutgoing = true
            )
            _messages.value = listOf(outgoing) + _messages.value
            Result.success(messageId)
        } catch (e: Throwable) {
            addLog("Failed to send message: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Replaces the content of a message this device already sent. WhatsApp only allows
     * this for a limited time window after sending, so callers must treat a failure as
     * "fall back to sending a new message".
     */
    override suspend fun editText(target: String, messageId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val c = client ?: return@withContext Result.failure(IllegalStateException("Gateway not initialized"))
                c.editText(target, messageId, text)
                Result.success(Unit)
            } catch (e: Throwable) {
                addLog("Failed to edit message $messageId: ${e.message}")
                Result.failure(e)
            }
        }

    /** Sends a read receipt (centang biru) for an incoming message. */
    suspend fun markRead(chat: String, sender: String, messageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val c = client ?: return@withContext Result.failure(IllegalStateException("Gateway not initialized"))
                if (!c.isConnected) {
                    return@withContext Result.failure(IllegalStateException("Gateway is not connected"))
                }
                c.markRead(chat, sender, messageId)
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Shows or clears the "typing..." indicator in a chat. Best effort. */
    fun setTyping(chat: String, typing: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val c = client ?: return@launch
                if (!c.isConnected) return@launch
                c.setTyping(chat, typing)
            } catch (e: Throwable) {
                addLog("Typing indicator error: ${e.message}")
            }
        }
    }

    /** Unlinks the WhatsApp session so a device can be paired again from scratch. */
    fun resetSession() {
        scope.launch(Dispatchers.IO) {
            try {
                addLog("Unlinking stored WhatsApp session...")
                client?.resetSession()
                _isLoggedIn.value = false
                _isConnected.value = false
                _qrCode.value = null
                _pairingCode.value = null
                _connectionStatus.value = "Logged out"
                addLog("Sesi dihapus. Tautkan ulang dengan QR atau kode pairing.")
            } catch (e: Throwable) {
                addLog("Reset session error: ${e.message}")
            }
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
            } catch (e: Throwable) {
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
            refreshState()
            if (status == "Connected") {
                _qrCode.value = null
                _pairingCode.value = null
            }
            addLog("Status updated: $status")
        }
    }

    override fun onMessage(
        sender: String,
        chat: String,
        isGroup: Boolean,
        text: String,
        messageId: String,
        timestamp: Long
    ) {
        scope.launch {
            val msg = WaMessage(
                id = messageId.ifBlank { UUID.randomUUID().toString() },
                sender = sender,
                text = text,
                timestamp = timestamp,
                isOutgoing = false
            )
            _messages.value = listOf(msg) + _messages.value
            addLog("Received message from $sender${if (isGroup) " in group $chat" else ""}")

            // Mark as read in parallel: WhatsApp only shows blue ticks once the receipt
            // has been acknowledged by the server.
            if (messageId.isNotBlank()) {
                markRead(chat.ifBlank { sender }, sender, messageId)
                    .onFailure { addLog("Read receipt failed for $messageId: ${it.message}") }
            }

            for (listener in messageListeners) {
                try {
                    listener(sender, chat, isGroup, text, messageId, timestamp)
                } catch (e: Throwable) {
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

/**
 * Callback contract for incoming WhatsApp text messages.
 *
 * @param sender the author of the message (group participant inside a group chat)
 * @param chat   the conversation JID, use it as the reply target
 * @param isGroup true when the message came from a group conversation
 * @param text   plain text content
 * @param messageId WhatsApp message ID, used for read receipts and message edits
 * @param timestamp unix timestamp in seconds
 */
typealias IncomingMessageListener = (
    sender: String,
    chat: String,
    isGroup: Boolean,
    text: String,
    messageId: String,
    timestamp: Long
) -> Unit
