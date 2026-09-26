package com.example.agent.mcp

/** One tool advertised by a remote MCP server. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    /** Raw JSON Schema as sent by the server; blank means "no arguments". */
    val inputSchema: String = ""
)

/**
 * String-level parts of the Model Context Protocol (JSON-RPC 2.0 over Streamable HTTP).
 *
 * Kept free of `org.json` on purpose: request building, SSE unwrapping, header parsing and tool
 * naming are the bits that break silently, so they are unit-testable on a plain JVM. The JSON
 * *object* work lives in [McpClient], which runs on a device where `org.json` exists.
 */
object McpProtocol {

    /** Protocol revision this client speaks; the server may answer with its own. */
    const val PROTOCOL_VERSION = "2024-11-05"

    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_INITIALIZED = "notifications/initialized"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"

    /** The header a Streamable-HTTP server uses to pin a session. */
    const val SESSION_HEADER = "Mcp-Session-Id"

    const val DEFAULT_LIMIT_CHARS = 12_000

    fun initializeRequest(id: Int = 1): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$METHOD_INITIALIZE","params":{"protocolVersion":"$PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"wa-gateway-agent","version":"1.0"}}}"""

    fun initializedNotification(): String =
        """{"jsonrpc":"2.0","method":"$METHOD_INITIALIZED","params":{}}"""

    fun toolsListRequest(id: Int = 2, cursor: String? = null): String {
        val params = if (cursor.isNullOrBlank()) "{}" else """{"cursor":"${escape(cursor)}"}"""
        return """{"jsonrpc":"2.0","id":$id,"method":"$METHOD_TOOLS_LIST","params":$params}"""
    }

    /**
     * Wraps tool arguments. The model sends an object; an empty/blank argument string becomes `{}`
     * so a no-argument tool does not turn into a protocol error.
     */
    fun toolsCallRequest(id: Int, toolName: String, argumentsJson: String): String {
        val args = argumentsJson.trim().takeIf { it.startsWith("{") } ?: "{}"
        return """{"jsonrpc":"2.0","id":$id,"method":"$METHOD_TOOLS_CALL","params":{"name":"${escape(toolName)}","arguments":$args}}"""
    }

    /**
     * Streamable HTTP may answer either with a plain JSON body or with Server-Sent Events. Both
     * carry the same JSON-RPC payload, so this returns the payload from the *last* `data:` line —
     * progress notifications can precede the actual result.
     */
    fun extractJsonPayload(body: String, contentType: String? = null): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        val isSse = contentType?.contains("text/event-stream", ignoreCase = true) == true ||
            trimmed.startsWith("event:") ||
            trimmed.startsWith("data:")

        if (!isSse) return trimmed

        val payload = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .lastOrNull()
        return payload
    }

    /**
     * `Key: Value` per line → header map. Blank lines and comments are ignored, and a value may
     * contain colons (bearer tokens, dates) because only the *first* colon separates.
     */
    fun parseHeaders(raw: String): Map<String, String> =
        raw.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val separator = trimmed.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()

    /**
     * Local tool name for a remote tool: `mcp__<server>__<tool>`, lowercased and stripped to
     * `[a-z0-9_]` (OpenAI-compatible endpoints reject anything else), capped to 64 chars. The server
     * part keeps two different MCP servers from colliding on the same tool name.
     */
    fun toolName(serverName: String, toolName: String): String {
        val server = slug(serverName)
        val tool = slug(toolName)
        return "mcp__${server}__${tool}".take(MAX_TOOL_NAME_CHARS)
    }

    private fun slug(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "tool" }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    const val MAX_TOOL_NAME_CHARS = 64
}
