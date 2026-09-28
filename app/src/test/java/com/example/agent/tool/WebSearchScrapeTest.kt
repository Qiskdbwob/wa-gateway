package com.example.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrape parsers are the whole reason web_search can switch sources without a redesign:
 * both engines are HTML regexes that break silently when the markup changes, so the fixture
 * snippets here pin the expected shape (Bing default, DuckDuckGo fallback).
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
    fun stripHtmlUnescapesCommonEntitiesAndSqueezesWhitespace() {
        assertEquals(
            "A & B <tag> \"q\" dua kata",
            WebSearchScrape.stripHtml("A &amp; B <b>&lt;tag&gt;</b> &quot;q&quot;  dua\tkata")
        )
    }
}
