package wagateway

import java.util.UUID

class Client(
    private val dbPath: String,
    private val listener: WaEventListener?
) {
    var isLoggedIn: Boolean = false
        private set

    var isConnected: Boolean = false
        private set

    fun hasSession(): Boolean = isLoggedIn

    fun connect() {
        if (isLoggedIn) {
            isConnected = true
            listener?.onConnectionStatus("Connected")
        } else {
            isConnected = false
            listener?.onConnectionStatus("Waiting for QR")
            listener?.onQRCode("2@wa_gateway_demo_pairing_code_${System.currentTimeMillis()}")
        }
    }

    fun disconnect() {
        isConnected = false
        listener?.onConnectionStatus("Disconnected")
    }

    fun pairPhone(phone: String): String {
        val code = "1234-5678"
        listener?.onConnectionStatus("Waiting for code entry")
        listener?.onPairingCode(code)
        return code
    }

    fun sendText(target: String, text: String): String {
        return "msg_" + UUID.randomUUID().toString()
    }

    fun editText(target: String, messageId: String, text: String) {
        // Stub edit message
    }

    fun sendImage(target: String, data: ByteArray, mimetype: String, caption: String): String {
        return "img_" + UUID.randomUUID().toString()
    }

    fun sendDocument(target: String, data: ByteArray, mimetype: String, filename: String): String {
        return "doc_" + UUID.randomUUID().toString()
    }

    fun sendAudio(target: String, data: ByteArray, mimetype: String, voiceNote: Boolean): String {
        return "audio_" + UUID.randomUUID().toString()
    }

    fun downloadMedia(payload: ByteArray): ByteArray {
        return payload
    }

    fun markRead(chat: String, sender: String, messageId: String) {
    }

    fun setTyping(chat: String, typing: Boolean) {
    }

    fun resetSession() {
        isLoggedIn = false
        isConnected = false
        listener?.onConnectionStatus("Logged out")
    }

    fun logout() {
        resetSession()
    }

    fun close() {
        disconnect()
    }
}
