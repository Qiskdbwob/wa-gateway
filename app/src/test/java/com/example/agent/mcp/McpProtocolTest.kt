package com.example.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP protocol helpers.
 *
 * These are the parts that break silently against a real server: a wrong request shape, an SSE
 * body treated as JSON, a header line losing its token because of the second colon. None of them
 * need a network or `org.json`, so they are pinned here.
 */
class McpProtocolTest {

    @Test
    fun initializeRequestCarriesProtocolVersionAndClientInfo() {
        val request = McpProtocol.initializeRequest(id = 7)

        assertTrue(request.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(request.contains("\"id\":7"))
        assertTrue(request.contains("\"method\":\"initialize\""))
        assertTrue(request.contains(McpProtocol.PROTOCOL_VERSION))
        assertTrue(request.contains("wa-gateway-agent"))
    }

    @Test
    fun toolsCallRequestPassesArgumentsAsObject() {
        val call = McpProtocol.toolsCallRequest(3, "search_repos", """{"query":"kotlin"}""")

        assertTrue(call.contains("\"method\":\"tools/call\""))
        assertTrue(call.contains("\"name\":\"search_repos\""))
        assertTrue(call.contains("\"arguments\":{\"query\":\"kotlin\"}"))
    }

    @Test
    fun toolsCallRequestFallsBackToEmptyObjectForBlankArguments() {
        // A no-argument tool must not turn into a protocol error.
        assertTrue(McpProtocol.toolsCallRequest(4, "ping", "").contains("\"arguments\":{}"))
        assertTrue(McpProtocol.toolsCallRequest(4, "ping", "bukan json").contains("\"arguments\":{}"))
    }

    @Test
    fun toolsListRequestUsesCursorOnlyWhenGiven() {
        assertTrue(McpProtocol.toolsListRequest().contains("\"params\":{}"))
        assertTrue(McpProtocol.toolsListRequest(cursor = "abc").contains("\"cursor\":\"abc\""))
    }

    @Test
    fun extractJsonPayloadReturnsPlainBodyUntouched() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""

        assertEquals(body, McpProtocol.extractJsonPayload(body, "application/json"))
    }

    @Test
    fun extractJsonPayloadUnwrapsServerSentEvents() {
        val sse = buildString {
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n")
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n")
        }

        // The last data line carries the actual result, earlier ones are notifications.
        assertEquals(
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""",
            McpProtocol.extractJsonPayload(sse, "text/event-stream")
        )
        // ...and detection also works when the server lies about the content type.
        assertTrue(McpProtocol.extractJsonPayload(sse)!!.contains("\"tools\":[]"))
    }

    @Test
    fun extractJsonPayloadIgnoresEmptyBodiesAndDoneMarkers() {
        assertNull(McpProtocol.extractJsonPayload("   "))
        assertNull(McpProtocol.extractJsonPayload("data: [DONE]"))
    }

    @Test
    fun parseHeadersKeepsTokensWithColons() {
        val headers = McpProtocol.parseHeaders(
            """
            # komentar diabaikan
            Authorization: Bearer abc:def:ghi
            X-Env: prod

            baris tanpa titik dua
            """.trimIndent()
        )

        assertEquals("Bearer abc:def:ghi", headers["Authorization"])
        assertEquals("prod", headers["X-Env"])
        assertEquals(2, headers.size)
    }

    @Test
    fun toolNameIsPrefixedSanitisedAndLengthCapped() {
        assertEquals("mcp__github__search_repos", McpProtocol.toolName("github", "search_repos"))
        // Server names with spaces/punctuation must not leak into the tool name.
        assertEquals("mcp__my_server__list_issues", McpProtocol.toolName("My Server!", "list-issues"))
        // Two servers with the same tool must stay distinguishable for the model.
        assertFalse(
            McpProtocol.toolName("github", "search") == McpProtocol.toolName("gitlab", "search")
        )
        val longName = McpProtocol.toolName("s".repeat(80), "t".repeat(80))
        assertTrue(longName.length <= McpProtocol.MAX_TOOL_NAME_CHARS)
        assertTrue(longName.startsWith("mcp__"))
    }
}
