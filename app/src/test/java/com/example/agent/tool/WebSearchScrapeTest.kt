package com.example.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrape parsers are the whole reason web_search can switch sources without a redesign: every
 * engine is an HTML regex that breaks silently when the markup changes, so the fixtures here pin the
 * expected shape (Bing default, DuckDuckGo, Mojeek, Wikipedia last) — and every parser is required
 * to return nothing on foreign markup rather than garbage.
 *
 * The newer fixtures mirror each endpoint's documented/observed result shape. They are samples, not
 * live captures: a real capture is only possible on a device with internet, which is exactly why the
 * parsers must fail soft (→ "0 hasil" → next engine) instead of throwing.
 */
class WebSearchScrapeTest {

    @Test
    fun parseBingExtractsTitleUrlAndSnippetFromBAlgoBlocks() {
        val html = """
            <html><body>
            <li class="b_algo"><h2><a href="https://example.com/one&amp;x=1">Contoh <b>satu</b></a></h2>
              <div class="b_caption"><p>Cuplikan halaman pertama.</p></div></li>
            <li class="b_algo"><h2><a href="https://example.org/two">Contoh dua</a></h2>
              <p>Cuplikan kedua</p></li>
            </body></html>
        """.trimIndent()

        val hits = WebSearchScrape.parseBing(html)
        assertEquals(2, hits.size)
        assertEquals("Contoh satu", hits[0].first)
        assertEquals("https://example.com/one&x=1", hits[0].second)
        assertEquals("Cuplikan halaman pertama.", hits[0].third)
        assertEquals("Contoh dua", hits[1].first)
        assertEquals("https://example.org/two", hits[1].second)
        assertEquals("Cuplikan kedua", hits[1].third)
    }

    @Test
    fun parseBingReturnsNothingOnForeignMarkupInsteadOfGarbage() {
        val consent = "<html><body>Sebelum lanjut, pilih preferensi cookie Anda</body></html>"
        assertTrue(WebSearchScrape.parseBing(consent).isEmpty())
        assertTrue(WebSearchScrape.parseBing("").isEmpty())
    }

    @Test
    fun parseDuckDuckGoDecodesUddgRedirectsAndSkipsInternalLinks() {
        val html = """
            <div class="result">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Faa">Hasil A</a>
              <a class="result__snippet" href="#">Cuplikan A</a>
              <a rel="nofollow" class="result__a" href="https://duckduckgo.com/internal">Internal</a>
            </div>
        """.trimIndent()

        val hits = WebSearchScrape.parseDuckDuckGo(html)
        assertEquals(1, hits.size)
        assertEquals("Hasil A", hits[0].first)
        assertEquals("https://example.com/aa", hits[0].second)
        assertEquals("Cuplikan A", hits[0].third)
    }

    @Test
    fun parseMojeekReadsItsResultsListAndIgnoresTheRestOfThePage() {
        val html = """
            <html><body>
            <nav><a href="https://www.mojeek.com/about">About</a></nav>
            <ul class="results-standard">
              <li><a class="ob" href="https://example.com/satu">Contoh <b>satu</b></a>
                  <p class="s">Cuplikan pertama.</p></li>
              <li><a class="ob" href="https://example.org/dua">Contoh dua</a>
                  <p class="s">Cuplikan kedua.</p></li>
            </ul>
            </body></html>
        """.trimIndent()

        val hits = WebSearchScrape.parseMojeek(html)

        // The navigation link sits before the results list, so it must not become a hit.
        assertEquals(2, hits.size)
        assertEquals("Contoh satu", hits[0].first)
        assertEquals("https://example.com/satu", hits[0].second)
        assertEquals("Cuplikan pertama.", hits[0].third)
        assertEquals("Contoh dua", hits[1].first)
    }

    @Test
    fun parseWikipediaReadsSearchResultRows() {
        val html = """
            <ul class="mw-search-results">
              <li class="mw-search-result">
                <div class="mw-search-result-heading"><a href="/wiki/Kecerdasan_buatan" title="Kecerdasan buatan">Kecerdasan buatan</a></div>
                <div class="searchresult">Kecerdasan buatan adalah bidang <b>ilmu komputer</b>.</div>
              </li>
              <li class="mw-search-result">
                <div class="mw-search-result-heading"><a href="/wiki/AI" title="AI">AI</a></div>
                <div class="searchresult">Singkatan yang dialihkan ke halaman ini.</div>
              </li>
            </ul>
        """.trimIndent()

        val hits = WebSearchScrape.parseWikipedia(html)

        assertEquals(2, hits.size)
        assertEquals("Kecerdasan buatan", hits[0].first)
        assertEquals("https://id.wikipedia.org/wiki/Kecerdasan_buatan", hits[0].second)
        assertEquals("Kecerdasan buatan adalah bidang ilmu komputer.", hits[0].third)
    }

    @Test
    fun theNewerParsersReturnNothingOnForeignMarkupInsteadOfGarbage() {
        assertTrue(WebSearchScrape.parseMojeek("<html><body>tidak ada daftar hasil</body></html>").isEmpty())
        assertTrue(WebSearchScrape.parseMojeek("").isEmpty())
        assertTrue(WebSearchScrape.parseWikipedia("").isEmpty())
        assertTrue(WebSearchScrape.parseWikipedia("<html><body><p>bukan hasil pencarian</p></body></html>").isEmpty())
    }

    @Test
    fun looksBlockedRecognisesCaptchaAndConsentWallsButNotResultPages() {
        assertTrue(
            WebSearchScrape.looksBlocked(
                "<html><body>Your request looks like unusual traffic. Please verify you are human.</body></html>"
            )
        )
        assertTrue(WebSearchScrape.looksBlocked("<html><head><title>consent.bing.com</title></head></html>"))
        assertTrue(WebSearchScrape.looksBlocked("<html><body>Enable JavaScript and cookies to continue</body></html>"))
        assertTrue(WebSearchScrape.looksBlocked("<html><body>Sorry, too many requests from your network</body></html>"))

        // A normal result page must never be mistaken for a wall, otherwise a working engine would
        // be skipped and reported as "diblokir".
        assertFalse(
            WebSearchScrape.looksBlocked(
                "<html><body><li class=\"b_algo\"><h2><a href=\"https://example.com\">Hasil</a></h2></li></body></html>"
            )
        )
        assertFalse(WebSearchScrape.looksBlocked(""))
    }

    @Test
    fun stripHtmlUnescapesCommonEntitiesAndSqueezesWhitespace() {
        assertEquals(
            "A & B <tag> \"q\" dua kata",
            WebSearchScrape.stripHtml("A &amp; B <b>&lt;tag&gt;</b> &quot;q&quot;  dua\tkata")
        )
    }
}
