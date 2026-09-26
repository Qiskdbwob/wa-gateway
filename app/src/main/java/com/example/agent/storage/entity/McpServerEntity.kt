package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A configured MCP (Model Context Protocol) server.
 *
 * [headers] is the `Key: Value` block the user pastes (usually an Authorization bearer token) and
 * is stored **encrypted** with SecretCipher, for the same reason API keys are: it is a secret the
 * user typed, and a Room file on a rooted/backed-up device must not leak it verbatim.
 */
@Entity(
    tableName = "mcp_servers",
    indices = [Index(value = ["name"], unique = true), Index(value = ["enabled"])]
)
data class McpServerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val headers: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
