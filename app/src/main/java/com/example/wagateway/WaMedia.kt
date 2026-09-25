package com.example.wagateway

import java.util.UUID

/** A media message received from WhatsApp (image / audio / video / document). */
data class WaMediaMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val chat: String,
    val isGroup: Boolean,
    /** "image" | "audio" | "video" | "document". */
    val mediaType: String,
    val mimetype: String,
    val caption: String,
    val filename: String,
    val messageId: String,
    /** Unix seconds. */
    val timestamp: Long,
    /** Marshalled protobuf payload; pass back to [WaGatewayManager.downloadMedia]. */
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is WaMediaMessage && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
