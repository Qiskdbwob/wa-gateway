package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.workspace.Workspace
import com.example.agent.workspace.WorkspaceSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Host-owned context supplied by the Agent Loop; model arguments cannot spoof it. */
data class ToolExecutionContext(
    val agentId: String,
    val sessionId: String,
    val conversationId: String,
    val channel: String,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun direct(): ToolExecutionContext = ToolExecutionContext(
            agentId = "default-agent",
            sessionId = "direct",
            conversationId = "direct",
            channel = "direct"
        )
    }
}

/** A tool that needs trusted runtime context without changing the original Tool contract. */
interface ContextAwareTool : Tool {
    suspend fun execute(input: String, context: ToolExecutionContext): ToolResult

    override suspend fun execute(input: String): ToolResult = execute(input, ToolExecutionContext.direct())
}

/** One direct process invocation. There is deliberately no host shell in this command. */
data class TerminalProcessCommand(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: File,
    val timeoutMs: Long
)

data class TerminalExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val error: String? = null
)

fun interface TerminalCommandRunner {
    suspend fun run(command: TerminalProcessCommand): TerminalExecutionResult
}

/**
 * Executes argv directly and drains stdout/stderr concurrently. Using argv instead of `sh -c`
 * means shell operators in model text remain ordinary arguments and cannot start another command.
 */
class ProcessTerminalCommandRunner(
    private val maxStreamChars: Int = WorkspaceTerminalTool.MAX_STREAM_CHARS
) : TerminalCommandRunner {

    private data class StreamCapture(val text: String, val truncated: Boolean)

    override suspend fun run(command: TerminalProcessCommand): TerminalExecutionResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(command.executable) + command.arguments)
                .directory(command.workingDirectory)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            return@withContext TerminalExecutionResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                error = "Gagal menjalankan '${command.executable}': ${e.message ?: e::class.java.simpleName}"
            )
        }

        val executor = Executors.newFixedThreadPool(3)
        val stdoutFuture = executor.submit(Callable { readBounded(process.inputStream, maxStreamChars) })
        val stderrFuture = executor.submit(Callable { readBounded(process.errorStream, maxStreamChars) })
        val exitFuture = executor.submit(Callable { process.waitFor() })

        var timedOut = false
        var exitCode = -1
        try {
            exitCode = exitFuture.get(command.timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            timedOut = true
            process.destroy()
            exitCode = try {
                exitFuture.get(750L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                -1
            }
        } catch (e: Exception) {
            process.destroy()
            executor.shutdownNow()
            return@withContext TerminalExecutionResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                error = "Gagal menunggu proses: ${e.message ?: e::class.java.simpleName}"
            )
        }

        val stdout = capture(stdoutFuture)
        val stderr = capture(stderrFuture)
        executor.shutdownNow()
        TerminalExecutionResult(
            stdout = stdout?.text.orEmpty(),
            stderr = stderr?.text.orEmpty(),
            exitCode = exitCode,
            timedOut = timedOut,
            stdoutTruncated = stdout?.truncated == true,
            stderrTruncated = stderr?.truncated == true,
            error = if (timedOut) "Perintah melewati batas waktu." else null
        )
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): StreamCapture {
        val safeLimit = limit.coerceIn(1, WorkspaceTerminalTool.MAX_STREAM_CHARS)
        val output = StringBuilder(minOf(safeLimit, 4096))
        var truncated = false
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val remaining = safeLimit - output.length
                    if (remaining > 0) {
                        output.append(buffer, 0, minOf(read, remaining))
                    }
                    if (read > remaining) truncated = true
                }
            }
        } catch (_: IOException) {
            // A killed process may close the pipe while this reader is draining it. Whatever was
            // captured before that remains useful and is returned below.
        }
        return StreamCapture(output.toString(), truncated)
    }

    private fun <T> capture(future: Future<T>): T? = try {
        future.get(2, TimeUnit.SECONDS)
    } catch (_: Exception) {
        future.cancel(true)
        null
    }
}

/**
 * Phase 8 — a deliberately small terminal for the model.
 *
 * It is a real process runner, not a fabricated tool result, but only exposes non-mutating
 * workspace commands during Phase 8. Every filesystem argument is canonicalised through
 * [Workspace] before execution. Shell syntax is tokenised but never interpreted, so `;`, pipes,
 * redirects, substitutions, and command chaining cannot escape the allowlist.
 */
class WorkspaceTerminalTool(
    private val workspace: Workspace,
    private val runner: TerminalCommandRunner = ProcessTerminalCommandRunner()
) : ContextAwareTool {

    override val id: String = "builtin.terminal"
    override val name: String = "run_terminal_command"
    override val description: String =
        "Menjalankan perintah terminal baca-terbaca di dalam workspace. Setiap percakapan memiliki cwd sendiri. " +
            "Perintah yang didukung: pwd, cd, ls, cat, grep, wc, head, tail. Perintah ini belum mengubah file, " +
            "mengakses jaringan, atau memasang paket; gunakan tool file untuk perubahan workspace."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "Satu perintah tanpa pipe/redirection/chaining, contoh: cd documents, ls, cat notes/todo.md, grep topic catatan.md."
            },
            "timeout_seconds": {
              "type": "integer",
              "minimum": 1,
              "maximum": 30,
              "description": "Batas waktu proses, default 15 detik dan maksimum 30 detik."
            }
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    private data class SessionState(var relativeDirectory: String = ".")

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun execute(input: String, context: ToolExecutionContext): ToolResult {
        val key = "${context.agentId}:${context.channel}:${context.conversationId.ifBlank { context.sessionId }}"
        val lock = sessionLocks.computeIfAbsent(key) { Mutex() }
        val session = sessions.computeIfAbsent(key) { SessionState() }

        return lock.withLock {
            try {
                runGuarded(input, session)
            } catch (e: WorkspaceSecurityException) {
                fail(e.message ?: "Path berada di luar workspace dan ditolak.")
            } catch (e: ToolInputException) {
                fail(e.message ?: "Argumen terminal tidak valid.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Tool terminal gagal: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    private suspend fun runGuarded(input: String, session: SessionState): ToolResult {
        val command = JsonArgs.string(input, "command")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ToolInputException("Argumen 'command' wajib diisi.")
        if (command.length > MAX_COMMAND_CHARS) {
            throw ToolInputException("Perintah terlalu panjang (maksimum $MAX_COMMAND_CHARS karakter).")
        }
        if (command.contains('\u0000')) {
            throw ToolInputException("Perintah mengandung karakter tidak valid.")
        }

        val timeoutSeconds = (JsonArgs.int(input, "timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        val tokens = tokenize(command)
        if (tokens.isEmpty()) throw ToolInputException("Perintah tidak boleh kosong.")

        val executable = tokens.first().lowercase(Locale.ROOT)
        if (executable.contains('/') || executable.contains('\\')) {
            throw ToolInputException("Path executable tidak diizinkan; gunakan nama perintah yang didukung.")
        }

        if (executable == "pwd") {
            requireNoArguments(executable, tokens, 1)
            val cwd = workspace.resolve(session.relativeDirectory)
            return success(
                output = cwd.absolutePath,
                metadata = mapOf("cwd" to cwd.absolutePath, "session" to session.relativeDirectory)
            )
        }

        if (executable == "cd") {
            requireNoArguments(executable, tokens, 2)
            val target = resolveSessionPath(session, tokens[1])
            if (!target.isDirectory) {
                return fail("Path '${tokens[1]}' bukan direktori di workspace.")
            }
            session.relativeDirectory = workspace.relativePath(target)
            return success(
                output = "Working directory: ${target.absolutePath}",
                metadata = mapOf("cwd" to target.absolutePath, "session" to session.relativeDirectory)
            )
        }

        val prepared = prepareProcessCommand(executable, tokens, session)
        val result = runner.run(
            TerminalProcessCommand(
                executable = executable,
                arguments = prepared.arguments,
                workingDirectory = prepared.workingDirectory,
                timeoutMs = timeoutSeconds * 1_000L
            )
        )

        val metadata = mapOf(
            "command" to command,
            "exitCode" to result.exitCode.toString(),
            "timedOut" to result.timedOut.toString(),
            "cwd" to prepared.workingDirectory.absolutePath,
            "stdoutTruncated" to result.stdoutTruncated.toString(),
            "stderrTruncated" to result.stderrTruncated.toString()
        )

        if (result.error != null || result.timedOut || result.exitCode != 0) {
            val detail = result.error
                ?: result.stderr.trim().ifBlank { "Proses berakhir dengan exit code ${result.exitCode}." }
            return ToolResult(
                success = false,
                output = result.stdout.trimEnd(),
                error = detail,
                metadata = metadata
            )
        }

        return success(result.stdout.trimEnd(), metadata + mapOf("stderr" to result.stderr.trim()))
    }

    private data class PreparedCommand(
        val arguments: List<String>,
        val workingDirectory: File
    )

    private fun prepareProcessCommand(
        executable: String,
        tokens: List<String>,
        session: SessionState
    ): PreparedCommand {
        val workingDirectory = workspace.resolve(session.relativeDirectory)
        val args = tokens.drop(1)

        fun onePath(): Pair<List<String>, File> {
            if (args.size != 1) throw usage(executable)
            val target = resolveSessionPath(session, args.single())
            return listOf(target.absolutePath) to target
        }

        return when (executable) {
            "ls" -> {
                val flags = mutableListOf<String>()
                var pathToken: String? = null
                for (token in args) {
                    if (token.startsWith("-")) {
                        if (token !in setOf("-l", "-a", "-la", "-al")) throw usage(executable)
                        flags += token
                    } else if (pathToken == null) {
                        pathToken = token
                    } else {
                        throw usage(executable)
                    }
                }
                val target = resolveSessionPath(session, pathToken ?: ".")
                PreparedCommand(flags + listOf(target.absolutePath), workingDirectory)
            }

            "cat" -> {
                val (commandArgs, _) = onePath()
                PreparedCommand(commandArgs, workingDirectory)
            }

            "grep" -> {
                if (args.size != 2) throw usage(executable)
                val pattern = args[0]
                if (pattern.isBlank()) throw ToolInputException("Pola grep tidak boleh kosong.")
                val target = resolveSessionPath(session, args[1])
                PreparedCommand(listOf("--", pattern, target.absolutePath), workingDirectory)
            }

            "wc" -> {
                val (flag, pathToken) = when (args.size) {
                    1 -> "" to args[0]
                    2 -> {
                        if (args[0] !in setOf("-l", "-w", "-c")) throw usage(executable)
                        args[0] to args[1]
                    }
                    else -> throw usage(executable)
                }
                val target = resolveSessionPath(session, pathToken)
                PreparedCommand(listOf(flag, target.absolutePath).filter { it.isNotBlank() }, workingDirectory)
            }

            "head", "tail" -> {
                val pathToken: String
                val commandArgs: List<String>
                when (args.size) {
                    1 -> {
                        pathToken = args[0]
                        commandArgs = emptyList()
                    }
                    3 -> {
                        if (args[0] != "-n") throw usage(executable)
                        val count = args[1].toIntOrNull()
                            ?.takeIf { it in 1..MAX_HEAD_TAIL_LINES }
                            ?: throw ToolInputException("Jumlah baris -n harus antara 1 dan $MAX_HEAD_TAIL_LINES.")
                        pathToken = args[2]
                        commandArgs = listOf("-n", count.toString())
                    }
                    else -> throw usage(executable)
                }
                val target = resolveSessionPath(session, pathToken)
                PreparedCommand(commandArgs + listOf(target.absolutePath), workingDirectory)
            }

            else -> throw ToolInputException(
                "Perintah '$executable' belum didukung. Gunakan salah satu: ${SUPPORTED_COMMANDS.joinToString(", ")}."
            )
        }
    }

    private fun resolveSessionPath(session: SessionState, requested: String): File {
        val combined = when {
            requested.startsWith('/') || requested.startsWith('\\') -> requested
            session.relativeDirectory == "." -> requested
            else -> "${session.relativeDirectory}/$requested"
        }
        return workspace.resolve(combined)
    }

    private fun requireNoArguments(executable: String, tokens: List<String>, expectedSize: Int) {
        if (tokens.size != expectedSize) throw usage(executable)
    }

    private fun usage(executable: String): ToolInputException {
        val syntax = when (executable) {
            "cd" -> "cd <path>"
            "ls" -> "ls [-l|-a|-la] [path]"
            "cat" -> "cat <path>"
            "grep" -> "grep <pattern> <path>"
            "wc" -> "wc [-l|-w|-c] <path>"
            "head", "tail" -> "$executable [-n <1..$MAX_HEAD_TAIL_LINES>] <path>"
            else -> executable
        }
        return ToolInputException("Sintaks tidak valid. Gunakan: $syntax")
    }

    /** Tokenises quotes/escapes without invoking a shell. */
    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        fun flush() {
            if (current.isNotEmpty()) {
                if (tokens.size >= MAX_ARGUMENTS) {
                    throw ToolInputException("Perintah memiliki terlalu banyak argumen.")
                }
                tokens += current.toString()
                current.clear()
            }
        }

        for (character in command) {
            if (escaping) {
                current.append(character)
                escaping = false
                continue
            }
            if (character == '\\' && quote != '\'') {
                escaping = true
                continue
            }
            if (quote != null) {
                if (character == quote) quote = null else current.append(character)
                continue
            }
            when (character) {
                '\'', '"' -> quote = character
                ' ', '\t', '\r', '\n' -> flush()
                else -> current.append(character)
            }
        }

        if (escaping) throw ToolInputException("Escape string tidak lengkap.")
        if (quote != null) throw ToolInputException("Kutipan string tidak lengkap.")
        flush()
        return tokens
    }

    private fun success(output: String, metadata: Map<String, String>): ToolResult =
        ToolResult(success = true, output = output, metadata = metadata)

    private fun fail(error: String): ToolResult = ToolResult(success = false, output = "", error = error)

    companion object {
        const val MAX_STREAM_CHARS = 15_000
        const val MAX_COMMAND_CHARS = 4_000
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 30
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val MAX_HEAD_TAIL_LINES = 1_000
        val SUPPORTED_COMMANDS = listOf("pwd", "cd", "ls", "cat", "grep", "wc", "head", "tail")
        private const val MAX_ARGUMENTS = 32
    }
}
