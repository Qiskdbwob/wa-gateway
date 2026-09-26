package com.example.agent.skills

import com.example.agent.tool.SaveSkillTool
import com.example.agent.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Markdown skills.
 *
 * The feature only pays off if a hand-written file and a file the agent wrote itself both work, so
 * the tests cover both paths — plus the boring parts that decide whether a shelf of skills is
 * usable or turns into noise: which files are picked up, what becomes the name, and how much of it
 * reaches the prompt.
 */
class SkillLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var library: SkillLibrary

    private fun setUpLibrary(): SkillLibrary {
        workspaceRoot = temp.newFolder("workspace")
        library = SkillLibrary(Workspace(workspaceRoot))
        return library
    }

    private fun writeSkill(fileName: String, content: String): File {
        val directory = File(workspaceRoot, SkillLibrary.SKILLS_DIRECTORY).apply { mkdirs() }
        return File(directory, fileName).apply { writeText(content) }
    }

    // ==========================================
    // Parsing
    // ==========================================

    @Test
    fun frontMatterProvidesNameAndDescription() {
        setUpLibrary()

        val skill = library.parse(
            "apa-pun.md",
            """
            ---
            name: Balas email kuesioner
            description: Prosedur menjawab email kuesioner pelanggan
            ---

            # Langkah
            1. Buka inbox
            """.trimIndent()
        )

        assertEquals("Balas email kuesioner", skill.name)
        assertEquals("Prosedur menjawab email kuesioner pelanggan", skill.description)
        // The front matter is metadata, not part of the procedure the model should read.
        assertFalse(skill.body.contains("name:"))
        assertTrue(skill.body.contains("Buka inbox"))
        assertEquals("skills/apa-pun.md", skill.path)
    }

    @Test
    fun plainMarkdownFallsBackToHeadingAndFirstParagraph() {
        setUpLibrary()

        val skill = library.parse(
            "catatan-harian.md",
            """
            # Laporan harian
            Kirim ringkasan penjualan tiap pukul 5 sore.

            Detail lain yang tidak dipakai sebagai deskripsi.
            """.trimIndent()
        )

        assertEquals("Laporan harian", skill.name)
        assertEquals("Kirim ringkasan penjualan tiap pukul 5 sore.", skill.description)
    }

    @Test
    fun fileWithoutAnythingReadableFallsBackToTheFileName() {
        setUpLibrary()

        val skill = library.parse("tanpa-judul.md", "hanya teks biasa tanpa heading")

        assertEquals("tanpa judul", skill.name)
        assertEquals("hanya teks biasa tanpa heading", skill.description)
    }

    // ==========================================
    // Loading / finding / indexing
    // ==========================================

    @Test
    fun loadReadsOnlyMarkdownFilesAndSortsNewestFirst() {
        setUpLibrary()
        writeSkill("lama.md", "# Lama\nprosedur lama")
        writeSkill("baru.md", "# Baru\nprosedur baru")
        writeSkill("abaikan.txt", "bukan skill")
        File(workspaceRoot, SkillLibrary.SKILLS_DIRECTORY).resolve("lama.md").setLastModified(1_000L)
        File(workspaceRoot, SkillLibrary.SKILLS_DIRECTORY).resolve("baru.md").setLastModified(9_000L)

        val skills = library.load()

        assertEquals(2, skills.size)
        assertEquals("Baru", skills.first().name)
    }

    @Test
    fun findPrefersExactNameThenPathThenSubstring() {
        setUpLibrary()
        writeSkill("balas-email.md", "# Balas email\nkirim template")
        writeSkill("laporan.md", "# Laporan mingguan\nrekap penjualan")

        assertEquals("Balas email", library.find("balas email")?.name)
        assertEquals("Laporan mingguan", library.find("skills/laporan.md")?.name)
        assertEquals("Laporan mingguan", library.find("mingguan")?.name)
        assertNull(library.find("tidak ada"))
    }

    @Test
    fun indexIsOneLinePerSkillAndCapped() {
        setUpLibrary()
        writeSkill("a.md", "---\nname: Skill A\ndescription: Deskripsi A\n---\nisi")
        writeSkill("b.md", "---\nname: Skill B\ndescription: Deskripsi B\n---\nisi")

        val index = library.index()

        assertEquals(2, index.size)
        assertTrue(index.any { it == "- Skill A: Deskripsi A" })
        // The cap protects the conversation's context budget, not the shelf.
        assertEquals(1, library.index(maxSkills = 1).size)
        assertTrue(library.index(maxChars = 5).isEmpty())
    }

    // ==========================================
    // Saving (agent writes its own skill)
    // ==========================================

    @Test
    fun sanitisesFileNamesSoASkillCannotEscapeTheFolder() {
        setUpLibrary()

        assertEquals("balas-email-kuesioner.md", library.fileNameFor("Balas Email Kuesioner"))

        // A path-like name is flattened into a plain file name: no separators, no "..".
        val escaped = library.fileNameFor("../../etc/passwd")
        assertFalse(escaped.contains('/'))
        assertFalse(escaped.contains(".."))
        assertTrue(escaped.endsWith(".md"))
    }

    @Test
    fun saveSkillWritesFrontMatterAndBecomesListable() = runBlocking {
        setUpLibrary()
        val workspace = Workspace(workspaceRoot)

        val result = SaveSkillTool(library, workspace).execute(
            """{"name":"Balas email kuesioner","description":"Menjawab email","content":"1. Buka inbox\n2. Balas"}"""
        )

        assertTrue(result.success == true)
        assertEquals("skills/balas-email-kuesioner.md", result.metadata["path"])

        val saved = library.find("Balas email kuesioner")
        assertEquals("Menjawab email", saved?.description)
        assertTrue(saved!!.body.contains("Buka inbox"))
        // ...and it shows up in the prompt index without the body.
        assertTrue(library.index().any { it.startsWith("- Balas email kuesioner") })
    }

    @Test
    fun saveSkillRejectsMissingArguments() = runBlocking {
        setUpLibrary()

        val missing = SaveSkillTool(library, Workspace(workspaceRoot)).execute("""{"name":"x"}""")

        assertFalse(missing.success)
    }
}
