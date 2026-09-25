package com.example.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

        withContext(mainDispatcher) { view.loadUrl(normalized) }
        withTimeoutOrNull(PAGE_TIMEOUT_MS) { gate.await() }

        val currentUrl = withContext(mainDispatcher) { view.url ?: normalized }
        val error = lastError
        return if (error != null) {
            BrowserActionResult(ok = false, url = currentUrl, error = error)
        } else {
            BrowserActionResult(ok = true, url = currentUrl, detail = "Halaman dimuat.")
        }
    }

    override suspend fun snapshot(maxChars: Int): BrowserSnapshot {
        val raw = evaluateJs(SNAPSHOT_SCRIPT)
            ?: return BrowserSnapshot("", "", "", emptyList(), "Browser belum membuka halaman apa pun.")
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
            BrowserSnapshot(
                url = json.optString("url"),
                title = json.optString("title"),
                text = json.optString("text").take(maxChars),
                elements = elements,
                error = json.optString("error").takeIf { it.isNotBlank() && it != "undefined" }
            )
        } catch (e: Exception) {
            BrowserSnapshot("", "", "", emptyList(), "Gagal membaca halaman: ${e.message}")
        }
    }

    override suspend fun click(ref: String): BrowserActionResult {
        val safe = safeRef(ref) ?: return BrowserActionResult(false, error = "Ref elemen tidak valid: $ref")
        val result = evaluateJs(
            """
            (function(){
              var el = document.querySelector('[data-agx-ref="$safe"]');
              if (!el) return 'not-found';
              try { el.scrollIntoView({block:'center'}); } catch (e) {}
              el.focus();
              el.click();
              return 'ok';
            })()
            """.trimIndent()
        )
        return when (result) {
            null -> BrowserActionResult(false, error = "Browser belum siap.")
            "not-found" -> BrowserActionResult(
                false,
                error = "Elemen $ref tidak ada lagi di halaman. Ambil snapshot baru (browser_read) lalu ulangi."
            )
            else -> {
                // A click can complete a login or an "remember me" consent: make sure the cookie
                // jar hits disk before anything can kill the process.
                persistCookies()
                BrowserActionResult(true, url = currentUrl(), detail = "Elemen $ref diklik.")
            }
        }
    }

    override suspend fun typeText(ref: String, text: String, submit: Boolean): BrowserActionResult {
        val safe = safeRef(ref) ?: return BrowserActionResult(false, error = "Ref elemen tidak valid: $ref")
        val literal = JSONObject.quote(text)
        val result = evaluateJs(
            """
            (function(){
              var el = document.querySelector('[data-agx-ref="$safe"]');
              if (!el) return 'not-found';
              try { el.scrollIntoView({block:'center'}); } catch (e) {}
              el.focus();
              if (el.isContentEditable) { el.innerText = $literal; }
              else { el.value = $literal; }
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              var s = $submit;
              if (s) {
                var form = el.form;
                if (form) {
                  if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
                  else { form.submit(); }
                } else {
                  var opts = {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true};
                  el.dispatchEvent(new KeyboardEvent('keydown', opts));
                  el.dispatchEvent(new KeyboardEvent('keyup', opts));
                }
              }
              return 'ok';
            })()
            """.trimIndent()
        )
        return when (result) {
            null -> BrowserActionResult(false, error = "Browser belum siap.")
            "not-found" -> BrowserActionResult(
                false,
                error = "Elemen $ref tidak ada lagi di halaman. Ambil snapshot baru (browser_read) lalu ulangi."
            )
            else -> {
                // Submitting a form is the moment a session cookie is written (login, 2FA
                // confirmation, "remember this device"): persist it right away.
                if (submit) persistCookies()
                BrowserActionResult(
                    true,
                    url = currentUrl(),
                    detail = if (submit) "Teks dikirim ke $ref dan form disubmit." else "Teks diketik ke $ref."
                )
            }
        }
    }

    override suspend fun scroll(direction: String, amountPx: Int): BrowserActionResult {
        val delta = when (direction.lowercase()) {
            "up" -> -amountPx
            "top" -> Int.MIN_VALUE
            "bottom" -> Int.MAX_VALUE
            else -> amountPx
        }
        val script = if (delta == Int.MIN_VALUE) {
            "window.scrollTo(0,0); 'ok'"
        } else if (delta == Int.MAX_VALUE) {
            "window.scrollTo(0, document.body.scrollHeight); 'ok'"
        } else {
            "window.scrollBy(0, $delta); 'ok'"
        }
        val result = evaluateJs(script)
        return if (result == null) {
            BrowserActionResult(false, error = "Browser belum siap.")
        } else {
            BrowserActionResult(true, url = currentUrl(), detail = "Halaman digulir ($direction).")
        }
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
    }

    /** Releases the view. The cookie jar stays on disk, so the next session is still logged in. */
    override fun dispose() {
        val view = webView ?: return
        webView = null
        pageLoaded = null
        try {
            view.post { view.destroy() }
        } catch (_: Exception) {
            // Already destroyed
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

    private fun safeRef(ref: String): String? {
        val trimmed = ref.trim().lowercase()
        return if (Regex("^[a-z0-9\\-]{1,24}$").matches(trimmed)) trimmed else null
    }

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

        /** Collects the readable text plus every interactive element, tagged for later calls. */
        private val SNAPSHOT_SCRIPT = """
            (function(){
              try {
                var nodes = document.querySelectorAll('a,button,input,textarea,select,[role=button],[contenteditable=true]');
                var out = [];
                var i = 0;
                for (var k = 0; k < nodes.length && i < 80; k++) {
                  var el = nodes[k];
                  var r = el.getBoundingClientRect();
                  if (r.width <= 0 || r.height <= 0) continue;
                  var ref = 'agx-' + i;
                  try { el.setAttribute('data-agx-ref', ref); } catch (e) {}
                  var label = el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.innerText || el.value || el.name || '';
                  out.push({
                    ref: ref,
                    tag: (el.tagName || '').toLowerCase(),
                    type: (el.getAttribute('type') || ''),
                    label: ('' + label).replace(/\s+/g, ' ').trim().slice(0, 90),
                    value: ('' + (el.value || '')).slice(0, 90)
                  });
                  i++;
                }
                return {
                  url: location.href,
                  title: document.title || '',
                  text: (document.body ? (document.body.innerText || '') : '').slice(0, 20000),
                  elements: out
                };
              } catch (e) {
                return { url: location.href, title: '', text: '', elements: [], error: '' + e };
              }
            })()
        """.trimIndent()
    }
}
