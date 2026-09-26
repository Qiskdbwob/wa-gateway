package com.example.agent.terminal

import com.example.agent.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/** Result of a one-shot shell command. */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val cwd: String
) {
    val success: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Runs a single shell command on the device shell (`/system/bin/sh`, i.e. mksh + toybox on
 * Android) with the agent workspace as working directory.
 *
 * Why the device shell and not a bundled Linux: shipping a rootfs/proot would add hundreds of
 * megabytes per ABI to an app that must stay installable. Android already ships a POSIX shell
 * with toybox utilities, so `sh`, `ls`, `grep`, `sed`, `awk`, `tar`, `zip` and — on most
 * devices — `curl` and `wget` are available with zero payload. [availableCommands] reports
 * exactly what the device really has instead of pretending; `python`/`node`/`git`/`gh` are
 * only present when the user installed them (e.g. a Termux toolchain on PATH).
 *
 * The command is a *one-shot* process: it cannot change this app's state. Persistent state
 * (cwd, exports) belongs to [TerminalSession].
 */
class ShellRunner(
    private val workspace: Workspace,
    /** Extra directories prepended to PATH, e.g. the workspace `bin/` folder. */
    private val extraPath: List<String> = emptyList(),
    private val shellPath: String = DEFAULT_SHELL
) {

    /** Directory for scratch files created by the model, always inside the workspace. */
    val binDirectory: File = File(workspace.root, "bin").apply { mkdirs() }

    /** Directory for outputs such as browser screenshots. */
    val outputDirectory: File = File(workspace.root, "output").apply { mkdirs() }

    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        workingDirectory: String? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        val cwd = resolveWorkingDirectory(workingDirectory)
        val effectiveTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        val process = try {
            ProcessBuilder(shellPath, "-c", command)
                .directory(cwd)
                .redirectErrorStream(true)
                .apply { environment().putAll(buildEnvironment(cwd)) }
                .start()
        } catch (e: Exception) {
            return@withContext ShellResult(
                exitCode = -1,
                output = "Gagal menjalankan shell: ${e.message ?: e.javaClass.simpleName}",
                timedOut = false,
                truncated = false,
                cwd = cwd.absolutePath
            )
        }

        // The reader runs on its own thread so a command that produces a lot of output can
        // never deadlock against waitFor() below.
        val buffer = StringBuffer()
        var truncated = false
        val reader = thread(isDaemon = true, name = "shell-reader") {
            try {
                process.inputStream.bufferedReader().use { readerStream ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val read = readerStream.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) {
                            val room = maxOutputChars - buffer.length
                            if (room <= 0) {
                                truncated = true
                            } else {
                                val take = minOf(read, room)
                                buffer.append(chunk, 0, take)
                                if (take < read) truncated = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Process died or the pipe closed; the exit code below is the real signal.
            }
        }

        // `Process.waitFor(timeout)` needs API 26 while this app supports API 24, so the
        // timeout is enforced by a watchdog that kills the process instead.
        var timedOut = false
        coroutineScope {
            val watchdog = launch {
                delay(effectiveTimeout)
                if (isRunning(process)) {
                    timedOut = true
                    process.destroy()
                }
            }
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                // Reported as a failure by the exit code below.
            } finally {
                watchdog.cancel()
            }
        }
        if (timedOut && isRunning(process)) {
            process.destroy()
        }
        reader.join(1_000)

        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }
        val text = synchronized(buffer) { buffer.toString() }
        val note = if (timedOut) {
            "\n...(perintah dihentikan setelah ${effectiveTimeout / 1000} detik)"
        } else if (truncated) {
            "\n...(output dipotong pada $maxOutputChars karakter)"
        } else {
            ""
        }

        ShellResult(
            exitCode = exitCode,
            output = text + note,
            timedOut = timedOut,
            truncated = truncated,
            cwd = cwd.absolutePath
        )
    }

    /**
     * Which of the interesting binaries actually exist on this device. One process, no
     * guessing — the model is told the truth about its own toolchain.
     */
    suspend fun availableCommands(): Map<String, String> {
        val probes = CANDIDATE_BINARIES.joinToString(" ") { name ->
            "command -v $name >/dev/null 2>&1 && echo \"$name=\$(command -v $name)\""
        }
        val result = run(probes, timeoutMs = 15_000, maxOutputChars = 4_000)
        val found = LinkedHashMap<String, String>()
        result.output.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { line ->
                val name = line.substringBefore('=').trim()
                val path = line.substringAfter('=').trim()
                if (name.isNotEmpty() && path.isNotEmpty()) found[name] = path
            }
        return found
    }

    /** true while the process has not exited (exitValue() only succeeds after it did). */
    private fun isRunning(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    fun resolveWorkingDirectory(requested: String?): File {
        if (requested.isNullOrBlank()) return workspace.root
        return try {
            workspace.resolve(requested)
        } catch (_: Exception) {
            workspace.root
        }
    }

    /** Resolves a path for tools (screenshots, scripts) and rejects escapes. */
    fun resolveInWorkspace(requested: String): File = workspace.resolve(requested)

    private fun buildEnvironment(cwd: File): Map<String, String> {
        val pathPrefix = (extraPath + listOf(binDirectory.absolutePath, "/system/bin", "/system/xbin", "/vendor/bin"))
            .filter { it.isNotBlank() }
            .joinToString(":")
        // Explicit type: System.getenv() is nullable, and leaving it inline made the whole
        // map infer Map<String, Any> instead of Map<String, String>.
        val systemPath: String = System.getenv("PATH") ?: DEFAULT_SYSTEM_PATH
        return mapOf(
            "PATH" to (pathPrefix + ":" + systemPath),
            "HOME" to workspace.rootPath,
            "TMPDIR" to cwd.absolutePath,
            "PWD" to cwd.absolutePath,
            "LC_ALL" to "C.UTF-8",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color"
        )
    }

    companion object {
        /** Android's POSIX shell; exists on every Android device since API 1. */
        const val DEFAULT_SHELL = "/system/bin/sh"

        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val DEFAULT_MAX_OUTPUT_CHARS = 12_000

        /** Fallback PATH when the process environment has none (it always does on Android). */
        private const val DEFAULT_SYSTEM_PATH = "/sbin:/system/sbin:/system/bin:/system/xbin"

        /** Binaries worth probing for the model's capability report. */
        val CANDIDATE_BINARIES = listOf(
            "sh", "bash", "curl", "wget", "python3", "python", "pip", "git", "gh", "node",
            "npm", "busybox", "toybox", "zip", "unzip", "tar", "gzip", "openssl", "ssh", "ffmpeg"
        )
    }
}
