package com.example.agent.workspace

import java.io.File

/** Thrown when a requested path would leave the workspace sandbox. */
class WorkspaceSecurityException(message: String) : Exception(message)

/**
 * Phase 7 — workspace isolation.
 *
 * Every file tool goes through this class: a requested path is resolved against [root] and the
 * *canonical* result must still live inside it. Canonicalisation is what makes the guard useful:
 * `..` segments and symlinks are resolved before the check, so `../`, `sub/../../outside.txt`
 * and a symlink pointing at `/etc` are all rejected. A leading `/` is read as "relative to the
 * workspace root", never as a real filesystem path, so `/etc/passwd` cannot escape either.
 *
 * The class only uses java.io.File on purpose: it is fully testable in plain JVM unit tests and
 * holds no Android dependency.
 */
class Workspace(rootDirectory: File) {

    /** Absolute workspace root. Created on demand so the first tool call always has a home. */
    val root: File = rootDirectory.absoluteFile

    init {
        if (!root.exists()) {
            root.mkdirs()
        }
    }

    val rootPath: String get() = root.absolutePath

    /**
     * Resolves [requested] inside the workspace, or throws [WorkspaceSecurityException].
     * The returned file may not exist yet (write/mkdir targets), but its parent chain is already
     * guaranteed to stay inside the workspace.
     */
    fun resolve(requested: String): File {
        val cleaned = requested.trim()
        if (cleaned.isEmpty()) {
            throw WorkspaceSecurityException("Path tidak boleh kosong.")
        }
        if (cleaned.contains('\u0000')) {
            throw WorkspaceSecurityException("Path mengandung karakter tidak valid.")
        }

        // "/etc/passwd" and "\etc\passwd" are treated as workspace-relative, not absolute.
        val relative = cleaned.trimStart('/', '\\')
        val candidate = if (relative.isEmpty()) root else File(root, relative)

        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile

        val inside = canonicalCandidate == canonicalRoot ||
            canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
        if (!inside) {
            throw WorkspaceSecurityException(
                "Path '$requested' berada di luar workspace agent dan ditolak."
            )
        }
        return canonicalCandidate
    }

    /** Workspace-relative display path, e.g. "notes/todo.md" ("." for the root itself). */
    fun relativePath(file: File): String {
        val canonicalRoot = root.canonicalPath
        val target = file.canonicalPath
        return when {
            target == canonicalRoot -> "."
            target.startsWith(canonicalRoot + File.separator) -> target.substring(canonicalRoot.length + 1)
            else -> target
        }
    }

    companion object {
        /** Base directory that holds one folder per agent. */
        const val WORKSPACES_DIRECTORY = "workspaces"

        /**
         * Longest file body handed back to the model in one read. Kept well under the Agent
         * Loop's tool-output backstop so the truncation note the tool adds is what the model
         * sees, instead of a silent cut by the loop.
         */
        const val MAX_READ_CHARS = 16_000

        /** Longest file body the model may write in one call. */
        const val MAX_WRITE_CHARS = 1_000_000

        /** Entries returned by one directory listing. */
        const val MAX_LIST_ENTRIES = 200

        /**
         * Workspace of a single agent: `<base>/workspaces/<agentId>`. [agentId] is sanitised into
         * a single safe folder name so the result always stays directly under `workspaces/`.
         */
        fun of(baseDirectory: File, agentId: String): Workspace {
            val safeAgentId = sanitiseAgentId(agentId)
            return Workspace(File(File(baseDirectory, WORKSPACES_DIRECTORY), safeAgentId))
        }

        /**
         * Turns [agentId] into a safe single-segment directory name. Only letters, digits, `-`,
         * `_` and `.` survive; anything else (including path separators) becomes `_`, so the name
         * can never contain a directory traversal. Empty and dot-only names (`.` / `..`) fall back
         * to `"default-agent"`, keeping every workspace directly under `workspaces/`.
         */
        private fun sanitiseAgentId(agentId: String): String {
            val replaced = agentId.trim()
                .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_' }
                .joinToString("")
            // After the substitutions above the name is a single path segment (no '/'), so it can
            // no longer walk out of workspaces/. Reject the remaining ambiguous cases (empty, or a
            // name made only of dots such as "." / "..") so the folder name is always predictable.
            val ambiguous = replaced.isEmpty() || replaced.all { it == '.' }
            return if (ambiguous) "default-agent" else replaced
        }
    }
}
