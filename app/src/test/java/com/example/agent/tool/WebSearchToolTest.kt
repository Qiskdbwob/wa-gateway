package com.example.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty rules of `web_search`.
 *
 * The case that produced a wrong answer on a real device was not a crash: the tool returned
 * "Tidak ada hasil" for a *blocked* search, and the model filled the gap by narrating. These tests
 * pin the distinction — every engine blocked is a failure **with recovery instructions**, while a
 * reachable engine with no rows is a normal empty result.
 */
class WebSearchToolTest {

    private fun attempt(engine: String, status: SearchStatus, detail: String = "") =
        SearchAttempt(engine, status, detail)

    @Test
    fun everyEngineBlockedFailsWithInstructionsInsteadOfInventingNews() {
        val outcome = SearchOutcome(
            results = emptyList(),
            attempts = listOf(
                attempt("bing", SearchStatus.BLOCKED, "halaman captcha/consent"),
                attempt("duckduckgo", SearchStatus.ERROR, "timeout"),
                attempt("mojeek", SearchStatus.BLOCKED, "halaman captcha/consent"),
                attempt("wikipedia", SearchStatus.ERROR, "dilewati: batas waktu total")
            )
        )

        val result = renderSearchOutcome("berita ai terbaru", outcome, maxResults = 5)
        val error = result.error.orEmpty()

        assertFalse(result.success)
        assertTrue(error.contains("jangan mengarang"))
        assertTrue(error.contains("web_fetch"))
        // The model needs to know which engines were tried before it explains the failure.
        assertTrue(error.contains("bing=diblokir/anti-bot"))
        assertTrue(error.contains("duckduckgo=gagal"))
    }

    @Test
    fun aReachableEngineWithoutRowsIsANormalEmptyResult() {
        val outcome = SearchOutcome(
            results = emptyList(),
            attempts = listOf(
                attempt("bing", SearchStatus.EMPTY),
                attempt("duckduckgo", SearchStatus.BLOCKED, "halaman captcha/consent")
            )
        )

        val result = renderSearchOutcome("kata-kunci-yang-tidak-ada", outcome, maxResults = 5)

        assertTrue(result.success)
        assertTrue(result.output.contains("Tidak ada hasil"))
        assertTrue(result.output.contains("bing=0 hasil"))
        assertEquals("0", result.metadata["count"])
    }

    @Test
    fun anEngineErrorAloneIsNotAnEmptySearch() {
        val outcome = SearchOutcome(
            results = emptyList(),
            attempts = listOf(attempt("bing", SearchStatus.ERROR, "HTTP 503 dari bing"))
        )

        val result = renderSearchOutcome("apa pun", outcome, maxResults = 5)

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun wikipediaHitsAreLabelledAsAnEncyclopediaNotAsNews() {
        val outcome = SearchOutcome(
            results = listOf(
                WebSearchHit(
                    title = "Kecerdasan buatan",
                    url = "https://id.wikipedia.org/wiki/Kecerdasan_buatan",
                    snippet = "Bidang ilmu komputer.",
                    source = "wikipedia"
                )
            ),
            attempts = listOf(
                attempt("bing", SearchStatus.BLOCKED, "halaman captcha/consent"),
                attempt("wikipedia", SearchStatus.OK, "1 hasil")
            )
        )

        val result = renderSearchOutcome("ai", outcome, maxResults = 5)

        assertTrue(result.success)
        assertTrue(result.output.contains("sumber: wikipedia"))
        assertTrue(result.output.contains("ensiklopedia"))
        assertEquals("wikipedia", result.metadata["source"])
    }

    @Test
    fun theOutputNamesEveryEngineThatWasChecked() {
        val outcome = SearchOutcome(
            results = listOf(WebSearchHit("Judul", "https://example.com", "Cuplikan", "mojeek")),
            attempts = listOf(
                attempt("bing", SearchStatus.BLOCKED, "halaman captcha/consent"),
                attempt("duckduckgo", SearchStatus.ERROR, "timeout"),
                attempt("mojeek", SearchStatus.OK, "3 hasil")
            )
        )

        val output = renderSearchOutcome("q", outcome, maxResults = 5).output

        assertTrue(output.contains("sumber: mojeek"))
        assertTrue(output.contains("bing=diblokir/anti-bot"))
        assertTrue(output.contains("duckduckgo=gagal (timeout)"))
        assertTrue(output.contains("mojeek=ok (3 hasil)"))
    }

    @Test
    fun resultsAreTrimmedToTheRequestedMaximum() {
        val outcome = SearchOutcome(
            results = (1..8).map { WebSearchHit("Judul $it", "https://example.com/$it", "Cuplikan", "bing") },
            attempts = listOf(attempt("bing", SearchStatus.OK, "8 hasil"))
        )

        val result = renderSearchOutcome("q", outcome, maxResults = 3)

        assertTrue(result.success)
        assertTrue(result.output.contains("1. Judul 1"))
        assertTrue(result.output.contains("3. Judul 3"))
        assertFalse(result.output.contains("4. Judul 4"))
    }
}
