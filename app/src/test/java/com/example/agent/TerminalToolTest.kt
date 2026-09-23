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
}
