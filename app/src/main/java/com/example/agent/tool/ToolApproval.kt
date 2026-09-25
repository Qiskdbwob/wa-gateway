package com.example.agent.tool

import com.example.agent.model.ToolPermission
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 9 — approval layer.
 *
 * A tool whose [ToolPermission] is [ToolPermission.CONFIRM] must not run until a human (or a
 * configured policy) explicitly allows it. This interface is the seam the Agent Loop consults
 * before executing such a tool; the UI / WhatsApp bridge supplies the concrete policy.
 *
 * The layer is intentionally minimal: it answers one question per tool call — "may this run?" —
 * and stays out of the tool's own logic. Tools that are [ToolPermission.SAFE] are never routed
 * through here, so the common case (read-only file/terminal tools) is never blocked.
 */
interface ToolApproval {

    /**
     * Returns true if [permission] may execute without further confirmation.
     *
     * - [ToolPermission.SAFE] is always allowed.
     * - [ToolPermission.CONFIRM] is allowed only when the policy decides so (e.g. an
     *   auto-allowlist, or a previously granted session decision).
     */
    fun isAllowed(permission: ToolPermission): Boolean

    /**
     * Suspends until a human decision is available for a [ToolPermission.CONFIRM] tool that
     * [isAllowed] rejected. Returns true to proceed, false to deny (the tool is then reported
     * back to the model as a failed result).
     *
     * Implementations must be cancellable: if the surrounding turn is abandoned (e.g. the
     * conversation is interrupted), this call must not hang forever.
     */
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision
}

/** A tool call awaiting human confirmation. */
data class ApprovalRequest(
    val toolName: String,
    val permission: ToolPermission,
    /** Short, model-readable reason the tool needs approval, e.g. "mutating filesystem". */
    val reason: String,
    /** Opaque id tying the decision back to the originating turn/conversation. */
    val conversationId: String
)

/** Outcome of an approval request. */
sealed interface ApprovalDecision {
    /** Proceed with the tool call. */
    data object Approved : ApprovalDecision
    /** Reject the tool call; it is reported to the model as a failed result. */
    data object Denied : ApprovalDecision
    /** No human is available to decide (e.g. headless channel); fall back to policy. */
    data object Unavailable : ApprovalDecision
}

/**
 * Default policy: approve everything. Used when no interactive approval surface is wired up
 * (e.g. the Echo provider, or a channel that has no UI). This keeps the loop non-blocking while
 * still routing every CONFIRM tool through the [ToolApproval] seam so a real policy can be
 * dropped in without touching the loop.
 */
object AutoApproveAll : ToolApproval {
    override fun isAllowed(permission: ToolPermission): Boolean = true
    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision = ApprovalDecision.Approved
}

/**
 * In-memory approval gate used by the Agent Loop.
 *
 * A tool that needs confirmation is surfaced as a pending [ApprovalRequest] through [pending]
 * and the loop suspends on [requestApproval] until [grant]/[deny] is called (or the caller
 * cancels). This is the bridge between the loop and any UI / WhatsApp approval surface: the UI
 * observes [pending], renders a prompt, and calls [grant]/[deny].
 *
 * For channels without an interactive surface, [Unavailable] is returned after [timeoutMs],
 * which the loop treats as "fall back to policy" (see [AutoApproveAll]).
 */
class ApprovalGate(
    private val timeoutMs: Long = 30_000L
) : ToolApproval {

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    /** One-shot channel so a suspended [requestApproval] wakes exactly once per decision. */
    private var decision: Channel<ApprovalDecision>? = null

    override fun isAllowed(permission: ToolPermission): Boolean = permission == ToolPermission.SAFE

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        // SAFE tools never reach here (isAllowed short-circuits them), but guard anyway.
        if (isAllowed(request.permission)) return ApprovalDecision.Approved

        _pending.value = request
        val channel = Channel<ApprovalDecision>(Channel.CONFLATED).also { decision = it }
        try {
            return withTimeoutOrNull(timeoutMs) { channel.receive() }
                ?: ApprovalDecision.Unavailable
        } finally {
            _pending.value = null
            decision = null
        }
    }

    /** Called by the UI / bridge when the human approves the pending request. */
    fun grant() = dispatch(ApprovalDecision.Approved)
    /** Called by the UI / bridge when the human rejects the pending request. */
    fun deny() = dispatch(ApprovalDecision.Denied)

    private fun dispatch(outcome: ApprovalDecision) {
        val channel = decision ?: return
        channel.trySend(outcome).isSuccess
    }
}
