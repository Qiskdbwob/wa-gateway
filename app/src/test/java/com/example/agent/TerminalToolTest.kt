package com.example.agent

import com.example.agent.loop.AgentLoop
import com.example.agent.model.Agent
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import com.example.agent.model.ToolCall
import com.example.agent.provider.ModelProvider
import com.example.agent.storage.InMemoryAgentSessionRepository
import com.example.agent.tool.ProcessTerminalCommandRunner
import com.example.agent.tool.TerminalCommandRunner
import com.example.agent.tool.TerminalExecutionResult
import com.example.agent.tool.TerminalProcessCommand
import com.example.agent.tool.ToolExecutionContext
import com.example.agent.tool.ToolRegistry
import com.example.agent.tool.WorkspaceTerminalTool
import com.example.agent.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Phase 8 — restricted workspace terminal and its Agent Loop integration. */
class TerminalToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class RecordingRunner(
        var result: TerminalExecutionResult = TerminalExecutionResult(
            stdout = "ok\n",
            stderr = "",
            exitCode = 0,
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = false
        )
    ) : TerminalCommandRunner {
        val invocations = mutableListOf<TerminalProcessCommand>()

        override suspend fun run(command: TerminalProcessCommand): TerminalExecutionResult {
            invocations += command
            return result
        }
    }

    private fun context(conversationId: String) = ToolExecutionContext(
        agentId = "default-agent",
        sessionId = "session-$conversationId",
        conversationId = conversationId,
        channel = "test"
    )

    private fun args(vararg values: Pair<String, String>): String = values.joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}"
    ) { (key, value) ->
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        "\"$key\":\"$escaped\""
    }

    @Test
    fun phase8_test1_workingDirectoryIsPersistentAndIsolatedPerConversation() = runBlocking {
        val root = temp.newFolder("workspace")
        val notes = File(root, "notes").apply { mkdirs() }
        val workspace = Workspace(root)
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(workspace, runner)

        assertTrue(tool.execute(args("command" to "cd notes"), context("chat-a")).success)
        val cwdA = tool.execute(args("command" to "pwd"), context("chat-a"))
        val cwdB = tool.execute(args("command" to "pwd"), context("chat-b"))

        assertTrue(cwdA.success)
        assertEquals(notes.canonicalPath, cwdA.output)
        assertTrue(cwdB.success)
        assertEquals(root.canonicalPath, cwdB.output)
        // cd and pwd are handled by the workspace session; no host process is needed.
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun phase8_test2_pathTraversalIsRejectedAndDoesNotChangeCwd() = runBlocking {
        val root = temp.newFolder("workspace")
        File(root, "docs").mkdirs()
        val workspace = Workspace(root)
        val tool = WorkspaceTerminalTool(workspace, RecordingRunner())
        val ctx = context("chat-a")

        assertTrue(tool.execute(args("command" to "cd docs"), ctx).success)
        val rejected = tool.execute(args("command" to "cd ../../outside"), ctx)
        val cwd = tool.execute(args("command" to "pwd"), ctx)

        assertFalse(rejected.success)
        assertTrue(rejected.error!!.contains("luar workspace"))
        assertEquals(File(root, "docs").canonicalPath, cwd.output)
    }

    @Test
    fun phase8_test3_processUsesDirectArgvAndCanonicalWorkspacePath() = runBlocking {
        val root = temp.newFolder("workspace")
        val notes = File(root, "notes").apply { mkdirs() }
        val todo = File(notes, "todo.txt").apply { writeText("beli kopi") }
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)
        val ctx = context("chat-a")

        tool.execute(args("command" to "cd notes"), ctx)
        val result = tool.execute(args("command" to "cat todo.txt"), ctx)

        assertTrue(result.success)
        assertEquals(1, runner.invocations.size)
        val invocation = runner.invocations.single()
        assertEquals("cat", invocation.executable)
        assertEquals(listOf(todo.canonicalPath), invocation.arguments)
        assertEquals(notes.canonicalPath, invocation.workingDirectory.canonicalPath)
    }

    @Test
    fun phase8_test4_shellChainingCannotStartASecondProcess() = runBlocking {
        val root = temp.newFolder("workspace")
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)

        val result = tool.execute(
            args("command" to "cat notes/a.txt; cat outside.txt"),
            context("chat-a")
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Sintaks tidak valid"))
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun phase8_test5_timeoutIsClampedAndCommandOutputIsBounded() = runBlocking {
        val root = temp.newFolder("workspace")
        val runner = RecordingRunner().apply {
            result = TerminalExecutionResult(
                stdout = "abc\n",
                stderr = "",
                exitCode = 0,
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false
            )
        }
        val tool = WorkspaceTerminalTool(Workspace(root), runner)

        val result = tool.execute(
            args("command" to "ls", "timeout_seconds" to "999"),
            context("chat-a")
        )

        assertTrue(result.success)
        assertEquals(30_000L, runner.invocations.single().timeoutMs)
    }

    @Test
    fun phase8_test6_processRunnerDrainsBothStreamsAndCapsThem() = runBlocking {
        val runner = ProcessTerminalCommandRunner(maxStreamChars = 8)
        val result = runner.run(
            TerminalProcessCommand(
                executable = "sh",
                arguments = listOf("-c", "printf 'abcdefghij'; printf '1234567890' >&2"),
                workingDirectory = temp.root,
                timeoutMs = 3_000L
            )
        )

        assertEquals(0, result.exitCode)
        assertEquals("abcdefgh", result.stdout)
        assertEquals("12345678", result.stderr)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
    }

    @Test
    fun phase8_test6b_processRunnerTimesOutWithoutHanging() = runBlocking {
        val runner = ProcessTerminalCommandRunner()
        val result = runner.run(
            TerminalProcessCommand(
                executable = "sh",
                arguments = listOf("-c", "while :; do :; done"),
                workingDirectory = temp.root,
                timeoutMs = 100L
            )
        )

        assertTrue(result.timedOut)
        assertTrue(result.error!!.contains("batas waktu"))
    }

    @Test
    fun phase8_test7_terminalExistsOnlyWithWorkspaceAndIsAdvertised() {
        assertTrue(ToolRegistry.withBuiltIns().all().none { it is WorkspaceTerminalTool })

        val registry = ToolRegistry.withBuiltIns(
            Workspace(temp.newFolder("workspace")),
            RecordingRunner()
        )
        val terminal = registry.get("run_terminal_command")

        assertTrue(terminal is WorkspaceTerminalTool)
        assertTrue(registry.definitions().any { it.name == "run_terminal_command" })
    }

    @Test
    fun phase8_test8_agentLoopSuppliesTrustedConversationContext() = runBlocking {
        val root = temp.newFolder("workspace")
        File(root, "docs").mkdirs()
        val runner = RecordingRunner()
        val provider = object : ModelProvider {
            override val id = "terminal-scripted"
            override val name = "Terminal Scripted"
            var calls = 0

            override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
                calls++
                return if (calls == 1) {
                    Result.success(
                        ModelResponse(
                            content = "",
                            finishReason = "tool_calls",
                            toolCalls = listOf(
                                ToolCall(
                                    id = "call-cd",
                                    name = "run_terminal_command",
                                    arguments = args("command" to "cd docs")
                                )
                            )
                        )
                    )
                } else {
                    val toolMessage = request.messages.last { it.role == AgentRole.TOOL }
                    assertTrue(toolMessage.content.contains(File(root, "docs").canonicalPath))
                    Result.success(ModelResponse(content = "Folder docs siap.", finishReason = "stop"))
                }
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true, maxToolIterations = 2),
            modelProvider = provider,
            sessionRepository = InMemoryAgentSessionRepository(),
            toolRegistry = ToolRegistry(listOf(WorkspaceTerminalTool(Workspace(root), runner)))
        )

        val result = loop.processMessage("conv-terminal-context", "Masuk folder docs")

        assertTrue(result.isSuccess)
        assertEquals("Folder docs siap.", result.getOrThrow())
        assertEquals(2, provider.calls)
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_RESULT") && it.contains("run_terminal_command") })
    }

    @Test
    fun phase8_test9_argvForLsGrepWcHeadAndTail() = runBlocking {
        val root = temp.newFolder("workspace")
        val docs = File(root, "docs").apply { mkdirs() }
        val notes = File(root, "notes.txt").apply { writeText("satu\ndua\ntiga") }
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)
        val ctx = context("chat-argv")

        // ls without flag: argv == [<absolute target>] (cwd == workspace root).
        assertTrue(tool.execute(args("command" to "ls"), ctx).success)
        assertEquals(listOf(root.canonicalPath), runner.invocations.last().arguments)
        assertEquals("ls", runner.invocations.last().executable)
        assertEquals(root.canonicalPath, runner.invocations.last().workingDirectory.canonicalPath)

        // ls -l: argv == ["-l", <absolute target>].
        assertTrue(tool.execute(args("command" to "ls -l"), ctx).success)
        assertEquals(listOf("-l", root.canonicalPath), runner.invocations.last().arguments)

        // ls -la docs: argv == ["-la", <absolute docs>].
        assertTrue(tool.execute(args("command" to "ls -la docs"), ctx).success)
        assertEquals(listOf("-la", docs.canonicalPath), runner.invocations.last().arguments)

        // grep <pola> <path>: argv == ["--", pola, <absolute path>].
        assertTrue(tool.execute(args("command" to "grep topic notes.txt"), ctx).success)
        assertEquals("grep", runner.invocations.last().executable)
        assertEquals(listOf("--", "topic", notes.canonicalPath), runner.invocations.last().arguments)

        // wc <path>: argv == [<absolute path>].
        assertTrue(tool.execute(args("command" to "wc notes.txt"), ctx).success)
        assertEquals("wc", runner.invocations.last().executable)
        assertEquals(listOf(notes.canonicalPath), runner.invocations.last().arguments)

        // wc -l <path>: argv == ["-l", <absolute path>].
        assertTrue(tool.execute(args("command" to "wc -l notes.txt"), ctx).success)
        assertEquals(listOf("-l", notes.canonicalPath), runner.invocations.last().arguments)

        // head <path>: argv == [<absolute path>].
        assertTrue(tool.execute(args("command" to "head notes.txt"), ctx).success)
        assertEquals("head", runner.invocations.last().executable)
        assertEquals(listOf(notes.canonicalPath), runner.invocations.last().arguments)

        // head -n 3 <path>: argv == ["-n", "3", <absolute path>].
        assertTrue(tool.execute(args("command" to "head -n 3 notes.txt"), ctx).success)
        assertEquals(listOf("-n", "3", notes.canonicalPath), runner.invocations.last().arguments)

        // tail -n 2 <path>: argv == ["-n", "2", <absolute path>].
        assertTrue(tool.execute(args("command" to "tail -n 2 notes.txt"), ctx).success)
        assertEquals("tail", runner.invocations.last().executable)
        assertEquals(listOf("-n", "2", notes.canonicalPath), runner.invocations.last().arguments)

        assertEquals(9, runner.invocations.size)
    }

    @Test
    fun phase8_test10_pathsAreCanonicalisedAgainstSessionCwdForNonCdCommands() = runBlocking {
        val root = temp.newFolder("workspace")
        val docs = File(root, "docs").apply { mkdirs() }
        val nested = File(docs, "nested.txt").apply { writeText("halo") }
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)
        val ctx = context("chat-canoncial")

        assertTrue(tool.execute(args("command" to "cd docs"), ctx).success)
        assertTrue(tool.execute(args("command" to "head nested.txt"), ctx).success)
        assertEquals(listOf(nested.canonicalPath), runner.invocations.last().arguments)
        assertEquals(docs.canonicalPath, runner.invocations.last().workingDirectory.canonicalPath)

        // Relative path segments are canonicalised relative to the session cwd, not the root.
        assertTrue(tool.execute(args("command" to "wc ./nested.txt"), ctx).success)
        assertEquals(listOf(nested.canonicalPath), runner.invocations.last().arguments)
        assertTrue(tool.execute(args("command" to "grep halo ./nested.txt"), ctx).success)
        assertEquals(listOf("--", "halo", nested.canonicalPath), runner.invocations.last().arguments)
    }

    @Test
    fun phase8_test11_nonCdCommandsRejectPathsOutsideWorkspaceWithoutRunning() = runBlocking {
        val root = temp.newFolder("workspace")
        val workspace = Workspace(root)
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(workspace, runner)
        val ctx = context("chat-outside")

        val commands = listOf(
            "grep x ../../outside.txt",
            "wc ../../outside.txt",
            "head ../../outside.txt",
            "ls ../../outside"
        )
        for (command in commands) {
            val result = tool.execute(args("command" to command), ctx)
            assertFalse("'$command' seharusnya ditolak", result.success)
            assertTrue(
                "'$command' error harus menyebut luar workspace",
                result.error!!.contains("luar workspace")
            )
        }
        // Tidak ada proses yang boleh dijalankan untuk path di luar workspace.
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun phase8_test12_headAndTailLineCountIsValidatedBeforeRunning() = runBlocking {
        val root = temp.newFolder("workspace")
        val notes = File(root, "notes.txt").apply { writeText("satu\ndua\ntiga") }
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)
        val ctx = context("chat-count")

        val invalid = listOf(
            "head -n 0 ${notes.name}",
            "head -n 5000 ${notes.name}",
            "tail -n 0 ${notes.name}",
            "tail -n 5000 ${notes.name}"
        )
        for (command in invalid) {
            val result = tool.execute(args("command" to command), ctx)
            assertFalse("'$command' seharusnya ditolak", result.success)
            assertTrue(
                "'$command' error harus menyebut Jumlah baris",
                result.error!!.contains("Jumlah baris")
            )
        }
        assertTrue(runner.invocations.isEmpty())

        // Batas atas yang valid (1..1000) tetap diizinkan dan menjalankan proses.
        assertTrue(tool.execute(args("command" to "head -n 1000 ${notes.name}"), ctx).success)
        assertEquals(
            listOf("-n", "1000", notes.canonicalPath),
            runner.invocations.last().arguments
        )
    }

    @Test
    fun phase8_test13_invalidSyntaxForGrepWcAndUnknownFlagIsRejected() = runBlocking {
        val root = temp.newFolder("workspace")
        val notes = File(root, "notes.txt").apply { writeText("satu\ndua") }
        val runner = RecordingRunner()
        val tool = WorkspaceTerminalTool(Workspace(root), runner)
        val ctx = context("chat-syntax")

        val invalidSyntax = listOf(
            "grep hanya-pola",        // 1 argumen, kurang path
            "grep a b c",             // 3 argumen, terlalu banyak
            "wc ${notes.name} extra", // flag tak dikenal + argumen berlebih
            "wc -x ${notes.name}"     // flag tak dikenal
        )
        for (command in invalidSyntax) {
            val result = tool.execute(args("command" to command), ctx)
            assertFalse("'$command' seharusnya ditolak", result.success)
            assertTrue(
                "'$command' error harus menyebut Sintaks tidak valid",
                result.error!!.contains("Sintaks tidak valid")
            )
        }
        assertTrue(runner.invocations.isEmpty())
    }

}
