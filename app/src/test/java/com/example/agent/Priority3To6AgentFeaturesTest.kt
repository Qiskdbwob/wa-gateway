package com.example.agent

import com.example.agent.approval.ApprovalCoordinator
import com.example.agent.chat.ChatCommandHandler
import com.example.agent.loop.AgentLoop
import com.example.agent.loop.ApprovalGate
import com.example.agent.memory.CompactManager
import com.example.agent.memory.MemoryRepository
import com.example.agent.model.Agent
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import com.example.agent.model.Tool
import com.example.agent.model.ToolCall
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.provider.ModelProvider
import com.example.agent.scheduler.SchedulerEngine
import com.example.agent.storage.ContactAccessRepository
import com.example.agent.storage.InMemoryAgentSessionRepository
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.storage.entity.ScheduledTaskEntity
import com.example.agent.subagent.SubAgentManager
import com.example.agent.subagent.SubAgentSpec
import com.example.agent.subagent.TaskResult
import com.example.agent.tool.ScheduleTaskTool
import com.example.agent.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Priorities 3-6 — approvals, scheduler, chat commands and background subagents.
 *
 * The thread running through these tests is the user's requirement: only destructive
 * actions need a human decision, everything else runs straight away, and a subagent must
 * never block the main agent's reply.
 */
class Priority3To6AgentFeaturesTest {

    // ==========================================
    // Test doubles
    // ==========================================

    private class RecordingTool(
        override val name: String,
        override val permission: ToolPermission,
        private val executed: MutableList<String>
    ) : Tool {
        override val id: String = "builtin.$name"
        override val description: String = "test tool"
        override val inputSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(input: String): ToolResult {
            executed.add(input)
            return ToolResult(success = true, output = "$name dijalankan")
        }
    }

    /** Provider that asks for a tool once, then answers with text. */
    private class ToolFirstProvider(private val toolName: String) : ModelProvider {
        override val id: String = "scripted"
        override val name: String = "Scripted Provider"
        var followUpToolMessage: String? = null

        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            val last = request.messages.lastOrNull()
            if (last?.role == AgentRole.TOOL) {
                followUpToolMessage = last.content
                return Result.success(
                    ModelResponse(content = "Baik, saya tunggu keputusan Anda.", provider = id, model = "scripted")
                )
            }
            return Result.success(
                ModelResponse(
                    content = "",
                    provider = id,
                    model = "scripted",
                    toolCalls = listOf(
                        ToolCall(id = "call-1", name = toolName, arguments = """{"path":"notes.md"}""")
                    )
                )
            )
        }
    }

    // ==========================================
    // Priority 3 — approval for destructive tools
    // ==========================================

    @Test
    fun priority3_confirmToolIsParkedAndNotExecuted() = runBlocking {
        val executed = mutableListOf<String>()
        val gate = ApprovalGate { _, _, _ -> "appr-1234" }
        val tool = RecordingTool("delete_path", ToolPermission.CONFIRM, executed)

        val result = gate.executeWithApproval(
            tool = tool,
            callName = "delete_path",
            arguments = """{"path":"notes.md"}""",
            conversationId = "628111"
        )

        assertFalse(result.success)
        assertTrue(result.error!!.startsWith("PENDING_APPROVAL:appr-1234"))
        assertTrue(result.error!!.contains("/approve appr-1234"))
        assertTrue(executed.isEmpty())
        assertEquals("appr-1234", gate.lastRequest.value?.requestId)
        assertEquals("628111", gate.lastRequest.value?.conversationId)
    }

    @Test
    fun priority3_safeToolsRunWithoutAsking() = runBlocking {
        val executed = mutableListOf<String>()
        val gate = ApprovalGate { _, _, _ -> "appr-should-not-be-used" }
        val tool = RecordingTool("current_time", ToolPermission.SAFE, executed)

        val result = gate.executeWithApproval(tool, "current_time", "{}", "628111")

        assertTrue(result.success)
        assertEquals(1, executed.size)
        assertNull(gate.lastRequest.value)
    }

    @Test
    fun priority3_loopTurnsAConfirmCallIntoAPendingApproval() = runBlocking {
        val executed = mutableListOf<String>()
        val provider = ToolFirstProvider("delete_path")
        val registry = ToolRegistry(listOf(RecordingTool("delete_path", ToolPermission.CONFIRM, executed)))
        val gate = ApprovalGate { _, _, _ -> "appr-9999" }

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            modelProvider = provider,
            sessionRepository = InMemoryAgentSessionRepository(),
            toolRegistry = registry,
            approvalGate = gate
        )

        val result = loop.processInput(
            com.example.agent.model.AgentInput(conversationId = "628111", content = "hapus notes.md")
        )

        assertTrue(result.isSuccess)
        assertTrue("destructive tool must not run before approval", executed.isEmpty())
        assertTrue(provider.followUpToolMessage!!.contains("PENDING_APPROVAL:appr-9999"))
        assertEquals("appr-9999", gate.lastRequest.value?.requestId)
    }

    @Test
    fun priority3_withoutAGateTheLoopStillRunsTheTool() = runBlocking {
        // Documents WHY the gate exists: with no approval layer attached the CONFIRM tool
        // executes (legacy Phase 7 behaviour), which is exactly what the bridge must not do.
        val executed = mutableListOf<String>()
        val provider = ToolFirstProvider("delete_path")
        val registry = ToolRegistry(listOf(RecordingTool("delete_path", ToolPermission.CONFIRM, executed)))

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            modelProvider = provider,
            sessionRepository = InMemoryAgentSessionRepository(),
            toolRegistry = registry
        )

        assertTrue(
            loop.processInput(
                com.example.agent.model.AgentInput(conversationId = "628111", content = "hapus notes.md")
            ).isSuccess
        )
        assertEquals(1, executed.size)
    }

    @Test
    fun priority3_coordinatorResolvesOnceAndExpiresStaleRequests() = runBlocking {
        val dao = FakeApprovalRequestDao()
        val coordinator = ApprovalCoordinator(dao)

        val request = coordinator.createRequest("628111", "delete_path", """{"path":"a.txt"}""")
        assertTrue(request.id.startsWith("appr-"))
        assertEquals(1, coordinator.pendingFlow().first().size)
        assertEquals(1, coordinator.pendingFor("628111").size)

        val approved = coordinator.resolve(request.id, approved = true)
        assertNotNull(approved)
        assertEquals(ApprovalRequestEntity.STATUS_APPROVED, dao.findById(request.id)?.status)
        // A resolved request can never be resolved again (no double execution).
        assertNull(coordinator.resolve(request.id, approved = false))

        val stale = ApprovalRequestEntity(
            id = "appr-old",
            conversationId = "628111",
            toolName = "delete_path",
            arguments = "{}",
            requestedAt = System.currentTimeMillis() - ApprovalRequestEntity.TTL_MS - 1_000L
        )
        dao.upsert(stale)
        assertNull(coordinator.resolve("appr-old", approved = true))
        assertEquals(ApprovalRequestEntity.STATUS_EXPIRED, dao.findById("appr-old")?.status)
        assertEquals(0, coordinator.pendingFlow().first().size)
    }

    // ==========================================
    // Priority 4 — scheduler
    // ==========================================

    @Test
    fun priority4_scheduleParsingEnforcesBounds() {
        assertEquals(60L, SchedulerEngine.parseIntervalSeconds("interval:60"))
        assertEquals(3_600L, SchedulerEngine.parseIntervalSeconds(" interval:3600 "))
        assertNull(SchedulerEngine.parseIntervalSeconds("interval:59"))
        assertNull(SchedulerEngine.parseIntervalSeconds("interval:besok"))
        assertNull(SchedulerEngine.parseIntervalSeconds("setiap hari"))
        assertEquals(
            ScheduledTaskEntity.MAX_INTERVAL_SECONDS,
            SchedulerEngine.parseIntervalSeconds("interval:999999999")
        )
    }

    @Test
    fun priority4_createSchedulesTheFirstRunOneIntervalAhead() = runBlocking {
        val dao = FakeScheduledTaskDao()
        val engine = SchedulerEngine(dao, runTask = { "ok" })

        val before = System.currentTimeMillis()
        val task = engine.create(
            name = "Laporan pagi",
            schedule = "interval:3600",
            prompt = "Ringkas agenda hari ini",
            conversationId = "628111"
        )

        assertTrue(task.id.startsWith("sched-"))
        assertTrue(task.enabled)
        assertEquals("interval:3600", task.schedule)
        assertTrue(task.nextRunAt!! >= before + 3_600_000L)

        try {
            engine.create("Rusak", "interval:5", "prompt", "628111")
            throw AssertionError("schedule di bawah minimum seharusnya ditolak")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("tidak valid"))
        }
    }

    @Test
    fun priority4_tickRunsOnlyDueTasksAndRecordsTheOutcome() = runBlocking {
        val dao = FakeScheduledTaskDao()
        val promptsRun = mutableListOf<String>()
        val engine = SchedulerEngine(
            dao,
            runTask = { task ->
                promptsRun.add(task.prompt)
                "hasil untuk ${task.name}"
            }
        )

        val due = engine.create("Due", "interval:600", "prompt due", "628111")
        val future = engine.create("Future", "interval:600", "prompt future", "628222")
        val now = System.currentTimeMillis()
        // Force the first task into the past; the second keeps its future next run.
        dao.upsert(due.copy(nextRunAt = now - 1_000L))

        engine.tick(now)

        assertEquals(listOf("prompt due"), promptsRun)
        assertEquals(listOf(due.id to "COMPLETED"), dao.runHistory)
        val stored = dao.findById(due.id)!!
        assertEquals("COMPLETED", stored.lastStatus)
        assertTrue(stored.lastResult!!.contains("hasil untuk Due"))
        assertTrue(stored.nextRunAt!! > now)
        // Untouched task has not run.
        assertEquals("interval:600", dao.findById(future.id)!!.schedule)
        assertNull(dao.findById(future.id)!!.lastStatus)
    }

    @Test
    fun priority4_failingTaskIsRecordedAsFailedInsteadOfKillingTheTicker() = runBlocking {
        val dao = FakeScheduledTaskDao()
        val engine = SchedulerEngine(dao, runTask = { throw IllegalStateException("provider down") })
        val task = engine.create("Rentan", "interval:600", "prompt", "628111")
        val now = System.currentTimeMillis()
        dao.upsert(task.copy(nextRunAt = now - 500L))

        engine.tick(now)

        val stored = dao.findById(task.id)!!
        assertEquals("FAILED", stored.lastStatus)
        assertTrue(stored.lastError!!.contains("provider down"))
        assertEquals(listOf(task.id to "FAILED"), dao.runHistory)
    }

    @Test
    fun priority4_disablingATaskRemovesItFromTheDueSet() = runBlocking {
        val dao = FakeScheduledTaskDao()
        var runs = 0
        val engine = SchedulerEngine(
            dao,
            runTask = {
                runs++
                "ok"
            }
        )
        val task = engine.create("Bisa dimatikan", "interval:600", "prompt", "628111")

        engine.setEnabled(task.id, false)
        assertNull(dao.findById(task.id)!!.nextRunAt)
        assertTrue(dao.getEnabled().isEmpty())

        engine.tick(System.currentTimeMillis() + 10_000_000L)
        assertEquals(0, runs)

        assertEquals(true, engine.runNow(task.id))
        assertEquals(1, runs)
    }

    /** Local wall-clock time as an epoch value, so the daily tests never depend on the CI zone. */
    private fun atLocal(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test
    fun priority4_dailyScheduleLandsOnTheClockInsteadOfDriftingByInterval() = runBlocking {
        val zone = TimeZone.getTimeZone("Asia/Jakarta")
        val schedule = "daily:06:00,20:00"
        fun nextAfter(from: Long): Long =
            requireNotNull(SchedulerEngine.nextRunAt(schedule, from, zone)) { "tidak ada slot berikutnya" }

        // The exact complaint: a 6-hour interval started at 21:15 gives 03:15/09:15/15:15,
        // while a daily schedule keeps landing on 06:00 and 20:00.
        assertEquals(atLocal(zone, 2026, 9, 28, 6, 0), nextAfter(atLocal(zone, 2026, 9, 28, 5, 30)))
        assertEquals(atLocal(zone, 2026, 9, 28, 20, 0), nextAfter(atLocal(zone, 2026, 9, 28, 7, 0)))
        assertEquals(atLocal(zone, 2026, 9, 29, 6, 0), nextAfter(atLocal(zone, 2026, 9, 28, 21, 15)))
        // A slot that is exactly "now" has already fired, so the next one is used.
        assertEquals(atLocal(zone, 2026, 9, 28, 20, 0), nextAfter(atLocal(zone, 2026, 9, 28, 6, 0)))
        // An interval keeps its old meaning: N seconds from the previous run.
        assertEquals(
            atLocal(zone, 2026, 9, 28, 6, 0) + 21_600_000L,
            requireNotNull(SchedulerEngine.nextRunAt("interval:21600", atLocal(zone, 2026, 9, 28, 6, 0), zone))
        )
    }

    @Test
    fun priority4_dailyScheduleIsNormalisedValidatedAndDescribed() {
        assertEquals("daily:06:00,20:00", SchedulerEngine.normalize("daily:6:0,20:00"))
        // The prefix is optional when the value is plainly a clock time.
        assertEquals("daily:06:00,20:00", SchedulerEngine.normalize("06:00,20:00"))
        assertEquals(listOf(360, 1200), SchedulerEngine.parseDailyMinutes("daily:20:00,06:00"))
        assertEquals("setiap 6 jam", SchedulerEngine.describeSchedule("interval:21600"))
        assertEquals(
            "setiap hari pukul 06:00 & 20:00 (waktu perangkat)",
            SchedulerEngine.describeSchedule("daily:06:00,20:00")
        )

        // Anything that is not a real clock time (or too many of them) must be rejected, so the
        // model cannot claim a schedule the scheduler would never run.
        assertNull(SchedulerEngine.parseDailyMinutes("daily:"))
        assertNull(SchedulerEngine.parseDailyMinutes("daily:06"))
        assertNull(SchedulerEngine.parseDailyMinutes("daily:24:00"))
        assertNull(SchedulerEngine.parseDailyMinutes("daily:06:60"))
        assertNull(SchedulerEngine.parseDailyMinutes("daily:06:00,tengah hari"))
        assertNull(
            SchedulerEngine.parseDailyMinutes("daily:01:00,02:00,03:00,04:00,05:00,06:00,07:00")
        )
        assertNull(SchedulerEngine.normalize("setiap hari"))
        assertNull(SchedulerEngine.normalize("jam 12:00"))
        assertNull(SchedulerEngine.nextRunAt("setiap hari", 1_000L, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun priority4_dailyTaskFiresOncePerSlotAndStoresTheFirstFutureSlot() = runBlocking {
        val dao = FakeScheduledTaskDao()
        val runs = mutableListOf<String>()
        val engine = SchedulerEngine(
            dao,
            runTask = { task ->
                runs.add(task.name)
                "ok"
            }
        )
        val zone = TimeZone.getTimeZone("Asia/Jakarta")

        val created = engine.create("Berita pagi", "daily:6:0", "Ringkas berita", "628111")
        assertEquals("daily:06:00", created.schedule)
        assertNotNull(created.nextRunAt)

        val now = atLocal(zone, 2026, 9, 28, 6, 0)
        dao.upsert(dao.findById(created.id)!!.copy(nextRunAt = now))
        engine.tick(now)

        assertEquals(listOf("Berita pagi"), runs)
        val stored = dao.findById(created.id)!!
        assertEquals("COMPLETED", stored.lastStatus)
        // Next run is the following 06:00, not now + 24 h from the tick.
        assertEquals(atLocal(zone, 2026, 9, 29, 6, 0), requireNotNull(stored.nextRunAt))
    }

    @Test
    fun priority4_scheduleTaskToolReportsTheStoredScheduleAndFailsLoudlyOnBadInput() = runBlocking {
        val dao = FakeScheduledTaskDao()
        val engine = SchedulerEngine(dao, runTask = { "ok" })
        val tool = ScheduleTaskTool { name, schedule, prompt, conversationId ->
            engine.create(name, schedule, prompt, conversationId)
        }

        val ok = tool.execute(
            """{"name":"Berita Harian","schedule":"daily:06:00,20:00","prompt":"Ringkas berita"}"""
        )
        assertTrue(ok.success)
        // The model gets the schedule that was really stored plus the next fire time, so it cannot
        // "confirm" a schedule that does not exist.
        assertTrue(ok.output.contains("setiap hari pukul 06:00 & 20:00"))
        assertTrue(ok.output.contains("Eksekusi berikutnya:"))
        val stored = dao.getAll().single()
        assertEquals(stored.id, ok.metadata["scheduledTaskId"])
        assertEquals("daily:06:00,20:00", stored.schedule)

        val invalid = tool.execute(
            """{"name":"Rusak","schedule":"tiap hari jam 6","prompt":"p"}"""
        )
        assertFalse(invalid.success)
        assertTrue(invalid.error!!.contains("tidak valid"))
        assertEquals(1, dao.getAll().size)
    }

    // ==========================================
    // Priorities 1-4 — chat commands
    // ==========================================

    private class CommandFixture {
        val contactDao = FakeContactRuleDao()
        val approvalDao = FakeApprovalRequestDao()
        val memoryDao = FakeMemoryItemDao()
        val scheduledDao = FakeScheduledTaskDao()
        var whitelistMode = false
        val executed = mutableListOf<String>()

        val contactAccess = ContactAccessRepository(contactDao)
        val approvals = ApprovalCoordinator(approvalDao)
        val memory = MemoryRepository(memoryDao)
        val sessionRepo = InMemoryAgentSessionRepository()

        val handler = ChatCommandHandler(
            contactAccess = contactAccess,
            approvals = approvals,
            memory = memory,
            compact = CompactManager(sessionRepo, memory),
            scheduledTaskDao = scheduledDao,
            agentLoop = AgentLoop(agent = Agent(enabled = true), sessionRepository = sessionRepo),
            isWhitelistMode = { whitelistMode },
            setWhitelistMode = { whitelistMode = it },
            executeApprovedTool = { tool, arguments, conversation ->
                executed.add("$tool:$arguments")
                "dijalankan di $conversation"
            },
            resolveProvider = { null },
            maxContextMessages = { 30 }
        )
    }

    @Test
    fun chatCommands_normalTextIsNotACommand() = runBlocking {
        val fixture = CommandFixture()
        val result = fixture.handler.handle("halo agent", "628111", "628111", "s1")
        assertTrue(result is ChatCommandHandler.Result.NotACommand)

        val unknown = fixture.handler.handle("/tidakada", "628111", "628111", "s1")
        assertTrue(unknown is ChatCommandHandler.Result.NotACommand)
    }

    @Test
    fun chatCommands_whitelistCanBeTurnedOnAndManaged() = runBlocking {
        val fixture = CommandFixture()

        val help = fixture.handler.handle("/help", "628111", "628111", "s1")
        assertTrue((help as ChatCommandHandler.Result.Handled).reply.contains("/approve"))

        val on = fixture.handler.handle("/whitelist on", "628111", "628111", "s1")
        assertTrue((on as ChatCommandHandler.Result.Handled).reply.contains("AKTIF"))
        assertTrue(fixture.whitelistMode)

        val add = fixture.handler.handle("/whitelist add 62812-3456-7890 Kakak", "628111", "628111", "s1")
        assertTrue(add is ChatCommandHandler.Result.Handled)
        val stored = fixture.contactDao.findByContactId("6281234567890")
        assertNotNull(stored)
        assertEquals(ContactRuleEntity.MODE_ALLOW, stored!!.mode)
        assertEquals("Kakak", stored.label)

        val list = fixture.handler.handle("/whitelist list", "628111", "628111", "s1")
        assertTrue((list as ChatCommandHandler.Result.Handled).reply.contains("6281234567890"))

        val off = fixture.handler.handle("/whitelist off", "628111", "628111", "s1")
        assertTrue((off as ChatCommandHandler.Result.Handled).reply.contains("dimatikan"))
        assertFalse(fixture.whitelistMode)

        val block = fixture.handler.handle("/blacklist add 628999000111", "628111", "628111", "s1")
        assertTrue(block is ChatCommandHandler.Result.Handled)
        assertEquals(
            ContactRuleEntity.MODE_BLOCK,
            fixture.contactDao.findByContactId("628999000111")!!.mode
        )
    }

    @Test
    fun chatCommands_approveExecutesTheStoredCallOnce() = runBlocking {
        val fixture = CommandFixture()
        val request = fixture.approvals.createRequest(
            conversationId = "628111",
            toolName = "delete_path",
            arguments = """{"path":"notes.md"}"""
        )

        val usage = fixture.handler.handle("/approve", "628111", "628111", "s1")
        assertTrue((usage as ChatCommandHandler.Result.Handled).reply.contains("Format"))

        val approved = fixture.handler.handle("/approve ${request.id}", "628111", "628111", "s1")
        assertTrue((approved as ChatCommandHandler.Result.Handled).reply.contains("disetujui"))
        assertEquals(listOf("delete_path:{\"path\":\"notes.md\"}"), fixture.executed)

        // Re-approving the same id must not run the tool again.
        val again = fixture.handler.handle("/approve ${request.id}", "628111", "628111", "s1")
        assertTrue((again as ChatCommandHandler.Result.Handled).reply.contains("tidak ditemukan"))
        assertEquals(1, fixture.executed.size)
    }

    @Test
    fun chatCommands_rejectNeverExecutesTheTool() = runBlocking {
        val fixture = CommandFixture()
        val request = fixture.approvals.createRequest("628111", "delete_path", "{}")

        val rejected = fixture.handler.handle("/reject ${request.id}", "628111", "628111", "s1")
        assertTrue((rejected as ChatCommandHandler.Result.Handled).reply.contains("ditolak"))
        assertTrue(fixture.executed.isEmpty())
        assertEquals(
            ApprovalRequestEntity.STATUS_REJECTED,
            fixture.approvalDao.findById(request.id)!!.status
        )
    }

    @Test
    fun chatCommands_rememberStoresKnowledgeAndStatusSummarises() = runBlocking {
        val fixture = CommandFixture()

        val empty = fixture.handler.handle("/remember", "628111", "628111", "s1")
        assertTrue((empty as ChatCommandHandler.Result.Handled).reply.contains("Format"))

        fixture.handler.handle("/remember Nama kucing saya Milo", "628111", "628111", "s1")
        val stored = fixture.memory.getByType(MemoryItemEntity.TYPE_KNOWLEDGE)
        assertEquals(1, stored.size)
        assertEquals("Nama kucing saya Milo", stored.first().content)
        assertEquals(MemoryItemEntity.SOURCE_MANUAL, stored.first().source)

        val status = fixture.handler.handle("/status", "628111", "628111", "s1")
        val statusText = (status as ChatCommandHandler.Result.Handled).reply
        assertTrue(statusText.contains("Status Agent"))
        assertTrue(statusText.contains("DIIZINKAN"))
        assertTrue(statusText.contains("1 fakta"))

        val learning = fixture.handler.handle("/learning", "628111", "628111", "s1")
        assertTrue((learning as ChatCommandHandler.Result.Handled).reply.contains("Tidak ada kandidat"))
    }

    @Test
    fun chatCommands_compactReportsShortConversationHonestly() = runBlocking {
        val fixture = CommandFixture()
        val session = fixture.sessionRepo.getOrCreateSession("628111")
        val result = fixture.handler.handle("/compact", "628111", "628111", session.sessionId)
        assertTrue((result as ChatCommandHandler.Result.Handled).reply.contains("masih pendek"))
    }

    // ==========================================
    // Priority 6 — subagents run in the background
    // ==========================================

    @Test
    fun priority6_subagentReturnsImmediatelyAndDeliversItsResultLater() = runBlocking {
        val dao = FakeAgentTaskDao()
        val delivered = CompletableDeferred<Pair<AgentTaskEntity, TaskResult>>()
        val manager = SubAgentManager(
            taskDao = dao,
            executeTask = { task -> "hasil riset: ${task.task}" },
            onTaskFinished = { task, result -> delivered.complete(task to result) }
        )

        val launched = manager.launchTask(
            spec = SubAgentSpec(name = "Peneliti Web", task = "cari harga tiket Jakarta-Bandung"),
            conversationId = "628111"
        )

        // The main agent gets the id back without waiting for the work to finish.
        assertTrue(launched.id.startsWith("sub-"))
        assertEquals(AgentTaskEntity.STATUS_QUEUED, launched.status)
        assertEquals("628111", launched.conversationId)

        val (task, result) = withTimeout(10_000L) { delivered.await() }
        assertEquals(AgentTaskEntity.STATUS_COMPLETED, result.status)
        assertEquals("hasil riset: cari harga tiket Jakarta-Bandung", result.summary)
        assertEquals("628111", task.conversationId)
        assertEquals(
            AgentTaskEntity.STATUS_COMPLETED,
            dao.findById(launched.id)!!.status
        )
        assertTrue(dao.findById(launched.id)!!.result!!.startsWith("hasil riset:"))
    }

    @Test
    fun priority6_failingSubagentIsReportedAsFailedWithItsReason() = runBlocking {
        val dao = FakeAgentTaskDao()
        val delivered = CompletableDeferred<TaskResult>()
        val manager = SubAgentManager(
            taskDao = dao,
            executeTask = { throw IllegalStateException("model sub-agent belum dikonfigurasi") },
            onTaskFinished = { _, result -> delivered.complete(result) }
        )

        val launched = manager.launchTask(SubAgentSpec(task = "tugas mustahil"), "628222")
        val result = withTimeout(10_000L) { delivered.await() }

        assertEquals(AgentTaskEntity.STATUS_FAILED, result.status)
        assertTrue(result.error!!.contains("belum dikonfigurasi"))
        assertEquals(AgentTaskEntity.STATUS_FAILED, dao.findById(launched.id)!!.status)
    }
}
