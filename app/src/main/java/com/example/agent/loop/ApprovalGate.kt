package com.example.agent.loop

import com.example.agent.model.Tool
import com.example.agent.model.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Priority 3 — the hook between the Agent Loop's tool executor and the approval
 * coordinator. It is injected into the loop as a function so the loop has no dependency
 * on Room/storage (keeps the loop testable with plain fakes).
 *
 * Behaviour:
 *  - non-CONFIRM tool  → run normally.
 *  - CONFIRM tool      → create an approval request, answer the model with
 *                        "PENDING_APPROVAL:<id>", surface the id via [lastRequest], and
 *                        do NOT execute anything. The user answers `/approve <id>` and
 *                        the ChatCommandHandler executes the stored call then.
 */
class ApprovalGate(
    /** Creates the request; returns its chat-facing id. */
    private val createRequest: suspend (conversationId: String, toolName: String, arguments: String) -> String
) {
    data class PendingApproval(
        val requestId: String,
        val conversationId: String,
        val toolName: String
    )

    private val _lastRequest = MutableStateFlow<PendingApproval?>(null)
    val lastRequest: StateFlow<PendingApproval?> = _lastRequest.asStateFlow()

    suspend fun executeWithApproval(
        tool: Tool,
        callName: String,
        arguments: String,
        conversationId: String
    ): ToolResult {
        if (tool.permission != com.example.agent.model.ToolPermission.CONFIRM) {
            return tool.execute(arguments)
        }

        val requestId = createRequest(conversationId, callName, arguments)
        _lastRequest.value = PendingApproval(requestId, conversationId, callName)
        return ToolResult(
            success = false,
            output = "",
            error = "PENDING_APPROVAL:$requestId:Tool '$callName' butuh persetujuan. " +
                "Balas dengan /approve $requestId untuk menyetujui atau /reject $requestId untuk menolak."
        )
    }
}
