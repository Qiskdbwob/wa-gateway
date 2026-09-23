package com.example.agent

import com.example.agent.loop.AgentLoop
import com.example.agent.loop.AgentState
import com.example.agent.model.Agent
import com.example.agent.model.AgentChannelAdapter
import com.example.agent.model.AgentInput
import com.example.agent.model.AgentResponse
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import com.example.agent.model.Tool
import com.example.agent.model.ToolCall
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.provider.ModelProvider
import com.example.agent.router.ModelRouter
import com.example.agent.router.ModelTarget
import com.example.agent.router.RetryPolicy
import com.example.agent.storage.InMemoryAgentSessionRepository
import com.example.agent.tool.CurrentTimeTool
import com.example.agent.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

/**
 * Phase 6 — Tool System.
 *
 * The point of these tests is that the loop really runs tools end to end: the model asks
 * for a tool, the registry resolves it, the result goes back to the model, and the final
 * answer is what the user receives. Both safety bounds are covered as well — an unknown
 * tool must not crash the turn, and a model that keeps calling tools must be stopped.
 */
class ToolSystemTest {

    private val fixedNow = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Provider that answers from a script and records every request it was given. */
    private class ScriptedProvider(
        private val handler: suspend (ModelRequest, Int) -> Result<ModelResponse>
    ) : ModelProvider {
        override val id: String = "scripted"
        override val name: String = "Scripted Provider"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            requests.add(request)
            return handler(request, requests.size)
        }
    }

    private fun textAnswer(text: String, request: ModelRequest) = ModelResponse(
        content = text,
        finishReason = "stop",
        model = request.modelId ?: "scripted-model",
        provider = "Scripted Provider"
    )

    private fun toolCallAnswer(vararg calls: ToolCall, request: ModelRequest) = ModelResponse(
        content = "",
        finishReason = "tool_calls",
        model = request.modelId ?: "scripted-model",
        provider = "Scripted Provider",
        toolCalls = calls.toList()
    )

    private fun timeTool() = CurrentTimeTool(clock = { fixedNow }, defaultTimeZone = utc)

    // ==========================================
    // Registry
    // ==========================================

    @Test
    fun phase6_test1_registryExposesBuiltInToolAndHidesConfirmTools() {
        val registry = ToolRegistry.withBuiltIns()

        val currentTime = registry.get("current_time")
        assertNotNull(currentTime)
        assertEquals("builtin.current_time", currentTime!!.id)

        val definition = registry.definitions().first { it.name == "current_time" }
        assertTrue(definition.description.isNotBlank())
        assertTrue(definition.parametersJson.contains("timezone_offset_hours"))

        // A tool that needs manual approval must never be advertised while there is no
        // approval layer, otherwise the model could ask for something nobody can answer.
        val dangerous = object : Tool {
            override val id: String = "builtin.dangerous"
            override val name: String = "dangerous_thing"
            override val description: String = "Menghapus sesuatu"
            override val inputSchema: String = """{"type":"object","properties":{}}"""
            override val permission: ToolPermission = ToolPermission.CONFIRM
            override suspend fun execute(input: String): ToolResult =
                ToolResult(success = true, output = "should never run")
        }
        registry.register(dangerous)

        assertNotNull(registry.get("dangerous_thing"))
        assertTrue(registry.definitions().none { it.name == "dangerous_thing" })
        assertEquals(registry.safeTools().size, registry.definitions().size)

        registry.unregister("dangerous_thing")
        assertNull(registry.get("dangerous_thing"))
    }

    @Test
    fun phase6_test2_currentTimeToolReturnsRealDeviceData() = runBlocking {
        val tool = timeTool()

        val withOffset = tool.execute("""{"timezone_offset_hours": 7}""")
        assertTrue(withOffset.success)
        assertTrue(withOffset.output.contains("UTC+07:00"))
        assertTrue(withOffset.output.contains("2023-11-14T22:13:20"))
        assertEquals(fixedNow.toString(), withOffset.metadata["epochMillis"])

        // Out of range offset falls back to the device zone instead of inventing one.
        val fallbackZone = tool.execute("""{"timezone_offset_hours": 99}""")
        assertTrue(fallbackZone.success)
        assertTrue(fallbackZone.output.contains("UTC+00:00"))

        // Garbage arguments must not throw.
        val noArgs = tool.execute("bukan json")
        assertTrue(noArgs.success)
    }

    // ==========================================
    // Agent loop: model -> tool -> model -> answer
    // ==========================================

    @Test
    fun phase6_test3_toolResultIsFedBackToTheModelAndOnlyFinalAnswerIsPersisted() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val dispatched = mutableListOf<String>()

        val provider = ScriptedProvider { request, call ->
            when (call) {
                1 -> Result.success(
                    toolCallAnswer(
                        ToolCall(id = "call-1", name = "current_time", arguments = "{}"),
                        request = request
                    )
                )

                else -> {
                    // The follow-up request must carry the tool result, otherwise the model
                    // would have to guess what the tool returned.
                    val toolMessage = request.messages.last()
                    assertEquals(AgentRole.TOOL, toolMessage.role)
                    assertEquals("call-1", toolMessage.toolCallId)
                    assertEquals("current_time", toolMessage.toolName)
                    assertTrue(toolMessage.content.contains("2023-11-14T22:13:20"))
                    Result.success(textAnswer("Sekarang pukul 22:13 UTC.", request))
                }
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 3),
            modelProvider = provider,
            sessionRepository = repo,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )
        loop.registerChannelAdapter(object : AgentChannelAdapter {
            override val channelName: String = "whatsapp"
            override suspend fun sendResponse(input: AgentInput, response: AgentResponse): Result<Unit> {
                dispatched += response.content
                return Result.success(Unit)
            }
        })

        val result = loop.processInput(
            AgentInput(conversationId = "conv-tools", content = "Jam berapa sekarang?", channel = "whatsapp")
        )

        assertTrue(result.isSuccess)
        assertEquals("Sekarang pukul 22:13 UTC.", result.getOrThrow().content)
        assertEquals(listOf("Sekarang pukul 22:13 UTC."), dispatched)

        // Two model calls: the tool request and the answer that used the result.
        assertEquals(2, provider.requests.size)
        val followUp = provider.requests[1]
        assertEquals(3, followUp.messages.size)
        assertTrue(followUp.tools.isNotEmpty())
        assertTrue(
            followUp.messages.any { it.role == AgentRole.ASSISTANT && it.toolCalls.size == 1 }
        )

        // Only the user turn and the final answer are persisted: the tool round trip is an
        // internal mechanic of the turn, not part of the conversation history.
        val session = repo.getOrCreateSession("conv-tools")
        val history = repo.getMessages(session.sessionId)
        assertEquals(2, history.size)
        assertEquals(AgentRole.USER, history[0].role)
        assertEquals(AgentRole.ASSISTANT, history[1].role)
        assertEquals("Sekarang pukul 22:13 UTC.", history[1].content)

        assertEquals(AgentState.IDLE, loop.state.value)
        assertNull(loop.currentActivity.value)
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_CALLS_REQUESTED") })
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_RESULT") && it.contains("success=true") })
    }

    @Test
    fun phase6_test4_unknownToolIsReportedBackWithoutCrashing() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var describedResult: String? = null

        val provider = ScriptedProvider { request, call ->
            when (call) {
                1 -> Result.success(
                    toolCallAnswer(
                        ToolCall(id = "call-9", name = "tidak_ada", arguments = "{}"),
                        request = request
                    )
                )

                else -> {
                    describedResult = request.messages.last().content
                    Result.success(textAnswer("Maaf, saya tidak bisa memakai tool itu.", request))
                }
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            modelProvider = provider,
            sessionRepository = repo,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )

        val result = loop.processMessage("conv-unknown-tool", "Pakai tool tidak_ada")
        assertTrue(result.isSuccess)
        assertTrue(describedResult!!.contains("gagal"))
        assertTrue(describedResult!!.contains("tidak tersedia"))
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_ERROR") })
    }

    @Test
    fun phase6_test5_runawayToolCallingIsBoundedAndFailsControlled() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val provider = ScriptedProvider { request, _ ->
            Result.success(
                toolCallAnswer(
                    ToolCall(id = "call-loop", name = "current_time", arguments = "{}"),
                    request = request
                )
            )
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            modelProvider = provider,
            sessionRepository = repo,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )

        val result = loop.processMessage("conv-runaway", "Jam berapa?")

        assertTrue(result.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        // One initial request + exactly maxToolIterations follow-ups, then the loop stops.
        assertEquals(3, provider.requests.size)
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_BUDGET_EXCEEDED") })
        assertNotNull(loop.lastError.value)
        assertTrue(loop.lastError.value!!.contains("terlalu banyak memakai tool"))
    }

    @Test
    fun phase6_test6_toolsAreNotAdvertisedWhenDisabled() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val provider = ScriptedProvider { request, _ ->
            Result.success(textAnswer("Tanpa tool", request))
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true, toolsEnabled = false),
            modelProvider = provider,
            sessionRepository = repo,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )

        val result = loop.processMessage("conv-no-tools", "Halo")
        assertTrue(result.isSuccess)
        assertTrue(provider.requests.single().tools.isEmpty())
        assertTrue(loop.activityLogs.value.none { it.contains("TOOLS_AVAILABLE") })
    }

    @Test
    fun phase6_test7_endpointThatRejectsToolSchemaIsRetriedWithoutTools() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var sawToolsAdvertised = false

        val provider = ScriptedProvider { request, _ ->
            if (request.tools.isNotEmpty()) {
                sawToolsAdvertised = true
                Result.failure(IOException("HTTP 400 error from OpenAI Compatible: unknown field 'tools'"))
            } else {
                Result.success(textAnswer("Jawaban tanpa tool.", request))
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = provider,
            sessionRepository = repo,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )

        val result = loop.processMessage("conv-schema", "Halo")

        assertTrue(result.isSuccess)
        assertEquals("Jawaban tanpa tool.", result.getOrThrow())
        assertTrue(sawToolsAdvertised)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests[1].tools.isEmpty())
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_SCHEMA_REJECTED") })
    }

    @Test
    fun phase6_test8_failedToolTurnStillFallsBackToTheNextModel() = runBlocking {
        val repo = InMemoryAgentSessionRepository()

        val primary = ScriptedProvider { request, call ->
            if (call == 1) {
                Result.success(
                    toolCallAnswer(
                        ToolCall(id = "call-1", name = "current_time", arguments = "{}"),
                        request = request
                    )
                )
            } else {
                Result.failure(IOException("500 Internal Server Error"))
            }
        }
        val fallback = ScriptedProvider { request, _ ->
            Result.success(textAnswer("Jawaban dari model cadangan.", request))
        }

        val router = ModelRouter(
            initialTargets = listOf(
                ModelTarget(id = "primary", provider = primary, priority = 0),
                ModelTarget(id = "fallback", provider = fallback, priority = 1)
            ),
            retryPolicy = RetryPolicy(maxAttemptsPerModel = 1, initialBackoffMs = 10, maxTotalAttemptsBudget = 3)
        )

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            sessionRepository = repo,
            modelRouter = router,
            toolRegistry = ToolRegistry(listOf(timeTool()))
        )

        val result = loop.processMessage("conv-tool-fallback", "Jam berapa sekarang?")

        assertTrue(result.isSuccess)
        assertEquals("Jawaban dari model cadangan.", result.getOrThrow())
        assertEquals(2, primary.requests.size)
        assertEquals(1, fallback.requests.size)
        assertTrue(loop.activityLogs.value.any { it.contains("FALLBACK_STARTED") })
        assertTrue(loop.activityLogs.value.any { it.contains("phase=tool-follow-up") })
    }

    @Test
    fun phase6_test9_noRegistryMeansPlainTextCompletion() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val provider = ScriptedProvider { request, _ ->
            Result.success(textAnswer("Halo juga", request))
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = provider,
            sessionRepository = repo
        )

        val result = loop.processMessage("conv-plain", "Halo")
        assertTrue(result.isSuccess)
        assertTrue(provider.requests.single().tools.isEmpty())
        assertFalse(loop.activityLogs.value.any { it.contains("TOOL_CALLS_REQUESTED") })
    }
}
