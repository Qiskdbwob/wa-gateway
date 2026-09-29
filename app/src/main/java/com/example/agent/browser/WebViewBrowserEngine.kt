package com.example.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Browser automation on Android's own WebView engine.
 *
 * Why the platform engine instead of the GeckoView build the reference list mentions: GeckoView
 * needs Mozilla's Maven repository, a Java 17 toolchain bump for the whole app and roughly
 * 100 MB of native libraries per ABI, on an app that already ships a Go/ABI-heavy gateway.
 * Android's WebView is present on every device, supports the same automation surface used
 * here (JS evaluation, DOM access, cookie jar, rendering to a bitmap) and keeps the APK
 * small and the CI build reproducible. Everything the agent sees goes through [BrowserEngine],
 * so a GeckoView implementation can be dropped in later without touching a single tool.
 *
 * The session is a single long-lived [WebView] kept on the main thread; it survives between
 * tool calls, which is what makes login + multi-step flows (type, click, submit) work. While
 * the Browser screen is open the same instance is attached to the UI so the user can solve a
 * captcha or finish a 2FA step by hand.
 *
 * Session persistence: the cookie jar belongs to the app-wide [CookieManager], not to this
 * WebView instance, and WebView stores it in the app's private data directory. So a login done
 * once (by the agent via `browser_login`, or by the user in the Browser screen) is reused for
 * later tool calls, for a recreated WebView, and across app restarts — until the app is cleared
 * or the agent calls `browser_logout` / [clearSession], which wipes cookies, cache and form
 * data. [typeText] with `submit = true` and every [click] flush the jar to disk immediately so a
 * process kill right after login cannot lose it.
 *
 * Accuracy model (three things, in order):
 *  1. **Validate before acting.** Every action goes through [BrowserScripts], which refuses to
 *     touch an element that is gone, zero-sized, disabled, readonly, or covered by another
 *     element at its own centre (`document.elementFromPoint`). A programmatic `click()` bypasses
 *     hit-testing, so without that check an overlay could swallow or misroute the action.
 *  2. **Wait for the page, don't guess.** After an action the engine arms a `MutationObserver`
 *     and polls until the DOM stops changing ([waitForSettle]) instead of reading the page
 *     immediately — the previous behaviour read a pre-render DOM on every SPA.
 *  3. **Verify after acting.** A URL + DOM + form-value fingerprint is taken before and after, so
 *     a click that changed nothing is reported as `changed = false` rather than a silent success.
 *
 * Reliability: WebView renders in its own process, and Android may kill that renderer under
 * memory pressure. When that happens the view can never be reused, so [onRenderProcessGone]
 * removes and destroys it and the next call transparently builds a fresh one — the login
 * cookies live in the app-wide jar, so the new renderer starts already signed in.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewBrowserEngine(
    /** Updated by the Browser screen so the WebView is created with an Activity context when possible. */
    private val contextProvider: () -> Context,
    private val userAgentProvider: () -> String? = { null }
) : BrowserEngine {

    override val kind: String = "android-webview"

    private val mainDispatcher = Dispatchers.Main

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageLoaded: CompletableDeferred<Unit>? = null

    @Volatile
    private var lastError: String? = null

    /** How many times the system killed the WebView renderer; surfaced for honest diagnostics. */
    @Volatile
    private var rendererRestarts: Int = 0

    /** Last URL asked for, so a recreated renderer can restore the session it was on. */
    @Volatile
    private var lastLoadedUrl: String? = null

    /**
     * Elements of the last snapshot, keyed by ref. When a ref goes stale because the page
     * re-rendered, the remembered descriptor (tag/type/label/value) lets [resolveRefAgain] find
     * the same control under its new ref instead of failing outright.
     */
    @Volatile
    private var lastDescriptors: Map<String, BrowserElement> = emptyMap()

    override fun isReady(): Boolean = webView != null

    /** The live view, so the Browser screen can show exactly what the agent is doing. */
    fun viewOrNull(): WebView? = webView

    /** Prepares the WebView on the main thread (no-op when it already exists). */
    private suspend fun obtain(): WebView = withContext(mainDispatcher) {
        webView ?: createWebView().also { webView = it }
    }

    private fun createWebView(): WebView {
        val view = WebView(contextProvider())
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentProvider()?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
        }
        // Keep this renderer alive as long as the app is alive: automation runs in the
        // background while the user is on another screen, and a killed renderer would abort a
        // multi-step flow (the flags ask the system not to treat us as a kill candidate first).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        // OAuth/SSO flows commonly live in a third-party iframe; without this the login looks
        // like it never completes even though the form did submit.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded?.complete(Unit)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    lastError = "Gagal memuat halaman: ${error?.description ?: "unknown error"}"
                    pageLoaded?.complete(Unit)
                }
            }

            /**
             * A 4xx/5xx for the *main frame* is a failure even though `onPageFinished` still
             * fires: without this, an anti-bot wall or a 404 page looked like a successful load
             * and the agent then "read" an error page as if it were content. Sub-resource errors
             * are ignored on purpose (they are normal and would otherwise poison the state).
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    val reason = errorResponse?.reasonPhrase.orEmpty()
                    lastError = "Halaman mengembalikan HTTP ${code}${if (reason.isBlank()) "" else " $reason"}" +
                        " — kemungkinan diblokir atau tidak ditemukan, bukan halaman yang diminta."
                    pageLoaded?.complete(Unit)
                }
            }

            /**
             * The renderer process is gone (Android reclaimed memory or it crashed). A WebView
             * whose renderer died can never be reused, so the only correct response is to
             * remove it, destroy it and build a new one. Returning `true` tells the platform the
             * app handled the event and keeps the process alive; the cookie jar is untouched, so
             * the new renderer is still logged in.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                rendererRestarts += 1
                val cause = if (detail?.didCrash() == true) "crash" else "dihentikan sistem (memori rendah)"
                lastError = "Renderer WebView $cause; sesi dibangun ulang ($rendererRestarts/$MAX_RENDERER_RECOVERIES)."
                pageLoaded?.complete(Unit)
                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    try {
                        view.destroy()
                    } catch (_: Exception) {
                        // Already gone.
                    }
                    if (webView === view) webView = null
                }
                // Recreate right away - this callback already runs on the main thread - so the
                // next tool call and the Browser screen get a usable view instead of a blank one.
                // After a few restarts the page itself is suspect, so recovery stops and the error
                // above tells the agent (and the log) what happened.
                if (rendererRestarts <= MAX_RENDERER_RECOVERIES) {
                    try {
                        val replacement = createWebView()
                        webView = replacement
                        lastLoadedUrl?.let { replacement.loadUrl(it) }
                    } catch (e: Exception) {
                        webView = null
                        lastError = "Gagal membuat ulang WebView: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                return true
            }
        }
        view.webChromeClient = WebChromeClient()
        // Lay the view out at a phone-sized viewport so JS layout and screenshots work even
        // while the Browser screen is closed (the WebView is then not attached to a window).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        CookieManager.getInstance().setAcceptCookie(true)
        return view
    }

    override suspend fun open(url: String): BrowserActionResult {
        val normalized = normalizeUrl(url)
            ?: return BrowserActionResult(ok = false, error = "URL tidak valid: \"$url\"")

        val view = obtain()
        val gate = CompletableDeferred<Unit>()
        pageLoaded = gate
        lastError = null
        lastDescriptors = emptyMap()

        lastLoadedUrl = normalized
        withContext(mainDispatcher) { view.loadUrl(normalized) }
        withTimeoutOrNull(PAGE_TIMEOUT_MS) { gate.await() }
        // `onPageFinished` fires for the *document*, not for the app that renders into it: give
        // SPA hydration a chance to settle before the first snapshot is taken.
        waitForSettle(OPEN_SETTLE_TIMEOUT_MS)

        val currentUrl = withContext(mainDispatcher) { view.url ?: normalized }
        val error = lastError
        return if (error != null) {
            BrowserActionResult(ok = false, url = currentUrl, error = error)
        } else {
            BrowserActionResult(
                ok = true,
                url = currentUrl,
                detail = "Halaman dimuat.",
                changed = true
            )
        }
    }

    override suspend fun snapshot(maxChars: Int): BrowserSnapshot {
        val raw = evaluateJs(BrowserScripts.SNAPSHOT)
            ?: return BrowserSnapshot("", "", "", emptyList(), "Browser belum membuka halaman apa pun.")
        return decodeSnapshot(raw, maxChars)
            ?: BrowserSnapshot("", "", "", emptyList(), "Gagal membaca halaman (format tidak dikenali).")
    }

    /** Parses a snapshot payload and remembers the descriptors for later re-resolution. */
    private fun decodeSnapshot(raw: String, maxChars: Int): BrowserSnapshot? {
        return try {
            val json = JSONObject(raw)
            val elements = mutableListOf<BrowserElement>()
            val array = json.optJSONArray("elements")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    elements += BrowserElement(
                        ref = item.optString("ref"),
                        tag = item.optString("tag"),
                        type = item.optString("type"),
                        label = item.optString("label"),
                        value = item.optString("value")
                    )
                }
            }
            lastDescriptors = elements.associateBy { it.ref }
            BrowserSnapshot(
                url = json.optString("url"),
                title = json.optString("title"),
                text = json.optString("text").take(maxChars),
                elements = elements,
                error = json.optString("error").takeIf { it.isNotBlank() && it != "undefined" }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Re-runs the snapshot only to refresh refs, discarding the text (used after a stale ref). */
    private suspend fun refreshElements(): List<BrowserElement>? {
        val raw = evaluateJs(BrowserScripts.SNAPSHOT) ?: return null
        return decodeSnapshot(raw, 1)?.elements
    }

    /**
     * Re-finds the element behind a stale [ref] by matching its remembered descriptor against a
     * fresh snapshot. Returns the new ref, or null when nothing matches confidently.
     */
    private suspend fun resolveRefAgain(ref: String): String? {
        val remembered = lastDescriptors[ref] ?: return null
        val candidates = refreshElements() ?: return null
        return BrowserScripts.matchDescriptor(remembered, candidates)
    }

    /**
     * Shared action path for every ref-based action:
     *
     *  1. fingerprint the page,
     *  2. arm the mutation counter and run the action,
     *  3. on a stale ref, wait briefly and re-resolve the element by descriptor, then retry,
     *  4. on success wait for the DOM to settle and fingerprint again to report `changed`.
     *
     * Failures that are *not* a stale ref (disabled, occluded, readonly, …) stop immediately with
     * an honest message; retrying those would just repeat the same wrong action.
     */
    private suspend fun runLocating(
        detail: String,
        ref: String,
        persistOnSuccess: Boolean,
        buildScript: (String) -> String
    ): BrowserActionResult {
        val before = fingerprint()
        var target = ref
        var attempt = 1
        while (attempt <= MAX_LOCATE_ATTEMPTS) {
            evaluateJs(BrowserScripts.ARM_SETTLE)
            val token = BrowserScripts.stripQuotes(evaluateJs(buildScript(target)))
                ?: return BrowserActionResult(false, error = "Browser belum siap.")
            if (token == "ok") {
                if (persistOnSuccess) persistCookies()
                waitForSettle()
                val after = fingerprint()
                return BrowserActionResult(
                    ok = true,
                    url = currentUrl(),
                    detail = detail,
                    changed = BrowserScripts.changedBetween(before, after)
                )
            }
            if (token == "not-found" && attempt < MAX_LOCATE_ATTEMPTS) {
                attempt += 1
                delay(LOCATE_RETRY_DELAY_MS)
                // The page may still be re-rendering: re-snapshot and look the element up again by
                // what it looked like (tag/type/label/value) instead of giving up.
                resolveRefAgain(target)?.let { target = it }
                continue
            }
            // Anything else (disabled, occluded, readonly, error …) is not a staleness problem, so
            // repeating the same action would only repeat the same wrong attempt.
            return BrowserActionResult(false, url = currentUrl(), error = BrowserScripts.describeToken(token, ref))
        }
        return BrowserActionResult(
            false,
            url = currentUrl(),
            error = BrowserScripts.describeToken("not-found", ref)
        )
    }

    override suspend fun click(ref: String): BrowserActionResult {
        val safe = safeRef(ref) ?: return BrowserActionResult(false, error = "Ref elemen tidak valid: $ref")
        // A click can complete a login or an "remember me" consent: make sure the cookie jar hits
        // disk before anything can kill the process.
        return runLocating("Elemen $ref diklik.", safe, persistOnSuccess = true) { target ->
            BrowserScripts.click(target)
        }
    }

    override suspend fun typeText(ref: String, text: String, submit: Boolean): BrowserActionResult {
        val safe = safeRef(ref) ?: return BrowserActionResult(false, error = "Ref elemen tidak valid: $ref")
        val literal = BrowserScripts.jsStringLiteral(text)
        val detail = if (submit) "Teks diketik ke $ref dan form disubmit." else "Teks diketik ke $ref."
        // Submitting a form is the moment a session cookie is written (login, 2FA confirmation,
        // "remember this device"), so persist immediately in that case only.
        return runLocating(detail, safe, persistOnSuccess = submit) { target ->
            BrowserScripts.type(target, literal, submit)
        }
    }

    override suspend fun selectOption(ref: String, value: String): BrowserActionResult {
        val safe = safeRef(ref) ?: return BrowserActionResult(false, error = "Ref elemen tidak valid: $ref")
        val literal = BrowserScripts.jsStringLiteral(value)
        return runLocating("\"$value\" dipilih pada $ref.", safe, persistOnSuccess = false) { target ->
            BrowserScripts.select(target, literal)
        }
    }

    override suspend fun pressKey(key: String): BrowserActionResult {
        val normalized = BrowserScripts.normalizeKey(key)
            ?: return BrowserActionResult(
                false,
                error = "Tombol \"$key\" tidak didukung. Pakai salah satu dari: " +
                    BrowserScripts.SUPPORTED_KEYS.joinToString(", ") + "."
            )
        val before = fingerprint()
        evaluateJs(BrowserScripts.ARM_SETTLE)
        val token = BrowserScripts.stripQuotes(
            evaluateJs(BrowserScripts.pressKey(BrowserScripts.jsStringLiteral(normalized)))
        ) ?: return BrowserActionResult(false, error = "Browser belum siap.")
        if (token != "ok") {
            return BrowserActionResult(false, url = currentUrl(), error = BrowserScripts.describeToken(token, ""))
        }
        waitForSettle()
        val after = fingerprint()
        return BrowserActionResult(
            ok = true,
            url = currentUrl(),
            detail = "Tombol $normalized ditekan pada elemen yang sedang fokus.",
            changed = BrowserScripts.changedBetween(before, after)
        )
    }

    override suspend fun scroll(direction: String, amountPx: Int): BrowserActionResult {
        val before = fingerprint()
        val result = evaluateJs(BrowserScripts.scroll(direction, amountPx))
            ?: return BrowserActionResult(false, error = "Browser belum siap.")
        if (result.isBlank()) return BrowserActionResult(false, error = "Browser belum siap.")
        val after = fingerprint()
        return BrowserActionResult(
            ok = true,
            url = currentUrl(),
            detail = "Halaman digulir ($direction).",
            changed = BrowserScripts.changedBetween(before, after)
        )
    }

    override suspend fun screenshot(): ByteArray? = withContext(mainDispatcher) {
        val view = webView ?: return@withContext null
        val width = if (view.width > 0) view.width else VIEWPORT_WIDTH
        val height = if (view.height > 0) view.height else VIEWPORT_HEIGHT
        try {
            if (view.width <= 0 || view.height <= 0) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, width, height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun currentUrl(): String =
        withContext(mainDispatcher) { webView?.url.orEmpty() }

    override suspend fun pageTitle(): String =
        withContext(mainDispatcher) { webView?.title.orEmpty() }

    /**
     * Flushes the shared cookie jar to disk. WebView also flushes it periodically, but doing it
     * ourselves after a submit keeps a freshly created session safe from an immediate process kill.
     */
    private suspend fun persistCookies() {
        withContext(mainDispatcher) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
                // Nothing to do: the jar is still flushed by WebView on its own schedule.
            }
        }
    }

    /**
     * Drops the whole browsing session: cookies, cache, form data and history. This is the
     * explicit logout path (`browser_logout`), so the next visit is a clean, logged-out one.
     * A plain [dispose] (process/view teardown) never touches the cookie jar.
     */
    override suspend fun clearSession() {
        withContext(mainDispatcher) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView?.clearCache(true)
            webView?.clearFormData()
            webView?.clearHistory()
            webView?.loadUrl("about:blank")
        }
        lastDescriptors = emptyMap()
    }

    /** Releases the view. The cookie jar stays on disk, so the next session is still logged in. */
    override fun dispose() {
        val view = webView ?: return
        webView = null
        pageLoaded = null
        lastDescriptors = emptyMap()
        try {
            view.post { view.destroy() }
        } catch (_: Exception) {
            // Already destroyed
        }
    }

    /** One fingerprint sample; empty when the page cannot be read yet. */
    private suspend fun fingerprint(): String =
        BrowserScripts.stripQuotes(evaluateJs(BrowserScripts.FINGERPRINT)).orEmpty()

    /**
     * Blocks until the DOM stops changing, or [timeoutMs] passes. The observer is armed by the
     * caller (before the action) so the counter already includes the mutation the action caused;
     * two identical samples in a row mean the page has settled.
     */
    private suspend fun waitForSettle(timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        if (webView == null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            delay(SETTLE_POLL_MS)
            val sample = BrowserScripts.stripQuotes(evaluateJs(BrowserScripts.SETTLE_SAMPLE)).orEmpty()
            if (sample.isEmpty()) return
            if (sample == last) {
                stable += 1
                if (stable >= SETTLE_STABLE_SAMPLES) return
            } else {
                stable = 0
                last = sample
            }
        }
    }

    private suspend fun evaluateJs(script: String): String? {
        val view = obtain()
        return withContext(mainDispatcher) {
            suspendCancellableCoroutine<String?> { continuation ->
                try {
                    view.evaluateJavascript(script) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun safeRef(ref: String): String? = BrowserScripts.safeRef(ref)

    private fun normalizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") -> trimmed
            else -> "https://" + trimmed.trimStart('/')
        }
        return try {
            java.net.URI(withScheme).toURL()
            withScheme
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920
        private const val PAGE_TIMEOUT_MS = 20_000L

        /** How often a dead renderer is rebuilt automatically before the page is declared broken. */
        private const val MAX_RENDERER_RECOVERIES = 3

        /** How long a fresh document is allowed to keep mutating before the first snapshot. */
        private const val OPEN_SETTLE_TIMEOUT_MS = 3_000L

        /** Upper bound on waiting for the DOM to stop changing after an action. */
        private const val SETTLE_TIMEOUT_MS = 4_000L

        /** Interval between settle samples; two identical samples in a row end the wait. */
        private const val SETTLE_POLL_MS = 250L
        private const val SETTLE_STABLE_SAMPLES = 2

        /** How many times an action is attempted while re-resolving a stale ref. */
        private const val MAX_LOCATE_ATTEMPTS = 3
        private const val LOCATE_RETRY_DELAY_MS = 350L
    }
}
