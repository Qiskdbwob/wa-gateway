package com.example.agent.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Drives one browser session for the agent and owns the human-in-the-loop handoff.
 *
 * Two things make this an *agent* browser rather than a script:
 *  1. `login()` uses a credential the user stored in Settings (never echoed back), and
 *  2. when the page demands a captcha / 2FA / anything only a human can pass,
 *     [requestUserHelp] parks the turn, tells the user over the chat, and resumes as soon as
 *     they tap "Selesai" in the Browser screen. The agent never pretends to have solved it.
 */
class BrowserAutomationManager(
    private val engine: BrowserEngine,
    private val credentials: () -> List<SiteCredential>,
    /** Tells the user (over the chat channel) that their hands are needed. */
    private val notifyUser: suspend (conversationId: String, message: String) -> Unit = { _, _ -> }
) {

    data class UserActionRequest(
        val id: String,
        val instruction: String,
        val requestedAt: Long = System.currentTimeMillis()
    )

    private val _pendingUserAction = MutableStateFlow<UserActionRequest?>(null)
    val pendingUserAction: StateFlow<UserActionRequest?> = _pendingUserAction.asStateFlow()

    @Volatile
    private var waiter: CompletableDeferred<Unit>? = null

    val engineKind: String get() = engine.kind

    fun isReady(): Boolean = engine.isReady()

    suspend fun open(url: String): BrowserActionResult = engine.open(url)

    suspend fun read(maxChars: Int = 8_000): BrowserSnapshot = engine.snapshot(maxChars)

    suspend fun click(ref: String): BrowserActionResult = engine.click(ref)

    suspend fun type(ref: String, text: String, submit: Boolean): BrowserActionResult =
        engine.typeText(ref, text, submit)

    suspend fun scroll(direction: String, amountPx: Int = 800): BrowserActionResult =
        engine.scroll(direction, amountPx)

    suspend fun screenshot(): ByteArray? = engine.screenshot()

    suspend fun currentUrl(): String = engine.currentUrl()

    suspend fun clearSession() = engine.clearSession()

    fun dispose() = engine.dispose()

    /**
     * Signs in with a credential the user saved for [site].
     *
     * The site-specific parts (which input is the username field, whether the flow is
     * two-step) cannot be known in advance, so this does the honest generic thing: open the
     * login page, fill the first text/email field and the password field, submit, then check
     * the result. If a captcha or a second factor blocks the way it hands over to the user
     * instead of claiming success.
     */
    suspend fun login(site: String, conversationId: String = ""): BrowserActionResult {
        val credential = SiteCredentialStore.find(credentials(), site)
            ?: return BrowserActionResult(
                ok = false,
                error = "Tidak ada akun tersimpan untuk \"$site\". Minta pengguna menambahkannya di " +
                    "Pengaturan → Browser (nama situs, URL login, username, password)."
            )

        val target = credential.loginUrl.ifBlank { "https://$site" }
        val opened = engine.open(target)
        if (!opened.ok) return opened

        val snapshot = engine.snapshot(6_000)
        val passwordField = snapshot.elements.firstOrNull {
            it.type.equals("password", ignoreCase = true)
        }
        val userField = snapshot.elements.firstOrNull {
            it.tag == "input" && (it.type.equals("email", true) || it.type.equals("text", true) ||
                it.type.isEmpty())
        }

        if (userField == null || passwordField == null) {
            // Either already logged in, or a flow we cannot fill blindly (SSO, QR, phone number).
            if (looksLikeChallenge(snapshot)) {
                val done = requestUserHelp(
                    instruction = "Halaman login $site butuh verifikasi manusia (captcha/2FA).",
                    conversationId = conversationId
                )
                return BrowserActionResult(
                    ok = done,
                    url = snapshot.url,
                    detail = if (done) "Verifikasi diselesaikan pengguna, sesi login dilanjutkan." else "Pengguna belum menyelesaikan verifikasi.",
                    error = if (done) null else "Verifikasi manual belum selesai."
                )
            }
            return BrowserActionResult(
                ok = false,
                url = snapshot.url,
                error = "Form login $site tidak dikenali otomatis (tidak ada field username/password). " +
                    "Minta pengguna memakai tombol \"Login manual\" di tab Browser, atau pakai browser_read " +
                    "untuk memeriksa halaman dan browser_type/browser_click untuk mengisi manual."
            )
        }

        engine.typeText(userField.ref, credential.username, submit = false)
        engine.typeText(passwordField.ref, credential.password, submit = true)

        val after = engine.snapshot(6_000)
        if (looksLikeChallenge(after)) {
            val done = requestUserHelp(
                instruction = "$site meminta captcha/verifikasi setelah login.",
                conversationId = conversationId
            )
            val finalState = engine.snapshot(4_000)
            return BrowserActionResult(
                ok = done,
                url = finalState.url,
                detail = if (done) "Verifikasi diselesaikan pengguna." else "Verifikasi belum selesai.",
                error = if (done) null else "Verifikasi manual belum selesai."
            )
        }

        // A password field that is still on screen after submitting means the login did not go
        // through — better to say so than to claim a session we do not have.
        val stillLoginForm = after.elements.any { it.type.equals("password", true) }
        return if (stillLoginForm) {
            BrowserActionResult(
                ok = false,
                url = after.url,
                error = "Login $site belum berhasil (form login masih tampil). Periksa username/password " +
                    "di Pengaturan, atau minta pengguna menyelesaikan login manual di tab Browser."
            )
        } else {
            BrowserActionResult(ok = true, url = after.url, detail = "Berhasil login ke $site.")
        }
    }

    /**
     * Parks the current turn until the user confirms they did something by hand (captcha,
     * 2FA, an OTP from their phone). Times out instead of hanging forever.
     */
    suspend fun requestUserHelp(
        instruction: String,
        conversationId: String = "",
        timeoutMs: Long = DEFAULT_HANDOFF_TIMEOUT_MS
    ): Boolean {
        val request = UserActionRequest(
            id = "uact-" + UUID.randomUUID().toString().take(6),
            instruction = instruction
        )
        val gate = CompletableDeferred<Unit>()
        waiter = gate
        _pendingUserAction.value = request

        try {
            notifyUser(
                conversationId,
                "🙋 $instruction\nBuka aplikasi → tab Browser, selesaikan langkahnya, lalu tekan " +
                    "\"Selesai — lanjutkan agent\"."
            )
        } catch (_: Exception) {
            // The chat channel may be unavailable (offline, no session); the tool result still explains.
        }

        val finished = withTimeoutOrNull(timeoutMs) { gate.await() } != null
        _pendingUserAction.value = null
        waiter = null
        return finished
    }

    /** Called by the Browser screen when the user says the manual step is done. */
    fun completeUserAction() {
        waiter?.complete(Unit)
    }

    /** Called when the user gives up; the agent is told the step did not happen. */
    fun abandonUserAction() {
        val gate = waiter
        waiter = null
        _pendingUserAction.value = null
        gate?.complete(Unit)
    }

    /** A compact, model-friendly description of what is on screen. */
    fun describe(snapshot: BrowserSnapshot, maxChars: Int = 6_000): String = buildString {
        appendLine("URL: ${snapshot.url}")
        if (snapshot.title.isNotBlank()) appendLine("Judul: ${snapshot.title}")
        if (snapshot.error != null) appendLine("Catatan: ${snapshot.error}")
        appendLine()
        appendLine("Teks halaman:")
        appendLine(snapshot.text.take(maxChars))
        if (snapshot.elements.isNotEmpty()) {
            appendLine()
            appendLine("Elemen interaktif (pakai ref ini di browser_click / browser_type):")
            snapshot.elements.take(40).forEach { element ->
                val value = if (element.value.isBlank()) "" else " value=\"${element.value}\""
                appendLine("• ${element.ref} <${element.tag}${if (element.type.isBlank()) "" else " type=${element.type}"}> \"${element.label}\"$value")
            }
        }
    }

    private fun looksLikeChallenge(snapshot: BrowserSnapshot): Boolean {
        val haystack = (snapshot.text + " " + snapshot.elements.joinToString(" ") { it.label })
            .lowercase()
        return CHALLENGE_MARKERS.any { haystack.contains(it) }
    }

    companion object {
        /** 6 minutes: long enough to solve a captcha, short enough not to hang a turn forever. */
        const val DEFAULT_HANDOFF_TIMEOUT_MS = 6 * 60 * 1000L

        private val CHALLENGE_MARKERS = listOf(
            "captcha", "recaptcha", "hcaptcha", "turnstile", "verify you are human",
            "verifikasi", "two-factor", "2fa", "one-time code", "kode verifikasi",
            "security code", "confirm you are not a robot"
        )
    }
}
