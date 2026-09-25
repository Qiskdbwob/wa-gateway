package com.example.agent.terminal

import com.example.agent.workspace.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File

/** Everything the terminal screen renders. Purely in-memory, like the reference design. */
data class TerminalState(
    val lines: List<String> = emptyList(),
    val cwd: String = "",
    val busy: Boolean = false,
    val started: Boolean = false,
    val lastExitCode: Int? = null,
    val lastCommand: String? = null
)

/**
 * Built-in interactive terminal (see `DOC/reference/terminal/builtin-terminal.md`).
 *
 * One persistent shell process keeps its working directory between commands, so `cd src` is
 * remembered by the next command. Completion is detected with the reference project's marker
 * trick: after every command the engine writes an extra line that echoes the exit code and
 * `$PWD`; the reader consumes that line instead of displaying it, which is how the UI knows a
 * command finished without needing a pty.
 *
 * Differences from the reference dossier, on purpose:
 *  - no Shizuku/privileged tier and no Termux toolchain bootstrap (both are project-specific
 *    and would require bundling or external apps);
 *  - the shell is Android's own `/system/bin/sh`, so the terminal works out of the box;
 *  - there is no keepalive service here: the app's existing gateway foreground service keeps
 *    the process alive while the gateway is connected.
 */
class TerminalManager(
    private val workspace: Workspace,
    private val shellPath: String = ShellRunner.DEFAULT_SHELL
) {

    val runner = ShellRunner(workspace, shellPath = shellPath)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TerminalState(cwd = workspace.rootPath))
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private val writeLock = Mutex()
    private val pending = ArrayDeque<String>()
    private val pendingLock = Any()
    private var flushJob: Job? = null

    private val _capabilities = MutableStateFlow<Map<String, String>>(emptyMap())
    val capabilities: StateFlow<Map<String, String>> = _capabilities.asStateFlow()

    private var capabilitiesLoaded = false

    /** Starts the persistent shell once; safe to call on every screen entry. */
    fun ensureStarted() {
        synchronized(this) {
            val current = process
            if (current != null && isRunning(current)) return
            startLocked()
        }
    }

    /** true while the shell has not exited; avoids Process.isAlive() (API 24+) ambiguity. */
    private fun isRunning(candidate: Process): Boolean = try {
        candidate.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun startLocked() {
        val directory: File = workspace.root
        try {
            val builder = ProcessBuilder(shellPath)
                .directory(directory)
                .redirectErrorStream(true)
            builder.environment().putAll(
                mapOf(
                    "PATH" to (listOf(
                        runner.binDirectory.absolutePath,
                        "/system/bin",
                        "/system/xbin",
                        "/vendor/bin"
                    ).joinToString(":") + ":" + (System.getenv("PATH") ?: "/sbin:/system/sbin:/system/bin")),
                    "HOME" to workspace.rootPath,
                    "TMPDIR" to directory.absolutePath,
                    "LC_ALL" to "C.UTF-8",
                    "LANG" to "C.UTF-8",
                    "TERM" to "xterm-256color",
                    "PS1" to ""
                )
            )
            val started = builder.start()
            process = started
            writer = started.outputStream.bufferedWriter()
            _state.update {
                it.copy(
                    started = true,
                    busy = false,
                    cwd = directory.absolutePath,
                    lines = it.lines + "# terminal siap — $shellPath (workspace: ${workspace.rootPath})"
                )
            }
            scope.launch { readLoop(started) }
        } catch (e: Exception) {
            writer = null
            process = null
            _state.update {
                it.copy(
                    started = false,
                    busy = false,
                    lines = it.lines + "# gagal memulai shell: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun readLoop(source: Process) {
        try {
            val reader = source.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                handleLine(line)
            }
        } catch (_: Exception) {
            // Falls through to the exit handling below.
        }
        val wasBusy = _state.value.busy
        synchronized(this) {
            if (process === source) {
                process = null
                writer = null
            }
        }
        _state.update {
            it.copy(
                started = false,
                busy = false,
                lastExitCode = if (wasBusy) -1 else it.lastExitCode,
                lines = it.lines + "# shell berhenti"
            )
        }
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.startsWith(MARKER_PREFIX)) {
            val payload = trimmed.removePrefix(MARKER_PREFIX).removePrefix(":")
            val exit = payload.substringBefore(':').toIntOrNull()
            val cwd = payload.substringAfter(':', "").takeIf { it.isNotBlank() }
            _state.update {
                it.copy(
                    busy = false,
                    lastExitCode = exit ?: it.lastExitCode,
                    cwd = cwd ?: it.cwd
                )
            }
            return
        }
        synchronized(pendingLock) { pending.addLast(trimmed) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushPending()
        }
    }

    private suspend fun flushPending() {
        val drained = synchronized(pendingLock) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (drained.isEmpty()) return
        _state.update { current ->
            val merged = current.lines + drained
            current.copy(lines = if (merged.size > MAX_LINES) merged.takeLast(MAX_LINES) else merged)
        }
    }

    /** Runs [command] in the persistent shell. Refused (not queued) while one is running. */
    suspend fun send(command: String): Boolean = writeLock.withLock {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return@withLock false
        ensureStarted()

        val out = writer
        if (out == null) {
            _state.update { it.copy(lines = it.lines + "# shell tidak berjalan") }
            return@withLock false
        }
        if (_state.value.busy) {
            _state.update { it.copy(lines = it.lines + "# masih ada perintah berjalan, tunggu selesai") }
            return@withLock false
        }

        _state.update {
            it.copy(
                busy = true,
                lastCommand = trimmed,
                lastExitCode = null,
                lines = it.lines + "$ $trimmed"
            )
        }
        return@withLock try {
            // Exit code and cwd travel back on an invisible marker line.
            out.write(trimmed + "\n")
            out.write("echo \"$MARKER_PREFIX:\$?:\$PWD\"\n")
            out.flush()
            true
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    busy = false,
                    lastExitCode = -1,
                    lines = it.lines + "# gagal menulis ke shell: ${e.message ?: e.javaClass.simpleName}"
                )
            }
            false
        }
    }

    fun clear() {
        synchronized(pendingLock) { pending.clear() }
        _state.update { it.copy(lines = emptyList()) }
    }

    /** Stops the shell explicitly (the reference design keeps it alive when the screen closes). */
    fun stop() {
        val current = synchronized(this) {
            val p = process
            process = null
            writer = null
            p
        }
        try {
            current?.let {
                it.outputStream.close()
                it.destroy()
            }
        } catch (_: Exception) {
            // ignore
        }
        _state.update {
            it.copy(started = false, busy = false, lines = it.lines + "# shell dihentikan")
        }
    }

    /** Probes (once) which binaries the device really provides. */
    suspend fun loadCapabilities(force: Boolean = false): Map<String, String> {
        if (capabilitiesLoaded && !force) return _capabilities.value
        val found = runner.availableCommands()
        capabilitiesLoaded = true
        _capabilities.value = found
        return found
    }

    fun capabilitiesSummary(): String = buildString {
        val available = _capabilities.value
        if (available.isEmpty()) {
            append("Belum dipindai. Panggil terminal_info untuk memindai toolchain perangkat.")
        } else {
            appendLine("Toolchain perangkat yang terdeteksi:")
            available.forEach { (name, path) -> appendLine("• $name -> $path") }
            val missing = listOf("curl", "wget", "python3", "git", "gh", "node")
                .filter { !available.containsKey(it) }
            if (missing.isNotEmpty()) {
                appendLine()
                append("Tidak tersedia di perangkat ini: ${missing.joinToString(", ")}. ")
                append("Gunakan tool web_fetch/web_search sebagai gantinya, atau minta pengguna memasang toolchain (Termux) lalu tambahkan binernya ke PATH.")
            }
        }
    }

    companion object {
        private const val MARKER_PREFIX = "__AGXDONE__"
        private const val FLUSH_INTERVAL_MS = 80L
        private const val MAX_LINES = 1_500
    }
}
