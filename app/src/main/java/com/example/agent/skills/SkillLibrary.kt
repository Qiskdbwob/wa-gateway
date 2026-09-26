package com.example.agent.skills

import com.example.agent.workspace.Workspace
import java.io.File

/** One markdown skill: a procedure the agent can read again the next time it is relevant. */
data class Skill(
    val name: String,
    val description: String,
    /** Workspace-relative path, e.g. `skills/balas-email.md`. */
    val path: String,
    val body: String,
    val updatedAt: Long = 0L
)

/**
 * Markdown skills — plain `.md` files in `workspace/skills/`, nothing proprietary.
 *
 * Deliberately the simplest thing that compounds: the user can drop a file in, the agent can write
 * one with `save_skill` after it figures something out, and the next turn reads only the *index*
 * (name + description) so a shelf full of skills costs a few lines of context, not a few thousand.
 * The body is fetched with `read_skill` when it is actually needed.
 *
 * Format (all parts optional except the body):
 *
 * ```
 * ---
 * name: Balas email kuesioner
 * description: Prosedur menjawab email kuesioner pelanggan
 * ---
 * # Langkah
 * 1. ...
 * ```
 *
 * Without front matter the first `# Heading` becomes the name and the first paragraph after it the
 * description, so a plain hand-written markdown file still works.
 */
class SkillLibrary(private val workspace: Workspace) {

    /** `workspace/skills` — created on demand. */
    fun directory(): File = File(workspace.root, SKILLS_DIRECTORY)

    /** All skills, newest first, then by name; malformed files are skipped instead of throwing. */
    fun load(): List<Skill> {
        val files = directory().listFiles()?.filter { it.isFile && it.extension.lowercase() == "md" }
            ?: return emptyList()
        return files
            .mapNotNull { file ->
                runCatching {
                    parse(file.name, file.readText(), file.lastModified())
                }.getOrNull()
            }
            .sortedWith(compareByDescending<Skill> { it.updatedAt }.thenBy { it.name.lowercase() })
    }

    /** Exact name match first, then substring — used by `read_skill`. */
    fun find(nameOrPath: String): Skill? {
        val needle = nameOrPath.trim().lowercase()
        if (needle.isEmpty()) return null
        val skills = load()
        return skills.firstOrNull { it.name.lowercase() == needle }
            ?: skills.firstOrNull { it.path.lowercase() == needle || it.path.lowercase() == "$SKILLS_DIRECTORY/$needle" }
            ?: skills.firstOrNull { it.name.lowercase().contains(needle) }
    }

    /**
     * Compact prompt block: one line per skill. Capped so an over-enthusiastic collection cannot
     * push the actual conversation out of the model's context.
     */
    fun index(maxSkills: Int = MAX_INDEX_SKILLS, maxChars: Int = MAX_INDEX_CHARS): List<String> {
        val lines = mutableListOf<String>()
        var used = 0
        for (skill in load().take(maxSkills)) {
            val line = if (skill.description.isBlank()) {
                "- ${skill.name}"
            } else {
                "- ${skill.name}: ${skill.description}"
            }
            if (used + line.length > maxChars) break
            used += line.length
            lines += line
        }
        return lines
    }

    /**
     * Reads a skill file into a [Skill]. [fileName] is only used for the name fallback and the
     * reported path; the caller guarantees the file lives inside `skills/`.
     */
    fun parse(fileName: String, content: String, updatedAt: Long = 0L): Skill {
        val (frontMatter, body) = splitFrontMatter(content)
        val heading = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            .orEmpty()

        val name = frontMatter["name"]?.takeIf { it.isNotBlank() }
            ?: heading.ifBlank { fileName.substringBeforeLast('.').replace('-', ' ') }
        val description = frontMatter["description"]?.takeIf { it.isNotBlank() }
            ?: firstParagraph(body, heading)

        return Skill(
            name = name,
            description = description,
            path = "$SKILLS_DIRECTORY/${sanitizeFileName(fileName)}",
            body = body.trim().take(MAX_BODY_CHARS),
            updatedAt = updatedAt
        )
    }

    /** `skills/<slug>.md` for a display name, so `save_skill` cannot escape the folder. */
    fun fileNameFor(name: String): String = sanitizeFileName(name.lowercase().replace(' ', '-')) + ".md"

    private fun splitFrontMatter(content: String): Pair<Map<String, String>, String> {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return emptyMap<String, String>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to normalized

        val header = normalized.substring(3, end)
        val body = normalized.substring(end + 4)
        val map = header.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim().trim('"', '\'')
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
        return map to body
    }

    private fun firstParagraph(body: String, heading: String): String =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("#") }
            .firstOrNull { it != heading }
            ?.take(MAX_DESCRIPTION_CHARS)
            .orEmpty()

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
        return cleaned.ifBlank { "skill" }.take(MAX_FILE_NAME_CHARS)
    }

    companion object {
        const val SKILLS_DIRECTORY = "skills"
        const val MAX_INDEX_SKILLS = 25
        const val MAX_INDEX_CHARS = 1_200
        const val MAX_BODY_CHARS = 4_000
        const val MAX_DESCRIPTION_CHARS = 200
        const val MAX_FILE_NAME_CHARS = 60
    }
}
