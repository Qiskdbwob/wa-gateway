package com.example.wagateway

import java.util.UUID

data class WaMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean = false
)
