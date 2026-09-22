package com.example.agent

import com.example.agent.loop.AgentLoop
import com.example.agent.loop.AgentState
import com.example.agent.model.Agent
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.provider.EchoTestProvider
import com.example.agent.provider.ModelProvider
import com.example.agent.storage.InMemoryAgentSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentCoreTest {

    @Test
    fun testSessionRepositoryStoresMessagesInOrder() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val session = repo.getOrCreateSession(conversationId = "628123456789")

        assertEquals("628123456789", session.conversationId)
        assertEquals("whatsapp", session.channel)

        val msg1 = AgentMessage(sessionId = session.sessionId, role = AgentRole.USER, content = "Halo")
        val msg2 = AgentMessage(sessionId = session.sessionId, role = AgentRole.ASSISTANT, content = "Halo juga!")

        repo.saveMessage(msg1)
        repo.saveMessage(msg2)

        val messages = repo.getMessages(session.sessionId)
        assertEquals(2, messages.size)
        assertEquals(AgentRole.USER, messages[0].role)
        assertEquals("Halo", messages[0].content)
        assertEquals(AgentRole.ASSISTANT, messages[1].role)
        assertEquals("Halo juga!", messages[1].content)
    }

    @Test
    fun testAgentLoopSuccessFlow() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val echoProvider = EchoTestProvider()
        val loop = AgentLoop(
            agent = Agent(name = "Test Assistant", enabled = true),
            modelProvider = echoProvider,
            sessionRepository = repo
        )

        assertEquals(AgentState.IDLE, loop.state.value)

        val result = loop.processMessage(conversationId = "user-1", userText = "Bantu saya hitung")
        assertTrue(result.isSuccess)

        val reply = result.getOrThrow()
        assertTrue(reply.contains("Bantu saya hitung"))
        assertEquals(AgentState.IDLE, loop.state.value)

        val session = repo.getOrCreateSession("user-1")
        val history = repo.getMessages(session.sessionId)
        assertEquals(2, history.size)
        assertEquals(AgentRole.USER, history[0].role)
        assertEquals("Bantu saya hitung", history[0].content)
        assertEquals(AgentRole.ASSISTANT, history[1].role)
        assertTrue(history[1].content.contains("Bantu saya hitung"))
    }

    @Test
    fun testAgentLoopHandlesProviderErrorWithoutCrashing() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val failingProvider = object : ModelProvider {
            override val id: String = "failing"
            override val name: String = "Failing Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("Rate limit exceeded 429"))
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = failingProvider,
            sessionRepository = repo
        )

        val result = loop.processMessage(conversationId = "user-2", userText = "Test error")
        assertTrue(result.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        assertNotNull(loop.lastError.value)
        assertTrue(loop.lastError.value!!.contains("Rate limit exceeded 429"))
        assertTrue(loop.activityLogs.value.any { it.contains("Rate limit exceeded 429") })
    }

    @Test
    fun testAgentDisabledDoesNotProcess() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val loop = AgentLoop(
            agent = Agent(enabled = false),
            modelProvider = EchoTestProvider(),
            sessionRepository = repo
        )

        val result = loop.processMessage(conversationId = "user-3", userText = "Hello")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("disabled") == true)
    }

    @Test
    fun test1_BasicChat() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val loop = AgentLoop(
            agent = Agent(name = "Personal Assistant", enabled = true),
            modelProvider = EchoTestProvider(),
            sessionRepository = repo
        )

        val input = com.example.agent.model.AgentInput(
            conversationId = "user-basic",
            content = "Halo",
            channel = "whatsapp"
        )
        val result = loop.processInput(input)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertTrue(response.content.isNotBlank())
        assertEquals(AgentState.IDLE, loop.state.value)
    }

    @Test
    fun test2_ContextHistoryProvidedToModel() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val capturedContexts = mutableListOf<List<AgentMessage>>()

        val contextCapturingProvider = object : ModelProvider {
            override val id = "capturing"
            override val name = "Capturing Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                capturedContexts.add(request.messages)
                val hasAhmad = request.messages.any { it.content.contains("Ahmad") }
                val reply = if (hasAhmad) "Nama Anda adalah Ahmad." else "Saya belum tahu nama Anda."
                return Result.success(
                    com.example.agent.model.ModelResponse(
                        content = reply,
                        finishReason = "stop",
                        model = id,
                        provider = name
                    )
                )
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = contextCapturingProvider,
            sessionRepository = repo
        )

        // Turn 1: User introduces name
        val res1 = loop.processMessage("conv-ahmad", "Nama saya Ahmad.")
        assertTrue(res1.isSuccess)

        // Turn 2: User asks name
        val res2 = loop.processMessage("conv-ahmad", "Siapa nama saya?")
        assertTrue(res2.isSuccess)
        assertEquals("Nama Anda adalah Ahmad.", res2.getOrThrow())

        // Verify context of turn 2 contained turn 1
        val turn2Messages = capturedContexts[1]
        assertTrue(turn2Messages.any { it.content == "Nama saya Ahmad." })
        assertTrue(turn2Messages.any { it.content == "Siapa nama saya?" })
    }

    @Test
    fun test3_SeparateSessionsDoNotMix() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val capturedContexts = mutableMapOf<String, List<AgentMessage>>()

        val provider = object : ModelProvider {
            override val id = "inspector"
            override val name = "Inspector"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                val lastMsg = request.messages.last().content
                capturedContexts[lastMsg] = request.messages
                return Result.success(
                    com.example.agent.model.ModelResponse(content = "OK for $lastMsg", model = id, provider = name)
                )
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = provider,
            sessionRepository = repo
        )

        // Conversation A
        loop.processMessage("conv-A", "Saya suka fisika.")
        // Conversation B
        loop.processMessage("conv-B", "Apa yang saya sukai?")

        val contextB = capturedContexts["Apa yang saya sukai?"] ?: emptyList()
        // Conversation B MUST NOT have "Saya suka fisika." from Conversation A
        assertTrue(contextB.none { it.content.contains("fisika") })
        assertEquals(1, contextB.size)
        assertEquals("Apa yang saya sukai?", contextB[0].content)
    }

    @Test
    fun test4_PersistenceAcrossSimulatedRestart() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = EchoTestProvider(),
            sessionRepository = repo
        )

        loop.processMessage("conv-persistent", "Pesan sebelum restart")

        // Simulate new loop instance connected to the same repository/database
        val newLoop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = EchoTestProvider(),
            sessionRepository = repo
        )

        val session = repo.getOrCreateSession("conv-persistent")
        val messages = repo.getMessages(session.sessionId)
        assertEquals(2, messages.size) // 1 user + 1 assistant
        assertEquals("Pesan sebelum restart", messages[0].content)
    }

    @Test
    fun test5_WhatsAppAdapterEndToEnd() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var dispatchedTarget: String? = null
        var dispatchedText: String? = null

        val testChannelAdapter = object : com.example.agent.model.AgentChannelAdapter {
            override val channelName: String = "whatsapp"
            override suspend fun sendResponse(
                input: com.example.agent.model.AgentInput,
                response: com.example.agent.model.AgentResponse
            ): Result<Unit> {
                dispatchedTarget = input.conversationId
                dispatchedText = response.content
                return Result.success(Unit)
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = EchoTestProvider(),
            sessionRepository = repo
        )
        loop.registerChannelAdapter(testChannelAdapter)

        val incomingWaInput = com.example.agent.model.AgentInput(
            conversationId = "628999888777",
            senderId = "628999888777",
            content = "Pesanan saya kapan dikirim?",
            channel = "whatsapp"
        )

        val result = loop.processInput(incomingWaInput)
        assertTrue(result.isSuccess)
        assertEquals("628999888777", dispatchedTarget)
        assertNotNull(dispatchedText)
        assertTrue(dispatchedText!!.contains("Pesanan saya kapan dikirim?"))
    }

    @Test
    fun test6_ModelErrorHandling() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val failingProvider = object : ModelProvider {
            override val id: String = "broken"
            override val name: String = "Broken Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(java.io.IOException("503 Service Unavailable"))
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = failingProvider,
            sessionRepository = repo
        )

        val result = loop.processMessage("user-err", "Halo")
        assertTrue(result.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        assertEquals("503 Service Unavailable", loop.lastError.value)
        assertTrue(loop.activityLogs.value.any { it.contains("AGENT_ERROR") })
    }

    @Test
    fun test7_EmptyResponseRejectedAndDoesNotDispatch() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var adapterDispatched = false

        val channelAdapter = object : com.example.agent.model.AgentChannelAdapter {
            override val channelName = "whatsapp"
            override suspend fun sendResponse(
                input: com.example.agent.model.AgentInput,
                response: com.example.agent.model.AgentResponse
            ): Result<Unit> {
                adapterDispatched = true
                return Result.success(Unit)
            }
        }

        val emptyProvider = object : ModelProvider {
            override val id = "empty"
            override val name = "Empty Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "   ", model = id, provider = name))
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = emptyProvider,
            sessionRepository = repo
        )
        loop.registerChannelAdapter(channelAdapter)

        val result = loop.processInput(
            com.example.agent.model.AgentInput(conversationId = "user-empty", content = "Test blank response", channel = "whatsapp")
        )
        assertTrue(result.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        assertTrue(loop.activityLogs.value.any { it.contains("AGENT_ERROR") })
        // Channel adapter MUST NOT have been called with empty content
        assertTrue(!adapterDispatched)
    }

    // ==========================================
    // PHASE 4: RETRY, ERROR CLASSIFICATION & FALLBACK TESTS
    // ==========================================

    @Test
    fun phase4_test1_NormalSuccess() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var requestCount = 0
        val mockPrimary = object : ModelProvider {
            override val id = "primary"
            override val name = "Primary Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                requestCount++
                return Result.success(com.example.agent.model.ModelResponse(content = "Success 1", model = id, provider = name))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = mockPrimary, priority = 0)
            )
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-1", "Hello Normal")
        assertTrue(res.isSuccess)
        assertEquals("Success 1", res.getOrThrow())
        assertEquals(1, requestCount)
        assertTrue(loop.activityLogs.value.any { it.contains("MODEL_SUCCESS") })
        assertTrue(loop.activityLogs.value.none { it.contains("FALLBACK_STARTED") })
    }

    @Test
    fun phase4_test2_TimeoutAndRetrySuccess() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var attempts = 0
        val timeoutProvider = object : ModelProvider {
            override val id = "primary"
            override val name = "Timeout Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                attempts++
                return if (attempts == 1) {
                    Result.failure(java.net.SocketTimeoutException("Read timed out"))
                } else {
                    Result.success(com.example.agent.model.ModelResponse(content = "Recovered from timeout", model = id, provider = name))
                }
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(com.example.agent.router.ModelTarget(id = "primary", provider = timeoutProvider)),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 2, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-2", "Test Timeout")
        assertTrue(res.isSuccess)
        assertEquals("Recovered from timeout", res.getOrThrow())
        assertEquals(2, attempts)
        assertTrue(loop.activityLogs.value.any { it.contains("MODEL_RETRY") })
        assertTrue(loop.activityLogs.value.any { it.contains("RETRY_COMPLETED") })
    }

    @Test
    fun phase4_test3_RateLimitWithBackoffAndFallback() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val primaryRateLimited = object : ModelProvider {
            override val id = "primary"
            override val name = "Primary RateLimited"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("HTTP 429 Too Many Requests: Rate limit reached"))
            }
        }
        val fallbackProvider = object : ModelProvider {
            override val id = "fallback"
            override val name = "Fallback Working"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "Fallback after 429", model = id, provider = name))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = primaryRateLimited, priority = 0),
                com.example.agent.router.ModelTarget(id = "fallback", provider = fallbackProvider, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 2, initialBackoffMs = 10, maxTotalAttemptsBudget = 5)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-3", "Test 429")
        assertTrue(res.isSuccess)
        assertEquals("Fallback after 429", res.getOrThrow())
        assertTrue(loop.activityLogs.value.any { it.contains("FALLBACK_STARTED") })
        assertTrue(loop.activityLogs.value.any { it.contains("MODEL_SUCCESS") && it.contains("fallback") })
    }

    @Test
    fun phase4_test4_ProviderUnavailableFallback() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val brokenProvider = object : ModelProvider {
            override val id = "primary"
            override val name = "Broken"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("503 Service Unavailable: upstream connect error"))
            }
        }
        val fallbackProvider = object : ModelProvider {
            override val id = "fallback"
            override val name = "Fallback"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "Fallback Response 503", model = id, provider = name))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = brokenProvider, priority = 0),
                com.example.agent.router.ModelTarget(id = "fallback", provider = fallbackProvider, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 1, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-4", "Test 503")
        assertTrue(res.isSuccess)
        assertEquals("Fallback Response 503", res.getOrThrow())
    }

    @Test
    fun phase4_test5_AuthenticationErrorNoInfiniteRetry() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var attempts = 0
        val authErrorProvider = object : ModelProvider {
            override val id = "primary"
            override val name = "Auth Error"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                attempts++
                return Result.failure(IOException("HTTP 401 Unauthorized: Invalid API Key"))
            }
        }
        val fallbackProvider = object : ModelProvider {
            override val id = "fallback"
            override val name = "Echo Fallback"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "Echo after auth failure", model = id, provider = name))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = authErrorProvider, priority = 0),
                com.example.agent.router.ModelTarget(id = "fallback", provider = fallbackProvider, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 3, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-5", "Test 401")
        assertTrue(res.isSuccess)
        // 401 is NOT retryable, so primary was called only ONCE before falling back
        assertEquals(1, attempts)
        assertEquals("Echo after auth failure", res.getOrThrow())
    }

    @Test
    fun phase4_test6_InvalidRequestControlledFailureOrFallback() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var attempts = 0
        val badRequestProvider = object : ModelProvider {
            override val id = "primary"
            override val name = "Bad Request"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                attempts++
                return Result.failure(IOException("HTTP 400 Bad Request: Invalid JSON body"))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(com.example.agent.router.ModelTarget(id = "primary", provider = badRequestProvider)),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 3, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-6", "Test 400")
        assertTrue(res.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        // 400 is not retryable on the same model
        assertEquals(1, attempts)
    }

    @Test
    fun phase4_test7_EmptyResponseRetryAndFallback() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val emptyProvider = object : ModelProvider {
            override val id = "primary"
            override val name = "Empty Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "  ", model = id, provider = name))
            }
        }
        val fallbackProvider = object : ModelProvider {
            override val id = "fallback"
            override val name = "Fallback Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.success(com.example.agent.model.ModelResponse(content = "Valid fallback text", model = id, provider = name))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = emptyProvider, priority = 0),
                com.example.agent.router.ModelTarget(id = "fallback", provider = fallbackProvider, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 2, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-7", "Test empty response")
        assertTrue(res.isSuccess)
        assertEquals("Valid fallback text", res.getOrThrow())
    }

    @Test
    fun phase4_test8_AllModelsFailStateFailed() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        val fail1 = object : ModelProvider {
            override val id = "p1"
            override val name = "Fail 1"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("Server error 500"))
            }
        }
        val fail2 = object : ModelProvider {
            override val id = "p2"
            override val name = "Fail 2"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("Server error 502 Bad Gateway"))
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "p1", provider = fail1, priority = 0),
                com.example.agent.router.ModelTarget(id = "p2", provider = fail2, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 2, initialBackoffMs = 10, maxTotalAttemptsBudget = 4)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        val res = loop.processMessage("conv-p4-8", "All models fail")
        assertTrue(res.isFailure)
        assertEquals(AgentState.FAILED, loop.state.value)
        assertNotNull(loop.lastError.value)
        assertTrue(loop.activityLogs.value.any { it.contains("AGENT_FAILURE") })
    }

    @Test
    fun phase4_test9_ContextOverflowMitigation() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var callCount = 0
        var secondCallMessagesCount = 0
        val overflowProvider = object : ModelProvider {
            override val id = "p1"
            override val name = "Overflow Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                callCount++
                return if (callCount == 1) {
                    Result.failure(IOException("400 maximum context length is 4096 tokens, but request has 5000 tokens"))
                } else {
                    secondCallMessagesCount = request.messages.size
                    Result.success(com.example.agent.model.ModelResponse(content = "Mitigated answer", model = id, provider = name))
                }
            }
        }
        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(com.example.agent.router.ModelTarget(id = "p1", provider = overflowProvider)),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 2, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)

        // Populate session with multiple turns
        val session = repo.getOrCreateSession("conv-p4-9")
        repo.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.USER, content = "Old 1"))
        repo.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.ASSISTANT, content = "Old 2"))
        repo.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.USER, content = "Old 3"))
        repo.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.ASSISTANT, content = "Old 4"))

        val res = loop.processMessage("conv-p4-9", "Latest Question")
        assertTrue(res.isSuccess)
        assertEquals("Mitigated answer", res.getOrThrow())
        assertTrue(loop.activityLogs.value.any { it.contains("CONTEXT_OVERFLOW") })
        assertTrue(secondCallMessagesCount <= 3) // Truncated context
    }

    @Test
    fun phase4_test10_WhatsAppEndToEndWithFallback() = runBlocking {
        val repo = InMemoryAgentSessionRepository()
        var dispatchedTarget: String? = null
        var dispatchedContent: String? = null

        val waAdapter = object : com.example.agent.model.AgentChannelAdapter {
            override val channelName: String = "whatsapp"
            override suspend fun sendResponse(
                input: com.example.agent.model.AgentInput,
                response: com.example.agent.model.AgentResponse
            ): Result<Unit> {
                dispatchedTarget = input.conversationId
                dispatchedContent = response.content
                return Result.success(Unit)
            }
        }

        val primaryBroken = object : ModelProvider {
            override val id = "openai-broken"
            override val name = "OpenAI Provider"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                return Result.failure(IOException("500 Internal Server Error"))
            }
        }
        val fallbackEcho = EchoTestProvider()

        val router = com.example.agent.router.ModelRouter(
            initialTargets = listOf(
                com.example.agent.router.ModelTarget(id = "primary", provider = primaryBroken, priority = 0),
                com.example.agent.router.ModelTarget(id = "echo-fallback", provider = fallbackEcho, priority = 1)
            ),
            retryPolicy = com.example.agent.router.RetryPolicy(maxAttemptsPerModel = 1, initialBackoffMs = 10)
        )
        val loop = AgentLoop(agent = Agent(enabled = true), sessionRepository = repo, modelRouter = router)
        loop.registerChannelAdapter(waAdapter)

        val waInput = com.example.agent.model.AgentInput(
            conversationId = "62811223344",
            senderId = "62811223344",
            content = "Pesanan paket A",
            channel = "whatsapp"
        )
        val res = loop.processInput(waInput)
        assertTrue(res.isSuccess)
        assertEquals("62811223344", dispatchedTarget)
        assertNotNull(dispatchedContent)
        assertTrue(dispatchedContent!!.contains("Pesanan paket A"))
        assertTrue(loop.state.value == AgentState.IDLE || loop.state.value == AgentState.COMPLETED)
    }

    @Test
    fun phase4_test11_ModelProbe() = runBlocking {
        val mockProvider = object : ModelProvider {
            override val id = "probe-target"
            override val name = "Probe Target"
            override suspend fun generate(request: com.example.agent.model.ModelRequest): Result<com.example.agent.model.ModelResponse> {
                assertEquals("1 + 1 =", request.messages.last().content)
                return Result.success(com.example.agent.model.ModelResponse(content = "2", model = id, provider = name))
            }
        }
        val target = com.example.agent.router.ModelTarget(id = "probe-target", provider = mockProvider)
        val router = com.example.agent.router.ModelRouter(listOf(target))

        val probe = router.probeModel(target)
        assertTrue(probe.available)
        assertTrue(probe.latencyMs >= 0)
        assertEquals("probe-target", probe.targetId)
    }
}
