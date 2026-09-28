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
 * Web search by scraping: Bing first (the default source), DuckDuckGo as the fallback when
 * Bing is unreachable or returns nothing usable. The parsers live in [WebSearchScrape] so
 * they are unit-testable against captured HTML without network or Robolectric.
 */
class WebSearchTool : Tool {

    override val id: String = "builtin.web_search"
    override val name: String = "web_search"
    override val description: String =
        "Mencari informasi di internet (Bing, fallback DuckDuckGo). Gunakan untuk fakta terkini, berita, harga, atau hal di luar pengetahuan Anda. Kembalikan judul, URL, dan cuplikan."
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
            val results = search(query, maxResults)
            if (results.isEmpty()) {
                ToolResult(success = true, output = "Tidak ada hasil untuk \"$query\".")
            } else {
                val source = results.first().source
                val body = results
                    .take(maxResults)
                    .mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}" }
                    .joinToString("\n\n")
                ToolResult(
                    success = true,
                    output = "Hasil pencarian \"$query\" (sumber: $source):\n\n$body",
                    metadata = mapOf(
                        "count" to results.size.toString(),
                        "query" to query,
                        "source" to source
                    )
                )
            }
        }
    } catch (e: Exception) {
        ToolResult(success = false, output = "", error = "web_search gagal: ${e.message ?: e.javaClass.simpleName}")
    }

    private data class Hit(val title: String, val url: String, val snippet: String, val source: String)

    /**
     * Bing first; DuckDuckGo only when Bing throws or yields nothing usable, so one angry
     * anti-bot page cannot make the tool report a failure to the model.
     */
    private fun search(query: String, maxResults: Int): List<Hit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val bing = try {
            WebSearchScrape.parseBing(
                httpGet("https://www.bing.com/search?q=$encoded&count=$maxResults")
            )
        } catch (_: Exception) {
            emptyList()
        }
        if (bing.isNotEmpty()) {
            return bing.take(maxResults).map { Hit(it.first, it.second, it.third, "bing") }
        }
        val duck = WebSearchScrape.parseDuckDuckGo(
            httpGet("https://html.duckduckgo.com/html/?q=$encoded")
        )
        return duck.take(maxResults).map { Hit(it.first, it.second, it.third, "duckduckgo") }
    }

    /**
     * Plain HTTP GET (network on the caller's IO dispatcher) returning the body as UTF-8,
     * capped so a huge page cannot exhaust memory. Throws IOException on HTTP errors.
     */
    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
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

    private companion object {
        const val MAX_BYTES = 512 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/**
 * Pure HTML parsing for [WebSearchTool]: Bing (the default source) and the DuckDuckGo HTML
 * endpoint (the fallback). Each hit is (title, url, snippet) — no network, no Android, so
 * the regexes are tested against captured pages instead of "works today".
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
