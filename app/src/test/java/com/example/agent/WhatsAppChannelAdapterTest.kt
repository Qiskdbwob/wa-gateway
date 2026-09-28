package com.example.agent

import com.example.agent.bridge.EDIT_TARGET_KEY
import com.example.agent.bridge.WhatsAppChannelAdapter
import com.example.agent.bridge.WHATSAPP_CHUNK_CHARS
import com.example.agent.bridge.splitForWhatsApp
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentResponse
import com.example.wagateway.OutgoingMessageSender
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the UX rule introduced for slow (reasoning) models: the instant
 * "sedang berpikir..." bubble must be edited into the final answer so the chat keeps a
 * single bubble, with a safe fallback when WhatsApp rejects the edit.
 */
class WhatsAppChannelAdapterTest {

    private class FakeGateway(
        private val editSucceeds: Boolean = true
    ) : OutgoingMessageSender {
        val sent = mutableListOf<Pair<String, String>>()
        val edits = mutableListOf<Triple<String, String, String>>()

        override suspend fun sendText(target: String, text: String): Result<String> {
            sent += target to text
            return Result.success("sent-${sent.size}")
        }

        override suspend fun editText(target: String, messageId: String, text: String): Result<Unit> {
            edits += Triple(target, messageId, text)
            return if (editSucceeds) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("edit window expired"))
            }
        }
    }

    private fun input(metadata: Map<String, String> = emptyMap()) = AgentInput(
        conversationId = "628123456789@s.whatsapp.net",
        senderId = "628123456789@s.whatsapp.net",
        content = "Halo agent",
        channel = "whatsapp",
        metadata = metadata
    )

    @Test
    fun placeholderIsEditedIntoFinalAnswer() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway)

        val result = adapter.sendResponse(
            input(mapOf(EDIT_TARGET_KEY to "ack-1")),
            AgentResponse(content = "Jawaban final")
        )

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.edits.size)
        assertEquals(
            Triple("628123456789@s.whatsapp.net", "ack-1", "Jawaban final"),
            gateway.edits.first()
        )
        // No second bubble: the placeholder was reused.
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun fallsBackToNewMessageWhenEditFails() = runTest {
        val gateway = FakeGateway(editSucceeds = false)
        val adapter = WhatsAppChannelAdapter(gateway)

        val result = adapter.sendResponse(
            input(mapOf(EDIT_TARGET_KEY to "ack-1")),
            AgentResponse(content = "Jawaban final")
        )

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.edits.size)
        assertEquals(1, gateway.sent.size)
        assertEquals("Jawaban final", gateway.sent.first().second)
    }

    @Test
    fun withoutPlaceholderItSendsANewMessage() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway)

        val result = adapter.sendResponse(input(), AgentResponse(content = "Halo juga"))

        assertTrue(result.isSuccess)
        assertTrue(gateway.edits.isEmpty())
        assertEquals(1, gateway.sent.size)
        assertEquals("Halo juga", gateway.sent.first().second)
    }

    @Test
    fun internalTurnsAreNotDeliveredToWhatsApp() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway) { input ->
            input.metadata["source"] == "scheduler"
        }

        val result = adapter.sendResponse(
            input(mapOf(EDIT_TARGET_KEY to "ack-1", "source" to "scheduler")),
            AgentResponse(content = "Pelajaran sudah dicatat ✅")
        )

        // The reflection report is bookkeeping: it must not reach the chat at all — neither as a
        // new bubble nor as an edit of the "sedang berpikir" placeholder.
        assertTrue(result.isSuccess)
        assertTrue(gateway.sent.isEmpty())
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun aScheduledTaskTheUserAskedForIsStillDelivered() = runTest {
        val gateway = FakeGateway()
        // Same rule as the bridge: only the internal reflection task is suppressed.
        val adapter = WhatsAppChannelAdapter(gateway) { input ->
            input.metadata["scheduledTaskId"] == "sched-reflection"
        }

        val result = adapter.sendResponse(
            input(mapOf("source" to "scheduler", "scheduledTaskId" to "sched-news")),
            AgentResponse(content = "Berita pagi: ...")
        )

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.sent.size)
        assertEquals("Berita pagi: ...", gateway.sent.first().second)
    }

    // ==========================================
    // LONG ANSWERS: never lose the tail
    // ==========================================

    @Test
    fun splitKeepsEveryCharacterAndStaysWithinTheLimit() {
        val text = buildString {
            repeat(40) { index ->
                append("Paragraf ")
                append(index)
                append(": isi kalimat yang cukup panjang untuk mengisi ruang bubble. ")
                append("\n\n")
            }
        }

        val chunks = splitForWhatsApp(text, limit = 500)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        // Lossless: WhatsApp may re-join nothing, so the chunks must reconstruct the answer exactly.
        assertEquals(text, chunks.joinToString(""))
        // Breaks happen at real boundaries (paragraph/line/sentence), not mid-word.
        assertTrue(chunks.dropLast(1).all { it.endsWith("\n") || it.endsWith(" ") || it.endsWith(".") })
    }

    @Test
    fun splitHardCutsOnlyWhenThereIsNoBoundaryAtAll() {
        val text = "x".repeat(1_200)

        val chunks = splitForWhatsApp(text, limit = 500)

        assertEquals(listOf(500, 500, 200), chunks.map { it.length })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun aShortAnswerStaysASingleBubble() {
        assertEquals(listOf("Halo"), splitForWhatsApp("Halo", limit = 100))
    }

    @Test
    fun aLongAnswerIsDeliveredAsSeveralBubblesWithoutLosingTheTail() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway)
        val long = "kalimat panjang yang harus utuh. ".repeat(300)
        assertTrue(long.length > WHATSAPP_CHUNK_CHARS)

        val result = adapter.sendResponse(input(), AgentResponse(content = long))

        assertTrue(result.isSuccess)
        assertTrue(gateway.sent.size > 1)
        assertEquals(long, gateway.sent.joinToString("") { it.second })
    }

    @Test
    fun thePlaceholderBecomesTheFirstChunkOfALongAnswer() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway)
        val long = "kalimat panjang yang harus utuh. ".repeat(300)

        val result = adapter.sendResponse(
            input(mapOf(EDIT_TARGET_KEY to "ack-1")),
            AgentResponse(content = long)
        )

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.edits.size)
        // Everything after the first chunk arrives as its own bubble; nothing is dropped.
        assertTrue(gateway.sent.isNotEmpty())
        assertEquals(long, gateway.edits.first().third + gateway.sent.joinToString("") { it.second })
    }

    @Test
    fun blankResponseIsNeverSent() = runTest {
        val gateway = FakeGateway()
        val adapter = WhatsAppChannelAdapter(gateway)

        val result = adapter.sendResponse(input(), AgentResponse(content = "   "))

        assertTrue(result.isFailure)
        assertTrue(gateway.sent.isEmpty())
        assertTrue(gateway.edits.isEmpty())
    }
}
