package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.skills.SkillLibrary
import com.example.agent.workspace.Workspace
import java.io.File

/**
 * Markdown skills, exposed to the model as three small tools.
 *
 * `list_skills` and `read_skill` are read-only (`SAFE`); `save_skill` writes, but only inside
 * `workspace/skills/` and only with a sanitised filename, so it cannot be talked into writing
 * somewhere else. The point of the feature is that the agent records *how* it solved something
 * once and reuses it later, instead of re-deriving the same procedure every time.
 */
class ListSkillsTool(private val library: SkillLibrary) : Tool {
    override val id: String = "builtin.list_skills"
    override val name: String = "list_skills"
    override val description: String =
        "Menampilkan daftar skill markdown yang tersedia (nama + deskripsi). Panggil ini bila tugas terasa seperti prosedur yang pernah dikerjakan."
    override val inputSchema: String = """{"type":"object","properties":{},"required":[]}"""
    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val skills = library.load()
        if (skills.isEmpty()) {
            return ToolResult(
                success = true,
                output = "Belum ada skill. Buat dengan save_skill (nama, deskripsi, isi markdown) " +
                    "atau tulis file di folder skills/ memakai write_file.",
                metadata = mapOf("count" to "0")
            )
        }
        val body = skills.joinToString("\n") { skill ->
            val description = skill.description.ifBlank { "(tanpa deskripsi)" }
            "- ${skill.name} [${skill.path}] — $description"
        }
        return ToolResult(
            success = true,
            output = "Skill tersedia (${skills.size}):\n$body\n\nBaca isinya dengan read_skill sebelum dipakai.",
            metadata = mapOf("count" to skills.size.toString())
        )
    }
}

class ReadSkillTool(private val library: SkillLibrary) : Tool {
    override val id: String = "builtin.read_skill"
    override val name: String = "read_skill"
    override val description: String =
        "Membaca isi satu skill markdown (prosedur/langkah) berdasarkan nama atau path-nya."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "name": { "type": "string", "description": "Nama skill atau path, contoh 'balas email' atau 'skills/balas-email.md'." }
          },
          "required": ["name"]
        }
    """.trimIndent()
    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val name = JsonArgs.string(input, "name")?.trim().orEmpty()
        if (name.isEmpty()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Argumen `name` wajib diisi. Pakai list_skills untuk melihat nama yang ada."
            )
        }
        val skill = library.find(name)
            ?: return ToolResult(
                success = false,
                output = "",
                error = "Skill '$name' tidak ditemukan. Jalankan list_skills untuk melihat daftar yang ada."
            )
        return ToolResult(
            success = true,
            output = "Skill '${skill.name}' (${skill.path}):\n${skill.body}",
            metadata = mapOf("name" to skill.name, "path" to skill.path)
        )
    }
}

class SaveSkillTool(
    private val library: SkillLibrary,
    private val workspace: Workspace
) : Tool {

    override val id: String = "builtin.save_skill"
    override val name: String = "save_skill"
    override val description: String =
        "Menyimpan atau memperbarui skill markdown (prosedur yang bisa dipakai ulang) di folder skills/. " +
            "Pakai setelah berhasil menyelesaikan sesuatu yang kemungkinan akan diminta lagi."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "name": { "type": "string", "description": "Nama singkat skill, contoh 'Balas email kuesioner'." },
            "description": { "type": "string", "description": "Satu kalimat kapan skill ini dipakai." },
            "content": { "type": "string", "description": "Isi markdown: langkah-langkah, perintah, atau catatan penting." }
          },
          "required": ["name", "content"]
        }
    """.trimIndent()
    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val name = JsonArgs.string(input, "name")?.trim().orEmpty()
        val content = JsonArgs.string(input, "content")?.trim().orEmpty()
        if (name.isEmpty() || content.isEmpty()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Argumen `name` dan `content` wajib diisi."
            )
        }
        if (content.length > Workspace.MAX_WRITE_CHARS) {
            return ToolResult(
                success = false,
                output = "",
                error = "Isi skill terlalu panjang (${content.length} karakter, maksimum ${Workspace.MAX_WRITE_CHARS})."
            )
        }

        return try {
            val directory = library.directory()
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, library.fileNameFor(name))
            val description = JsonArgs.string(input, "description")?.trim().orEmpty()
            val frontMatter = buildString {
                append("---\n")
                append("name: ").append(name.replace('\n', ' ')).append('\n')
                if (description.isNotEmpty()) {
                    append("description: ").append(description.replace('\n', ' ')).append('\n')
                }
                append("---\n\n")
            }
            file.writeText(frontMatter + content + "\n")
            ToolResult(
                success = true,
                output = "Skill '${name}' disimpan di ${workspace.relativePath(file)} " +
                    "(${file.length()} B). Skill akan terlihat di list_skills dan di indeks prompt.",
                metadata = mapOf("path" to workspace.relativePath(file))
            )
        } catch (e: Exception) {
            ToolResult(success = false, output = "", error = "Gagal menyimpan skill: ${e.message}")
        }
    }
}
