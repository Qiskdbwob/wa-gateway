package com.example.agent.browser

/**
 * Browser automation models (Phase 24 of `DOC/context-2.md`, scoped to what the app can
 * actually do: drive a real web page, sign in, fill forms, post, and hand control back to
 * the user for captcha/2FA).
 */

/** One interactive element of the current page, addressable by [ref] in later calls. */
data class BrowserElement(
    val ref: String,
    val tag: String,
    val type: String,
    val label: String,
    val value: String
)

/** Readable view of the page the model can reason about. */
data class BrowserSnapshot(
    val url: String,
    val title: String,
    val text: String,
    val elements: List<BrowserElement>,
    val error: String? = null
)

data class BrowserActionResult(
    val ok: Boolean,
    val url: String = "",
    val detail: String = "",
    val error: String? = null
)

/** A site login the user handed to the agent so it can sign in without typing secrets again. */
data class SiteCredential(
    val site: String,
    val loginUrl: String,
    val username: String,
    val password: String,
    val notes: String = ""
)

/**
 * The automation engine contract. Tools talk to this interface only, so the engine can be
 * replaced (a GeckoView build, a WebDriver bridge, a test double) without touching the agent
 * layer.
 */
interface BrowserEngine {
    /** Human-readable engine name, reported to the model and the UI. */
    val kind: String

    suspend fun open(url: String): BrowserActionResult
    suspend fun snapshot(maxChars: Int = 8_000): BrowserSnapshot
    suspend fun click(ref: String): BrowserActionResult
    suspend fun typeText(ref: String, text: String, submit: Boolean): BrowserActionResult
    suspend fun scroll(direction: String, amountPx: Int): BrowserActionResult
    suspend fun screenshot(): ByteArray?
    suspend fun currentUrl(): String
    suspend fun pageTitle(): String

    /** true once the engine has a live page/session to talk to. */
    fun isReady(): Boolean

    /** Drops cookies/storage so the next login starts clean. */
    suspend fun clearSession()

    fun dispose()
}

/**
 * Storage format for site credentials. Deliberately a tiny hand-rolled format instead of
 * JSON: the same encode/decode pair runs in plain JVM unit tests (no `org.json`, which is
 * only stubbed outside Robolectric). Values are sanitised of the two separators, so a
 * password containing a newline can never split a record.
 */
object SiteCredentialStore {

    private const val FIELD = '\u0001'
    private const val RECORD = '\u0002'

    fun encode(credentials: List<SiteCredential>): String =
        credentials
            .filter { it.site.isNotBlank() }
            .joinToString(RECORD.toString()) { credential ->
                listOf(
                    credential.site,
                    credential.loginUrl,
                    credential.username,
                    credential.password,
                    credential.notes
                ).joinToString(FIELD.toString()) { sanitize(it) }
            }

    fun decode(raw: String): List<SiteCredential> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD)
            .mapNotNull { record ->
                val parts = record.split(FIELD)
                if (parts.size < 4) return@mapNotNull null
                val site = parts[0].trim()
                if (site.isEmpty()) return@mapNotNull null
                SiteCredential(
                    site = site,
                    loginUrl = parts.getOrNull(1).orEmpty(),
                    username = parts.getOrNull(2).orEmpty(),
                    password = parts.getOrNull(3).orEmpty(),
                    notes = parts.getOrNull(4).orEmpty()
                )
            }
    }

    /** Finds the credential whose site matches [site] (case-insensitive, exact or contained). */
    fun find(credentials: List<SiteCredential>, site: String): SiteCredential? {
        val needle = site.trim().lowercase()
        if (needle.isEmpty()) return null
        return credentials.firstOrNull { it.site.lowercase() == needle }
            ?: credentials.firstOrNull { needle.contains(it.site.lowercase()) }
            ?: credentials.firstOrNull { it.site.lowercase().contains(needle) }
    }

    private fun sanitize(value: String): String =
        value.replace(FIELD, ' ').replace(RECORD, ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
