package wagateway

interface WaEventListener {
    fun onQRCode(code: String)
    fun onPairingCode(code: String)
    fun onConnectionStatus(status: String)
    fun onMessage(
        sender: String,
        chat: String,
        isGroup: Boolean,
        text: String,
        messageId: String,
        timestamp: Long
    )
    fun onMedia(
        sender: String,
        chat: String,
        isGroup: Boolean,
        mediaType: String,
        mimetype: String,
        caption: String,
        filename: String,
        messageId: String,
        timestamp: Long,
        payload: ByteArray
    )
}
