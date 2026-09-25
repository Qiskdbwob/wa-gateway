package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.workspace.Workspace
import com.example.agent.workspace.WorkspaceSecurityException
import java.io.File

/** Thrown when a tool argument is missing or invalid; reported to the model as a tool error. */
class ToolInputException(message: String) : Exception(message)

/**
 * Phase 7 — file tools, all sandboxed inside the agent workspace.
 *
 * Every path argument goes through [Workspace.resolve], so a path outside the workspace is
 * rejected (and reported back to the model as a failed tool result) instead of touching the
 * device filesystem. Most tools are `SAFE` because the sandbox itself is the permission: they
 * can only ever reach files the app itself owns. The destructive one ([DeletePathTool]) passes
 * `CONFIRM` instead, so it is routed through the approval layer (Priority 3).
 */
abstract class WorkspaceFileTool(
    protected val workspace: Workspace,
    override val permission: ToolPermission = ToolPermission.SAFE
) : Tool {

    final override suspend fun execute(input: String): ToolResult = try {
        run(input)
    } catch (e: WorkspaceSecurityException) {
        ToolResult(success = false, output = "", error = e.message ?: "Path ditolak oleh workspace.")
    } catch (e: ToolInputException) {
        ToolResult(success = false, output = "", error = e.message ?: "Argumen tool tidak valid.")
    } catch (e: Exception) {
        ToolResult(success = false, output = "", error = "Tool $name gagal: ${e.message}")
    }

    protected abstract fun run(input: String): ToolResult

    /** Required string argument. */
    protected fun requireArg(input: String, key: String): String {
        val value = JsonArgs.string(input, key)
        if (value.isNullOrBlank()) {
            throw ToolInputException("Argumen '$key' wajib diisi.")
        }
        return value
    }

    /** Optional string argument with a fallback. */
    protected fun optionalArg(input: String, key: String, default: String): String =
        JsonArgs.string(input, key)?.takeIf { it.isNotBlank() } ?: default

    /** Resolves a required path argument inside the workspace. */
    protected fun resolveRequired(input: String, key: String): File = workspace.resolve(requireArg(input, key))

    /** Resolves an optional path argument inside the workspace. */
    protected fun resolveOptional(input: String, key: String, default: String): File =
        workspace.resolve(optionalArg(input, key, default))

    protected fun relative(file: File): String = workspace.relativePath(file)

    protected fun ok(output: String, vararg metadata: Pair<String, String>): ToolResult =
        ToolResult(success = true, output = output, metadata = metadata.toMap())

    protected fun fail(error: String): ToolResult = ToolResult(success = false, output = "", error = error)
}

class ListFilesTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.list_files"
    override val name: String = "list_files"
    override val description: String =
        "Menampilkan isi sebuah direktori di dalam workspace. Direktori ditandai akhiran '/' dan file disertai ukurannya. Gunakan '.' untuk akar workspace."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path relatif terhadap workspace, contoh 'notes' atau '.' untuk akar."
            }
          },
          "required": []
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveOptional(input, "path", ".")
        if (!target.exists()) return fail("Path '${relative(target)}' tidak ditemukan.")
        if (!target.isDirectory) return fail("Path '${relative(target)}' bukan direktori.")

        val entries = target.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (entries.isEmpty()) {
            return ok("Direktori '${relative(target)}' kosong.", "count" to "0", "path" to relative(target))
        }

        val shown = entries.take(Workspace.MAX_LIST_ENTRIES)
        val lines = shown.joinToString("\n") { entry ->
            if (entry.isDirectory) "${entry.name}/" else "${entry.name} (${entry.length()} B)"
        }
        val truncated = if (entries.size > shown.size) {
            "\n...(${entries.size - shown.size} entri lain tidak ditampilkan)"
        } else {
            ""
        }
        return ok(
            "Isi '${relative(target)}' (${entries.size} entri):\n$lines$truncated",
            "count" to entries.size.toString(),
            "path" to relative(target)
        )
    }
}

class ReadFileTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.read_file"
    override val name: String = "read_file"
    override val description: String =
        "Membaca isi sebuah file teks di dalam workspace. Gunakan list_files lebih dulu bila belum tahu nama file."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path file relatif terhadap workspace, contoh 'notes/hari-ini.md'."
            }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveRequired(input, "path")
        if (!target.exists()) return fail("File '${relative(target)}' tidak ditemukan.")
        if (target.isDirectory) return fail("'${relative(target)}' adalah direktori, bukan file.")

        val text = target.readText()
        val truncated = text.length > Workspace.MAX_READ_CHARS
        val body = if (truncated) {
            text.take(Workspace.MAX_READ_CHARS) + "\n...(isi dipotong, file lebih besar dari ${Workspace.MAX_READ_CHARS} karakter)"
        } else {
            text
        }
        return ok(
            "Isi file '${relative(target)}' (${target.length()} B):\n$body",
            "path" to relative(target),
            "bytes" to target.length().toString(),
            "truncated" to truncated.toString()
        )
    }
}

class WriteFileTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.write_file"
    override val name: String = "write_file"
    override val description: String =
        "Menulis file teks di dalam workspace, membuat foldernya bila belum ada, dan menimpa isi lama."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path file relatif terhadap workspace."
            },
            "content": {
              "type": "string",
              "description": "Isi file yang akan ditulis, menggantikan seluruh isi sebelumnya."
            }
          },
          "required": ["path", "content"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveRequired(input, "path")
        val content = requireArg(input, "content")
        if (content.length > Workspace.MAX_WRITE_CHARS) {
            return fail("Isi file terlalu besar (${content.length} karakter, maksimum ${Workspace.MAX_WRITE_CHARS}).")
        }
        if (target.isDirectory) return fail("'${relative(target)}' adalah direktori, bukan file.")

        target.parentFile?.mkdirs()
        target.writeText(content)
        return ok(
            "File '${relative(target)}' tersimpan (${target.length()} B).",
            "path" to relative(target),
            "bytes" to target.length().toString()
        )
    }
}

class AppendFileTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.append_file"
    override val name: String = "append_file"
    override val description: String =
        "Menambahkan teks ke akhir file di dalam workspace. File dan foldernya dibuat bila belum ada."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path file relatif terhadap workspace."
            },
            "content": {
              "type": "string",
              "description": "Teks yang ditambahkan di akhir file."
            }
          },
          "required": ["path", "content"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveRequired(input, "path")
        val content = requireArg(input, "content")
        val existingSize = if (target.isFile) target.length() else 0L
        if (existingSize + content.length > Workspace.MAX_WRITE_CHARS) {
            return fail("Hasil akhir file melebihi batas ${Workspace.MAX_WRITE_CHARS} karakter.")
        }
        if (target.isDirectory) return fail("'${relative(target)}' adalah direktori, bukan file.")

        target.parentFile?.mkdirs()
        target.appendText(content)
        return ok(
            "Teks ditambahkan ke '${relative(target)}' (${target.length()} B).",
            "path" to relative(target),
            "bytes" to target.length().toString()
        )
    }
}

class MovePathTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.move_path"
    override val name: String = "move_path"
    override val description: String =
        "Memindahkan atau mengganti nama file/direktori di dalam workspace."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "from": { "type": "string", "description": "Path asal, relatif terhadap workspace." },
            "to": { "type": "string", "description": "Path tujuan, relatif terhadap workspace." }
          },
          "required": ["from", "to"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val source = resolveRequired(input, "from")
        val destination = resolveRequired(input, "to")
        if (!source.exists()) return fail("Path asal '${relative(source)}' tidak ditemukan.")
        if (destination.exists()) return fail("Path tujuan '${relative(destination)}' sudah ada.")

        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            return fail("Gagal memindahkan '${relative(source)}' ke '${relative(destination)}'.")
        }
        return ok(
            "'${relative(source)}' dipindahkan ke '${relative(destination)}'.",
            "from" to relative(source),
            "to" to relative(destination)
        )
    }
}

class CopyPathTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.copy_path"
    override val name: String = "copy_path"
    override val description: String =
        "Menyalin file atau direktori (termasuk isinya) di dalam workspace."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "from": { "type": "string", "description": "Path asal, relatif terhadap workspace." },
            "to": { "type": "string", "description": "Path tujuan, relatif terhadap workspace." }
          },
          "required": ["from", "to"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val source = resolveRequired(input, "from")
        val destination = resolveRequired(input, "to")
        if (!source.exists()) return fail("Path asal '${relative(source)}' tidak ditemukan.")
        if (destination.exists()) return fail("Path tujuan '${relative(destination)}' sudah ada.")

        copyRecursively(source, destination)
        return ok(
            "'${relative(source)}' disalin ke '${relative(destination)}'.",
            "from" to relative(source),
            "to" to relative(destination)
        )
    }

    private fun copyRecursively(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(destination, child.name))
            }
            return
        }
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

class DeletePathTool(workspace: Workspace) :
    WorkspaceFileTool(workspace, permission = ToolPermission.CONFIRM) {
    override val id: String = "builtin.delete_path"
    override val name: String = "delete_path"
    override val description: String =
        "Menghapus file atau direktori kosong di workspace. Untuk direktori yang masih berisi, set recursive=true. " +
            "Tindakan destruktif: pemanggilan ini butuh persetujuan pengguna sebelum dieksekusi."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Path relatif terhadap workspace." },
            "recursive": {
              "type": "boolean",
              "description": "true untuk menghapus direktori beserta seluruh isinya."
            }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveRequired(input, "path")
        val recursive = JsonArgs.boolean(input, "recursive") ?: false

        if (target.canonicalPath == workspace.root.canonicalPath) {
            return fail("Akar workspace tidak boleh dihapus.")
        }
        if (!target.exists()) return fail("Path '${relative(target)}' tidak ditemukan.")

        val path = relative(target)
        val hasChildren = target.isDirectory && (target.listFiles()?.isNotEmpty() == true)
        if (hasChildren && !recursive) {
            return fail("Direktori '$path' masih berisi. Ulangi dengan recursive=true bila memang ingin dihapus seluruhnya.")
        }

        val deleted = if (recursive || target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted || target.exists()) {
            return fail("Gagal menghapus '$path'.")
        }
        return ok("'$path' dihapus.", "path" to path)
    }
}

class MakeDirectoryTool(workspace: Workspace) : WorkspaceFileTool(workspace) {
    override val id: String = "builtin.make_directory"
    override val name: String = "make_directory"
    override val description: String =
        "Membuat direktori (beserta parent-nya) di dalam workspace."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Path direktori relatif terhadap workspace." }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override fun run(input: String): ToolResult {
        val target = resolveRequired(input, "path")
        if (target.isFile) return fail("'${relative(target)}' sudah ada sebagai file.")

        if (!target.mkdirs() && !target.isDirectory) {
            return fail("Gagal membuat direktori '${relative(target)}'.")
        }
        return ok("Direktori '${relative(target)}' siap.", "path" to relative(target))
    }
}

/** All workspace file tools, in the order they are advertised to the model. */
fun workspaceFileTools(workspace: Workspace): List<Tool> = listOf(
    ListFilesTool(workspace),
    ReadFileTool(workspace),
    WriteFileTool(workspace),
    AppendFileTool(workspace),
    MovePathTool(workspace),
    CopyPathTool(workspace),
    DeletePathTool(workspace),
    MakeDirectoryTool(workspace)
)
