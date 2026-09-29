package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.io.IOException

/**
 * Priority 3 — read-only network tools, executed automatically (no approval) because they
 * cannot mutate anything. They are plain JDK/OkHttp-free implementations on purpose so
 * they run in JVM unit tests without Robolectric.
 *
 * Both tools classify every failure as a failed ToolResult — the Agent Loop reports it to
 * the model, which can retry with different arguments or answer without the tool.
 */

/**
 * Web search by scraping, with four independent sources: Bing (default), DuckDuckGo, Mojeek and —
 * as a labelled last resort — Wikipedia. No API key and no SDK: a plain HTTP GET plus the pure
 * parsers in [WebSearchScrape].
 *
 * Two behaviours exist for accuracy rather than convenience:
 *
 *   - an engine is skipped only when it is *unusable* (HTTP error, anti-bot/consent wall, or a page
 *     we could not parse), so one blocked engine never silences the whole tool;
 *   - the outcome tells "search worked, nothing matched" apart from "every engine was blocked".
 *     The first is a normal success; the second fails **with instructions**, so the model says
 *     "pencarian tidak tersedia" instead of inventing news.
 *
 * The walk across engines is bounded by a total wall-clock budget (25 s), so a hanging engine costs
 * one timeout instead of stalling the chat.
 */

/**
 * One engine's answer to a query. Engine names are the labels the model sees in the tool output
 * (`sumber: bing`), so they are part of the tool's contract.
 */
internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

/** How an engine behaved: "0 hasil" and "diblokir" must not look the same to the model. */
internal enum class SearchStatus(val label: String) {
    OK("ok"),
    EMPTY("0 hasil"),
    BLOCKED("diblokir/anti-bot"),
    ERROR("gagal")
}

/** One engine attempt, including why it produced nothing. */
internal data class SearchAttempt(
    val engine: String,
    val status: SearchStatus,
    val detail: String = ""
) {
    val label: String get() = if (detail.isBlank()) "${engine}=${status.label}" else "${engine}=${status.label} (${detail.take(60)})"
}

/** Search result plus the attempt trace of every engine that was tried. */
internal data class SearchOutcome(
    val results: List<WebSearchHit>,
    val attempts: List<SearchAttempt>
) {
    /** True when at least one engine answered with a page we could actually read. */
    val reachable: Boolean
        get() = attempts.any { it.status == SearchStatus.OK || it.status == SearchStatus.EMPTY }
}

/**
 * Pure rendering of a [SearchOutcome] into the text the model reads.
 *
 * Kept top-level and free of network so the honesty rules are unit-testable: no results is a
 * success, a blocked search is a failure that tells the model what to say instead of guessing.
 */
internal fun renderSearchOutcome(query: String, outcome: SearchOutcome, maxResults: Int): ToolResult {
    val checked = outcome.attempts.joinToString(", ") { it.label }
    if (outcome.results.isNotEmpty()) {
        val source = outcome.results.first().source
        val body = outcome.results
            .take(maxResults)
            .mapIndexed { index, hit -> "${index + 1}. ${hit.title}\n   ${hit.url}\n   ${hit.snippet}" }
            .joinToString("\n\n")
        val caution = if (source == "wikipedia") {
            "\n\nCatatan: ini hasil ensiklopedia (bukan berita terkini) karena mesin pencari umum sedang " +
                "tidak bisa dipakai. Sebutkan hal itu ke pengguna."
        } else {
            ""
        }
        return ToolResult(
            success = true,
            output = "Hasil pencarian \"$query\" (sumber: $source):\n\n$body\n\n(engine dicek: $checked)$caution",
            metadata = mapOf(
                "count" to outcome.results.size.toString(),
                "query" to query,
                "source" to source
            )
        )
    }
    if (outcome.reachable) {
        return ToolResult(
            success = true,
            output = "Tidak ada hasil untuk \"$query\" (engine dicek: $checked). " +
                "Coba kata kunci lain atau bahasa lain; untuk halaman tertentu pakai web_fetch dengan URL-nya.",
            metadata = mapOf("count" to "0", "query" to query, "attempts" to checked)
        )
    }
    // Nothing was reachable: a real failure, with the recovery script the model should follow.
    return ToolResult(
        success = false,
        output = "",
        error = "web_search gagal: tidak ada mesin pencari yang bisa dipakai ($checked). " +
            "Katakan ke pengguna pencarian sedang tidak tersedia — jangan mengarang hasil — lalu tawarkan: " +
            "coba lagi nanti, atau kirim URL langsung untuk dibaca lewat web_fetch."
    )
}

class WebSearchTool : Tool {

    override val id: String = "builtin.web_search"
    override val name: String = "web_search"
    override val description: String =
        "Mencari informasi di internet tanpa API key (Bing, lalu DuckDuckGo, Mojeek, terakhir Wikipedia). " +
            "Gunakan untuk fakta terkini, berita, harga, atau hal di luar pengetahuan Anda; hasilnya judul, URL, dan cuplikan. " +
            "Kalau tool ini gagal, JANGAN mengarang isi berita: katakan pencarian tidak tersedia dan tawarkan " +
            "alternatif (coba lagi nanti, atau minta URL untuk dibaca dengan web_fetch)."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Kata kunci pencarian, boleh bahasa apa pun."
            },
            "max_results": {
              "type": "integer",
              "description": "Jumlah hasil maksimum, default 5, maksimum 8."
            }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult = try {
        val query = JsonArgs.string(input, "query")?.trim().orEmpty()
        if (query.isEmpty()) {
            ToolResult(success = false, output = "", error = "Argumen 'query' wajib diisi.")
        } else {
            val maxResults = (JsonArgs.int(input, "max_results") ?: 5).coerceIn(1, 8)
            renderSearchOutcome(query, search(query, maxResults), maxResults)
        }
    } catch (e: Exception) {
        ToolResult(success = false, output = "", error = "web_search gagal: ${e.message ?: e.javaClass.simpleName}")
    }

    /** One search source: where to fetch it and how to turn that page into rows. */
    private data class Engine(
        val name: String,
        val url: (encodedQuery: String) -> String,
        val parse: (html: String) -> List<Triple<String, String, String>>
    )

    /**
     * Engines in usefulness order: general web first, the encyclopedia last. The first engine that
     * returns rows wins, so a normal search stays a single request.
     */
    private val engines: List<Engine> = listOf(
        Engine("bing", { "https://www.bing.com/search?q=$it&count=$RESULT_COUNT" }, WebSearchScrape::parseBing),
        Engine("duckduckgo", { "https://html.duckduckgo.com/html/?q=$it" }, WebSearchScrape::parseDuckDuckGo),
        Engine("mojeek", { "https://www.mojeek.com/search?q=$it" }, WebSearchScrape::parseMojeek),
        Engine("wikipedia", { "https://id.wikipedia.org/w/index.php?search=$it" }, WebSearchScrape::parseWikipedia)
    )

    /**
     * Walks the engines until one answers with rows. One blocked engine only costs its own attempt:
     * the next one is tried, and every attempt is recorded so the caller can explain the outcome.
     */
    private fun search(query: String, maxResults: Int): SearchOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val attempts = mutableListOf<SearchAttempt>()
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS

        for (engine in engines) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= MIN_USEFUL_TIMEOUT_MS) {
                attempts += SearchAttempt(engine.name, SearchStatus.ERROR, "dilewati: batas waktu total")
                continue
            }
            val html = try {
                httpGet(engine.url(encoded), remaining)
            } catch (e: Exception) {
                attempts += SearchAttempt(engine.name, SearchStatus.ERROR, e.message ?: e.javaClass.simpleName)
                continue
            }
            if (WebSearchScrape.looksBlocked(html)) {
                attempts += SearchAttempt(engine.name, SearchStatus.BLOCKED, "halaman captcha/consent")
                continue
            }
            val parsed = engine.parse(html)
            if (parsed.isEmpty()) {
                attempts += SearchAttempt(engine.name, SearchStatus.EMPTY)
                continue
            }
            attempts += SearchAttempt(engine.name, SearchStatus.OK, "${parsed.size} hasil")
            return SearchOutcome(
                results = parsed.take(maxResults).map {
                    WebSearchHit(it.first, it.second, it.third, engine.name)
                },
                attempts = attempts
            )
        }
        return SearchOutcome(emptyList(), attempts)
    }

    /**
     * Plain HTTP GET (network on the caller's IO dispatcher) returning the body as UTF-8,
     * capped so a huge page cannot exhaust memory. Throws IOException on HTTP errors.
     *
     * [budgetMs] is what remains of the tool's total search budget; both timeouts are clamped to
     * it so one slow engine can never consume the time of the engines after it.
     */
    private fun httpGet(url: String, budgetMs: Long = DEFAULT_BUDGET_MS): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = clampTimeout(budgetMs, CONNECT_TIMEOUT_MS)
            connection.readTimeout = clampTimeout(budgetMs, READ_TIMEOUT_MS)
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code dari $url")
            return connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                }
                buffer.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun clampTimeout(budgetMs: Long, cap: Long): Int =
        minOf(budgetMs, cap).coerceAtLeast(MIN_TIMEOUT_MS).toInt()

    private companion object {
        const val MAX_BYTES = 512 * 1024

        /** How many rows are asked for; the tool still trims to `max_results` afterwards. */
        const val RESULT_COUNT = 8

        /** Wall-clock budget for the whole search, across every engine. */
        const val TOTAL_TIMEOUT_MS = 25_000L

        /** Below this there is no point starting another engine: it would only time out. */
        const val MIN_USEFUL_TIMEOUT_MS = 3_000L

        const val DEFAULT_BUDGET_MS = 12_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val READ_TIMEOUT_MS = 12_000L
        const val MIN_TIMEOUT_MS = 1_500L

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/**
 * Pure HTML parsing for [WebSearchTool]: Bing (default), DuckDuckGo and Mojeek endpoints plus
 * Wikipedia search, and the anti-bot wall check. Each hit is (title, url, snippet) — no network, no
 * Android, so the regexes are tested against sample pages instead of "works today".
 *
 * A parser that meets unexpected markup returns an empty list rather than throwing: the tool then
 * reports that engine as "0 hasil" and moves on to the next source.
 */
object WebSearchScrape {

    /** Bing result rows; empty when the markup changes or a consent page is served. */
    fun parseBing(html: String): List<Triple<String, String, String>> =
        Regex("""<li class="b_algo".*?</li>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .mapNotNull { block ->
                val link = Regex(
                    """<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(block.value) ?: return@mapNotNull null
                val url = link.groupValues[1].replace("&amp;", "&").trim()
                if (!url.startsWith("http")) return@mapNotNull null
                val title = stripHtml(link.groupValues[2]).take(160).ifBlank { url }
                val snippet = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                    .find(block.value)?.groupValues?.get(1)
                    ?.let(::stripHtml)?.take(300).orEmpty()
                Triple(title, url, snippet)
            }
            .toList()

    /** DuckDuckGo HTML endpoint rows (the fallback source). */
    fun parseDuckDuckGo(html: String): List<Triple<String, String, String>> {
        val linkRegex = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"")
        val titleRegex = Regex("""class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkRegex.findAll(html).toList()
        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        val hits = mutableListOf<Triple<String, String, String>>()
        for (i in links.indices) {
            val rawUrl = links[i].groupValues[1]
            val url = decodeDuckUrl(rawUrl) ?: continue
            if (url.startsWith("https://duckduckgo.com") || url.startsWith("http://duckduckgo.com")) continue
            val title = titles.getOrNull(i)?.groupValues?.get(1)?.let(::stripHtml)?.take(160) ?: url
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.let(::stripHtml)?.take(300) ?: ""
            hits.add(Triple(title, url, snippet))
        }
        return hits
    }

    private fun decodeDuckUrl(raw: String): String? {
        val decoded = if (raw.contains("uddg=")) {
            val start = raw.indexOf("uddg=") + 5
            val end = raw.indexOf('&', start).let { if (it < 0) raw.length else it }
            java.net.URLDecoder.decode(raw.substring(start, end), "UTF-8")
        } else {
            if (raw.startsWith("//")) "https:$raw" else raw
        }
        return if (decoded.startsWith("http")) decoded else null
    }

    /**
     * Mojeek rows — an index independent from Bing/DDG, used when both of those are walled off.
     *
     * Its markup is less stable than Bing's, so the scan is anchored on the results list and
     * returns nothing when the structure moves on: the caller then reports "0 hasil" for Mojeek and
     * falls through to the next source instead of inventing rows.
     */
    fun parseMojeek(html: String): List<Triple<String, String, String>> {
        val anchor = html.indexOf("results-standard")
        if (anchor < 0) return emptyList()
        return Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html.substring(anchor))
            .mapNotNull { block ->
                val body = block.value
                val link = Regex("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .find(body) ?: return@mapNotNull null
                val url = link.groupValues[1].trim()
                val title = stripHtml(link.groupValues[2]).take(160)
                if (title.isBlank()) return@mapNotNull null
                val snippet = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1)
                    ?.let(::stripHtml)?.take(300).orEmpty()
                Triple(title, url, snippet)
            }
            .toList()
    }

    /**
     * Wikipedia search rows — the last resort. It is an encyclopedia, so the hits are usually not
     * the latest news; [renderSearchOutcome] labels the source as such so the model can say so.
     */
    fun parseWikipedia(html: String): List<Triple<String, String, String>> =
        Regex(
            "<li[^>]*class=\"[^\"]*mw-search-result[^\"]*\"(.*?)</li>",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html)
            .mapNotNull { block ->
                val body = block.value
                val href = Regex("href=\"(/wiki/[^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val title = Regex("<a[^>]*title=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?.let(::stripHtml)?.take(160)
                    ?: return@mapNotNull null
                val snippet = Regex(
                    "<div[^>]*class=\"[^\"]*searchresult[^\"]*\"[^>]*>(.*?)</div>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(body)?.groupValues?.get(1)?.let(::stripHtml)?.take(300).orEmpty()
                Triple(title, "https://id.wikipedia.org$href", snippet)
            }
            .toList()

    /**
     * Heuristic "we were served a wall instead of results" check over the start of the page.
     *
     * Only markers that do not appear on a normal result page are used: a false positive only costs
     * one skipped engine (the next one is tried), while wall HTML would have parsed to zero rows
     * anyway — but the model deserves to know the difference between "tidak ada hasil" and
     * "diblokir".
     */
    fun looksBlocked(html: String): Boolean {
        val head = html.take(BLOCK_SCAN_CHARS).lowercase()
        return BLOCK_MARKERS.any { head.contains(it) }
    }

    /** Markers of captcha/consent/rate-limit walls; lowercase to match [looksBlocked]. */
    private val BLOCK_MARKERS = listOf(
        "unusual traffic",
        "are you a robot",
        "verify you are human",
        "consent.bing.com",
        "enable javascript and cookies",
        "checking your browser",
        "cf-browser-verification",
        "too many requests",
        "bots use duckduckgo",
        "access denied"
    )

    private const val BLOCK_SCAN_CHARS = 4_000

    /** Strips tags, unescapes the common entities and squeezes whitespace. */
    fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Fetches a web page and returns readable text (HTML stripped, size-capped). */
class WebFetchTool : Tool {

    override val id: String = "builtin.web_fetch"
    override val name: String = "web_fetch"
    override val description: String =
        "Mengambil isi halaman web sebagai teks bersih. Gunakan setelah web_search memberi URL, atau saat pengguna memberi tautan."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "URL lengkap halaman yang akan dibaca, diawali http(s)://"
            },
            "max_chars": {
              "type": "integer",
              "description": "Batas karakter teks yang dikembalikan, default 6000, maksimum 16000."
            }
          },
          "required": ["url"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult = try {
        val rawUrl = JsonArgs.string(input, "url")?.trim().orEmpty()
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            ToolResult(success = false, output = "", error = "Argumen 'url' harus diawali http:// atau https://")
        } else {
            val maxChars = (JsonArgs.int(input, "max_chars") ?: 6_000).coerceIn(500, 16_000)
            val (finalUrl, body) = fetchText(rawUrl)
            val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.trim()?.take(200)
            val text = extractText(body).take(maxChars)
            if (text.isBlank()) {
                ToolResult(success = false, output = "", error = "Halaman tidak mengandung teks yang bisa dibaca.")
            } else {
                val truncatedNote = if (text.length >= maxChars) "\n\n...(dipotong pada $maxChars karakter)" else ""
                ToolResult(
                    success = true,
                    output = buildString {
                        title?.let { append("Judul: ").append(it).append('\n') }
                        append("URL: ").append(finalUrl).append("\n\n")
                        append(text)
                        append(truncatedNote)
                    },
                    metadata = mapOf("url" to finalUrl, "chars" to text.length.toString())
                )
            }
        }
    } catch (e: SocketTimeoutException) {
        ToolResult(success = false, output = "", error = "web_fetch gagal: timeout mengambil halaman")
    } catch (e: Exception) {
        ToolResult(success = false, output = "", error = "web_fetch gagal: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Follows at most 3 redirects manually (JDK's HttpURLConnection already follows same-protocol). */
    private fun fetchText(url: String): Pair<String, String> {
        var current = url
        repeat(3) {
            val connection = openConnection(current)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect tanpa Location header")
                current = java.net.URI(current).resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code dari $current")
            }
            val encoding = connection.contentEncoding ?: "UTF-8"
            val body = connection.inputStream.use { stream ->
                val capped = ByteArray(MAX_BYTES)
                var read = 0
                while (read < MAX_BYTES) {
                    val n = stream.read(capped, read, MAX_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                String(capped, 0, read, charsetFor(encoding))
            }
            connection.disconnect()
            return current to body
        }
        throw IOException("Terlalu banyak redirect")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        )
        return connection
    }

    /** Strips script/style blocks, then tags, then unescapes entities and squeezes whitespace. */
    private fun extractText(html: String): String {
        var text = html
            .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|h[1-6]|li|tr)>"), "\n")
        text = text.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun charsetFor(name: String?): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name ?: "UTF-8") }
            .getOrDefault(Charsets.UTF_8)

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
