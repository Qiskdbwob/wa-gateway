package com.example.agent

import com.example.agent.loop.AgentLoop
import com.example.agent.model.Agent
import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import com.example.agent.model.ToolCall
import com.example.agent.provider.ModelProvider
import com.example.agent.storage.InMemoryAgentSessionRepository
import com.example.agent.tool.AppendFileTool
import com.example.agent.tool.CopyPathTool
import com.example.agent.tool.DeletePathTool
import com.example.agent.tool.ListFilesTool
import com.example.agent.tool.MakeDirectoryTool
import com.example.agent.tool.MovePathTool
import com.example.agent.tool.LineWindow
import com.example.agent.tool.ReadFileTool
import com.example.agent.tool.ToolRegistry
import com.example.agent.tool.WorkspaceFileTool
import com.example.agent.tool.WriteFileTool
import com.example.agent.workspace.Workspace
import com.example.agent.workspace.WorkspaceSecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Phase 7 — workspace isolation.
 *
 * The security tests matter more than the happy paths: an agent that can be talked into reading
 * `/etc/passwd` or deleting a file outside its sandbox is worse than an agent without file tools.
 * The rest of the file proves the tools really work on disk and that the Agent Loop can call them
 * end to end.
 */
class WorkspaceToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var outsideRoot: File
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        workspaceRoot = temp.newFolder("workspace")
        outsideRoot = temp.newFolder("outside")
        File(outsideRoot, "secret.txt").writeText("rahasia perusahaan")
        workspace = Workspace(workspaceRoot)
    }

    /** Builds a tool argument payload the way a model would: JSON-escaped strings. */
    private fun args(vararg pairs: Pair<String, String>): String = pairs.joinToString(
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

    private fun path(vararg segments: String): File = File(workspaceRoot, segments.joinToString("/"))

    // ==========================================
    // Path guard
    // ==========================================

    @Test
    fun phase7_test1_pathTraversalIsRejected() {
        assertThrows(WorkspaceSecurityException::class.java) {
            workspace.resolve("../outside/secret.txt")
        }
        assertThrows(WorkspaceSecurityException::class.java) {
            workspace.resolve("sub/../../outside/secret.txt")
        }
        assertThrows(WorkspaceSecurityException::class.java) {
            workspace.resolve("")
        }
    }

    @Test
    fun phase7_test2_absolutePathStaysInsideTheWorkspace() {
        val resolved = workspace.resolve("/etc/passwd")

        assertTrue(resolved.path.startsWith(workspaceRoot.canonicalPath))
        assertEquals(path("etc", "passwd").canonicalPath, resolved.path)
        assertEquals("etc/passwd", workspace.relativePath(resolved))
    }

    @Test
    fun phase7_test3_symlinkLeavingTheWorkspaceIsRejected() {
        val link = File(workspaceRoot, "escape")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outsideRoot.toPath())
            true
        } catch (_: Exception) {
            false // filesystem without symlink support: nothing to verify here
        }
        if (!created) return

        assertThrows(WorkspaceSecurityException::class.java) {
            workspace.resolve("escape/secret.txt")
        }
    }

    // ==========================================
    // File tools
    // ==========================================

    @Test
    fun phase7_test4_writeThenReadAndAppendKeepsTheExactContent() = runBlocking {
        val content = "baris-1\n\"kutip\" & {\"kurung\"}"

        val write = WriteFileTool(workspace).execute(
            args("path" to "notes/hari-ini.md", "content" to content)
        )
        assertTrue(write.success)
        assertTrue(write.output.contains("notes/hari-ini.md"))
        assertEquals(write.metadata["bytes"], path("notes", "hari-ini.md").length().toString())

        val read = ReadFileTool(workspace).execute(args("path" to "notes/hari-ini.md"))
        assertTrue(read.success)
        assertTrue(read.output.contains(content))
        assertEquals("false", read.metadata["truncated"])

        val append = AppendFileTool(workspace).execute(
            args("path" to "notes/hari-ini.md", "content" to "\nbaris-2")
        )
        assertTrue(append.success)
        assertEquals(content + "\nbaris-2", path("notes", "hari-ini.md").readText())
    }

    @Test
    fun phase7_test5_contentThatLooksLikeJsonDoesNotConfuseArgumentParsing() = runBlocking {
        val tricky = "{\"path\":\"palsu.md\",\"content\":\"isi lain\"}"

        val write = WriteFileTool(workspace).execute(
            args("path" to "tricky.md", "content" to tricky)
        )

        assertTrue(write.success)
        assertEquals(tricky, path("tricky.md").readText())
    }

    @Test
    fun phase7_test6_listMoveCopyDeleteAndMkdir() = runBlocking {
        assertTrue(MakeDirectoryTool(workspace).execute(args("path" to "docs/laporan")).success)
        assertTrue(
            WriteFileTool(workspace).execute(
                args("path" to "docs/laporan/a.txt", "content" to "laporan A")
            ).success
        )

        val docsListing = ListFilesTool(workspace).execute(args("path" to "docs")).output
        assertTrue(docsListing.contains("laporan/"))

        val dirListing = ListFilesTool(workspace).execute(args("path" to "docs/laporan")).output
        assertTrue(dirListing.contains("a.txt"))

        assertTrue(
            CopyPathTool(workspace).execute(
                args("from" to "docs/laporan/a.txt", "to" to "docs/b.txt")
            ).success
        )
        assertTrue(path("docs", "b.txt").exists())

        assertTrue(
            MovePathTool(workspace).execute(
                args("from" to "docs/b.txt", "to" to "docs/c.txt")
            ).success
        )
        assertFalse(path("docs", "b.txt").exists())
        assertEquals("laporan A", path("docs", "c.txt").readText())

        // A directory that still has children must not be deleted without an explicit flag.
        val refused = DeletePathTool(workspace).execute(args("path" to "docs/laporan"))
        assertFalse(refused.success)
        assertTrue(refused.error!!.contains("recursive=true"))
        assertTrue(path("docs", "laporan", "a.txt").exists())

        val recursive = DeletePathTool(workspace).execute(
            """{"path":"docs/laporan","recursive":true}"""
        )
        assertTrue(recursive.success)
        assertFalse(path("docs", "laporan").exists())
    }

    @Test
    fun phase7_test7_outsideAndMissingPathsAreReportedNotThrown() = runBlocking {
        val outside = ReadFileTool(workspace).execute(args("path" to "../outside/secret.txt"))
        assertFalse(outside.success)
        assertTrue(outside.error!!.contains("luar workspace"))

        val missing = ReadFileTool(workspace).execute(args("path" to "tidak-ada.txt"))
        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("tidak ditemukan"))

        val outsideWrite = WriteFileTool(workspace).execute(
            args("path" to "../outside/curi.txt", "content" to "x")
        )
        assertFalse(outsideWrite.success)
        assertFalse(File(outsideRoot, "curi.txt").exists())

        val escapeDelete = DeletePathTool(workspace).execute(args("path" to "../outside/secret.txt"))
        assertFalse(escapeDelete.success)
        assertTrue(File(outsideRoot, "secret.txt").exists())
    }

    @Test
    fun phase7_test8_deletingTheWorkspaceRootIsRefused() = runBlocking {
        val result = DeletePathTool(workspace).execute("""{"path":".","recursive":true}""")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Akar workspace"))
        assertTrue(workspaceRoot.exists())
    }

    @Test
    fun phase7_test9_missingArgumentsProduceAToolError() = runBlocking {
        val noPath = WriteFileTool(workspace).execute(args("content" to "halo"))

        assertFalse(noPath.success)
        assertTrue(noPath.error!!.contains("'path'"))
    }

    // ==========================================
    // Registry + Agent Loop integration
    // ==========================================

    @Test
    fun phase7_test10_fileToolsExistOnlyWhenAWorkspaceIsProvided() {
        val withoutWorkspace = ToolRegistry.withBuiltIns()
        assertTrue(withoutWorkspace.all().none { it is WorkspaceFileTool })

        val withWorkspace = ToolRegistry.withBuiltIns(workspace)
        assertEquals(8, withWorkspace.all().count { it is WorkspaceFileTool })
        assertTrue(withWorkspace.definitions().any { it.name == "write_file" })
        assertTrue(withWorkspace.definitions().any { it.name == "current_time" })
    }

    @Test
    fun phase7_test11_agentLoopWritesAFileThroughAToolCall() = runBlocking {
        val provider = object : ModelProvider {
            override val id: String = "workspace-scripted"
            override val name: String = "Workspace Scripted"
            val requests = mutableListOf<ModelRequest>()

            override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
                requests.add(request)
                return if (requests.size == 1) {
                    Result.success(
                        ModelResponse(
                            content = "",
                            finishReason = "tool_calls",
                            toolCalls = listOf(
                                ToolCall(
                                    id = "call-write",
                                    name = "write_file",
                                    arguments = args(
                                        "path" to "catatan.md",
                                        "content" to "beli kopi\nbeli susu"
                                    )
                                )
                            )
                        )
                    )
                } else {
                    Result.success(ModelResponse(content = "Catatan tersimpan.", finishReason = "stop"))
                }
            }
        }

        val loop = AgentLoop(
            agent = Agent(enabled = true),
            modelProvider = provider,
            sessionRepository = InMemoryAgentSessionRepository(),
            toolRegistry = ToolRegistry.withBuiltIns(workspace)
        )

        val result = loop.processMessage("conv-workspace", "Simpan catatan: beli kopi, beli susu")

        assertTrue(result.isSuccess)
        assertEquals("Catatan tersimpan.", result.getOrThrow())
        assertEquals("beli kopi\nbeli susu", path("catatan.md").readText())
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests[1].messages.last().content.contains("catatan.md"))
        assertTrue(loop.activityLogs.value.any { it.contains("TOOL_RESULT") && it.contains("write_file") })
    }

    // ==========================================
    // read_file paging (offset/limit)
    // ==========================================

    @Test
    fun readFileCanBePagedWithOffsetAndLimit() = runBlocking {
        path("laporan.txt").writeText((1..5).joinToString("\n") { "baris-$it" })

        val page = ReadFileTool(workspace).execute("""{"path":"laporan.txt","offset":2,"limit":2}""")

        assertTrue(page.success)
        assertTrue(page.output.contains("baris-2"))
        assertTrue(page.output.contains("baris-3"))
        assertFalse(page.output.contains("baris-4"))
        assertEquals("2-3/5", page.metadata["lines"])
        // The model must be told how to continue instead of guessing.
        assertEquals("true", page.metadata["truncated"])
        assertTrue(page.output.contains("offset=4"))
    }

    @Test
    fun readFileWithoutArgsStillReadsFromTheTop() = runBlocking {
        path("kecil.txt").writeText("satu\ndua")

        val whole = ReadFileTool(workspace).execute(args("path" to "kecil.txt"))

        assertTrue(whole.success)
        assertEquals("1-2/2", whole.metadata["lines"])
        assertEquals("false", whole.metadata["truncated"])
        assertFalse(whole.output.contains("lanjutkan dengan offset"))
    }

    @Test
    fun readFileWindowPastTheEndIsHonestInsteadOfAnError() = runBlocking {
        path("pendek.txt").writeText("hanya satu baris")

        val beyond = ReadFileTool(workspace).execute("""{"path":"pendek.txt","offset":99}""")

        assertTrue(beyond.success)
        assertEquals("1-1/1", beyond.metadata["lines"])
        // Nothing to continue with: the file really is that short.
        assertEquals("false", beyond.metadata["truncated"])
    }

    @Test
    fun lineWindowClampsLimitAndOffset() {
        val text = (1..10).joinToString("\n") { "L$it" }

        val window = LineWindow.slice(text, offset = 3, limit = 4)
        assertEquals("L3\nL4\nL5\nL6", window.text)
        assertEquals(3, window.fromLine)
        assertEquals(6, window.toLine)
        assertEquals(10, window.totalLines)
        assertTrue(window.hasMore)

        // A limit of zero or a negative offset must never produce an empty/garbage window.
        assertEquals(1, LineWindow.slice(text, offset = -5, limit = 0).fromLine)
        assertEquals("L1", LineWindow.slice(text, offset = 0, limit = 1).text)
        // Huge limits are clamped to the file, not to the cap.
        assertEquals(10, LineWindow.slice(text, offset = 1, limit = 99_999).toLine)
    }
}
