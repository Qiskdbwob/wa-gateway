package com.example.agent.loop

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

enum class ModelErrorKind {
    TRANSIENT,
    RATE_LIMIT,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    CONTEXT_OVERFLOW,
    INVALID_REQUEST,
    AUTH_ERROR,
    TOOL_ERROR,
    EMPTY_RESPONSE,
    UNKNOWN
}

data class ModelClassification(
    val kind: ModelErrorKind,
    val isRetryable: Boolean,
    val canFallback: Boolean,
    val retryAfterMs: Long? = null,
    val description: String
)

object ModelErrorClassifier {
    fun classify(throwable: Throwable): ModelClassification {
        val msg = (throwable.message ?: "").lowercase()

        return when {
            // Timeout
            throwable is SocketTimeoutException || throwable is TimeoutException ||
            msg.contains("timeout") || msg.contains("timed out") || msg.contains("deadline exceeded") -> {
                ModelClassification(
                    kind = ModelErrorKind.TIMEOUT,
                    isRetryable = true,
                    canFallback = true,
                    description = "Request timed out"
                )
            }

            // Rate Limit
            msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("quota exceeded") -> {
                val retryAfter = extractRetryAfterMs(msg)
                ModelClassification(
                    kind = ModelErrorKind.RATE_LIMIT,
                    isRetryable = true,
                    canFallback = true,
                    retryAfterMs = retryAfter,
                    description = "Rate limit or quota exceeded"
                )
            }

            // Authentication / Authorization Error
            msg.contains("401") || msg.contains("403") || msg.contains("unauthorized") ||
            msg.contains("forbidden") || msg.contains("invalid api key") || msg.contains("api key is not configured") ||
            msg.contains("authentication") -> {
                ModelClassification(
                    kind = ModelErrorKind.AUTH_ERROR,
                    isRetryable = false, // Do not blindly retry same invalid key
                    canFallback = true,  // Can fallback to alternative provider
                    description = "Authentication failed or invalid API key"
                )
            }

            // Provider Unavailable / Server Errors
            throwable is ConnectException || msg.contains("502") || msg.contains("503") || msg.contains("504") ||
            msg.contains("unavailable") || msg.contains("bad gateway") || msg.contains("connection refused") ||
            msg.contains("server error") || msg.contains("internal server error") -> {
                ModelClassification(
                    kind = ModelErrorKind.PROVIDER_UNAVAILABLE,
                    isRetryable = true,
                    canFallback = true,
                    description = "Provider service is temporarily unavailable"
                )
            }

            // Context Overflow
            msg.contains("context_length_exceeded") || msg.contains("maximum context length") ||
            msg.contains("too many tokens") || msg.contains("context length") || msg.contains("context overflow") ||
            msg.contains("token limit") -> {
                ModelClassification(
                    kind = ModelErrorKind.CONTEXT_OVERFLOW,
                    isRetryable = false, // Not retryable with identical context
                    canFallback = true,
                    description = "Context window exceeded"
                )
            }

            // Empty Response
            msg.contains("empty response") || msg.contains("empty choices") || msg.contains("received empty response") -> {
                ModelClassification(
                    kind = ModelErrorKind.EMPTY_RESPONSE,
                    isRetryable = true,
                    canFallback = true,
                    description = "Received empty content from model"
                )
            }

            // Invalid Request
            msg.contains("400") || msg.contains("invalid request") || msg.contains("bad request") || msg.contains("invalid_request_error") -> {
                ModelClassification(
                    kind = ModelErrorKind.INVALID_REQUEST,
                    isRetryable = false, // Do not retry identical bad request
                    canFallback = false,
                    description = "Invalid request payload or schema"
                )
            }

            // Tool error
            msg.contains("tool_error") || msg.contains("function call failed") -> {
                ModelClassification(
                    kind = ModelErrorKind.TOOL_ERROR,
                    isRetryable = false,
                    canFallback = true,
                    description = "Tool execution failed"
                )
            }

            // Transient Network Glitch
            throwable is IOException || msg.contains("reset by peer") || msg.contains("broken pipe") ||
            msg.contains("network error") || msg.contains("unexpected end of stream") -> {
                ModelClassification(
                    kind = ModelErrorKind.TRANSIENT,
                    isRetryable = true,
                    canFallback = true,
                    description = "Transient network issue"
                )
            }

            else -> {
                ModelClassification(
                    kind = ModelErrorKind.UNKNOWN,
                    isRetryable = false,
                    canFallback = true,
                    description = "Unknown error: ${throwable.message}"
                )
            }
        }
    }

    private fun extractRetryAfterMs(msg: String): Long? {
        val regex = Regex("""retry[ -]after[:\s]+(\d+)""")
        val match = regex.find(msg)
        return match?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000L }
    }
}
