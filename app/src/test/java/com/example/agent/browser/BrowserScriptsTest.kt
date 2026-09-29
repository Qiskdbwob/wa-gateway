package com.example.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView engine itself can only run on a device, but every decision it makes — which script
 * to run, how to read the JSON-encoded result, and when a target is safe to act on — lives in
 * [BrowserScripts]. That is what these tests pin down.
 *
 * The result-decoding tests matter most: Android serialises the value of `evaluateJavascript` as
 * JSON, so a script returning the JavaScript string `'ok'` reaches the callback as `"ok"`. Code
 * that compared the raw value against `ok` reported *every* failed lookup as a success.
 */
class BrowserScriptsTest {

    // --- reading the result ------------------------------------------------------------------

    @Test
    fun androidQuotesStringResultsSoTheTokenIsUnquotedFirst() {
        assertEquals("ok", BrowserScripts.stripQuotes("\"ok\""))
        assertEquals("not-found", BrowserScripts.stripQuotes("\"not-found\""))
        assertEquals("occluded DIV overlay", BrowserScripts.stripQuotes("\"occluded DIV overlay\""))
    }

    @Test
    fun anUnquotedResultIsReadAsIs() {
        assertEquals("ok", BrowserScripts.stripQuotes("ok"))
        assertEquals("not-a-select", BrowserScripts.stripQuotes("not-a-select"))
    }

    @Test
    fun aMissingResultIsNull() {
        assertNull(BrowserScripts.stripQuotes(null))
        assertNull(BrowserScripts.stripQuotes("null"))
        assertNull(BrowserScripts.stripQuotes("null\n"))
    }

    @Test
    fun escapedCharactersSurviveTheRoundTrip() {
        val original = "kata \"kutip\" dan \\ garis\nbaris baru\ttab"
        val literal = BrowserScripts.jsStringLiteral(original)
        assertEquals("\"kata \\\"kutip\\\" dan \\\\ garis\\nbaris baru\\ttab\"", literal)
        assertEquals(original, BrowserScripts.stripQuotes(literal))
        // Undecoded escapes must never leak back into a token comparison.
        assertFalse(BrowserScripts.stripQuotes(literal) == literal)
    }

    @Test
    fun controlCharactersAreEscapedInsteadOfBreakingTheScript() {
        assertEquals("\"a\\u0001b\"", BrowserScripts.jsStringLiteral("a\u0001b"))
        assertEquals("\"a\\u2028b\"", BrowserScripts.jsStringLiteral("a\u2028b"))
    }

    @Test
    fun aHostileValueCannotBreakOutOfItsStringLiteral() {
        assertEquals("\"\\\"; alert('x'); //\"", BrowserScripts.jsStringLiteral("\"; alert('x'); //"))
    }

    @Test
    fun anUnreadableFingerprintNeverClaimsThePageDidNotChange() {
        assertTrue(BrowserScripts.changedBetween("", ""))
        assertTrue(BrowserScripts.changedBetween("a|b", ""))
        assertFalse(BrowserScripts.changedBetween("a|b", "a|b"))
        assertTrue(BrowserScripts.changedBetween("a|b", "a|c"))
    }

    // --- refs and injection ------------------------------------------------------------------

    @Test
    fun refsAreRestrictedToWhatTheSnapshotGenerates() {
        assertEquals("agx-3", BrowserScripts.safeRef(" AGX-3 "))
        assertNull(BrowserScripts.safeRef(""))
        assertNull(BrowserScripts.safeRef("a".repeat(30)))
        assertNull(BrowserScripts.safeRef("agx-3']); alert(1);//"))
        assertNull(BrowserScripts.safeRef("agx 3"))
    }

    @Test
    fun aHostileRefNeverReachesThePage() {
        val script = BrowserScripts.click("x\"]);document.write('pwned');//")
        assertTrue(script.contains("invalid-ref"))
        assertFalse(script.contains("pwned"))

        val select = BrowserScripts.select("'; drop table users; --", "\"x\"")
        assertTrue(select.contains("invalid-ref"))
        assertFalse(select.contains("drop table"))
    }

    // --- the scripts themselves --------------------------------------------------------------

    @Test
    fun theClickScriptValidatesTheTargetBeforeActing() {
        val script = BrowserScripts.click("agx-2")
        assertTrue(script.contains("[data-agx-ref=\"agx-2\"]"))
        // Present, sized, enabled…
        assertTrue(script.contains("'not-found'"))
        assertTrue(script.contains("'invisible'"))
        assertTrue(script.contains("'disabled'"))
        // …and actually the topmost element at its own centre (no click through an overlay).
        assertTrue(script.contains("elementFromPoint"))
        assertTrue(script.contains("'occluded '"))
        assertFalse(script.contains("data-agx-ref=\"undefined\""))
    }

    @Test
    fun typingUsesThePrototypeValueSetterSoFrameworksNotice() {
        val script = BrowserScripts.type("agx-1", BrowserScripts.jsStringLiteral("halo"), submit = false)
        // React/Vue ignore `el.value = …`; the native setter is what their change tracker sees.
        assertTrue(script.contains("Object.getOwnPropertyDescriptor"))
        assertTrue(script.contains("HTMLInputElement.prototype"))
        assertTrue(script.contains("HTMLTextAreaElement.prototype"))
        assertTrue(script.contains("new Event('input'"))
        assertTrue(script.contains("new Event('change'"))
        // A <select> and a contenteditable need their own path.
        assertTrue(script.contains("el.options"))
        assertTrue(script.contains("isContentEditable"))
    }

    @Test
    fun submitIsEncodedAsABooleanLiteralNotAsText() {
        assertTrue(BrowserScripts.type("agx-1", "\"x\"", submit = true).contains("if (true) {"))
        assertTrue(BrowserScripts.type("agx-1", "\"x\"", submit = false).contains("if (false) {"))
        assertTrue(BrowserScripts.type("agx-1", "\"x\"", submit = true).contains("requestSubmit"))
    }

    @Test
    fun theSelectScriptMatchesByValueThenLabel() {
        val script = BrowserScripts.select("agx-4", BrowserScripts.jsStringLiteral("Indonesia"))
        assertTrue(script.contains("'not-a-select'"))
        assertTrue(script.contains("'option-not-found'"))
        assertTrue(script.contains("String(opts[i].value) === want"))
        assertTrue(script.contains("'Indonesia'") || script.contains("\"Indonesia\""))
    }

    @Test
    fun theSnapshotSelectorCoversNativeControlsAriaAndCustomWidgets() {
        val script = BrowserScripts.SNAPSHOT
        assertTrue(script.contains("[role=combobox]"))
        assertTrue(script.contains("[role=option]"))
        assertTrue(script.contains("[role=menuitem]"))
        assertTrue(script.contains("[contenteditable=\"\"]"))
        assertTrue(script.contains("[tabindex]:not([tabindex=\"-1\"])"))
        assertTrue(script.contains("data-agx-ref"))
        assertTrue(script.contains("document.body.innerText"))
        // Labels fall back to the attributes sites actually use, not just innerText.
        assertTrue(script.contains("getAttribute('data-testid')"))
        assertTrue(script.contains("getAttribute('placeholder')"))
    }

    @Test
    fun theFingerprintSeesFormValuesSoTypingCountsAsAChange() {
        val script = BrowserScripts.FINGERPRINT
        assertTrue(script.contains("input,textarea,select"))
        assertTrue(script.contains("charCodeAt"))
        assertTrue(script.contains("scrollY"))
        assertTrue(script.contains("[role=dialog]"))
    }

    @Test
    fun theSettleObserverIsArmedAndSampled() {
        assertTrue(BrowserScripts.ARM_SETTLE.contains("MutationObserver"))
        assertTrue(BrowserScripts.ARM_SETTLE.contains("window.__agx.n = 0"))
        assertTrue(BrowserScripts.SETTLE_SAMPLE.contains("readyState"))
        assertTrue(BrowserScripts.SETTLE_SAMPLE.contains("window.__agx"))
    }

    // --- keys --------------------------------------------------------------------------------

    @Test
    fun keyAliasesResolveToCanonicalNames() {
        assertEquals("Enter", BrowserScripts.normalizeKey("enter"))
        assertEquals("Escape", BrowserScripts.normalizeKey(" ESC "))
        assertEquals("ArrowDown", BrowserScripts.normalizeKey("bawah"))
        assertEquals("Tab", BrowserScripts.normalizeKey("tab"))
        assertNull(BrowserScripts.normalizeKey("F5"))
        assertNull(BrowserScripts.normalizeKey(""))
        assertTrue(
            BrowserScripts.SUPPORTED_KEYS.containsAll(
                listOf("Enter", "Escape", "Tab", "Backspace", "Space", "ArrowUp", "ArrowDown")
            )
        )
    }

    @Test
    fun theKeyScriptRejectsKeysItCannotSend() {
        val script = BrowserScripts.pressKey(BrowserScripts.jsStringLiteral("Enter"))
        assertTrue(script.contains("'unsupported-key'"))
        assertTrue(script.contains("KeyboardEvent('keydown'"))
        assertTrue(script.contains("KeyboardEvent('keyup'"))
        assertTrue(script.contains("document.activeElement"))
    }

    // --- failure messages --------------------------------------------------------------------

    @Test
    fun failureTokensBecomeActionableMessages() {
        val notFound = BrowserScripts.describeToken("not-found", "agx-1")
        assertTrue(notFound.contains("agx-1"))
        assertTrue(notFound.contains("browser_read"))

        val occluded = BrowserScripts.describeToken("occluded div overlay", "agx-9")
        assertTrue(occluded.contains("agx-9"))
        assertTrue(occluded.contains("overlay"))
        assertTrue(occluded.contains("browser_press_key"))

        assertTrue(BrowserScripts.describeToken("not-a-select", "agx-4").contains("<select>"))
        assertTrue(BrowserScripts.describeToken("option-not-found", "agx-4").contains("agx-4"))
        assertTrue(BrowserScripts.describeToken("disabled", "agx-4").contains("nonaktif"))
        assertTrue(BrowserScripts.describeToken("invalid-ref", "woops").contains("browser_read"))
        // An unknown token is still shown instead of being swallowed.
        assertTrue(BrowserScripts.describeToken("kode-aneh", "agx-1").contains("kode-aneh"))
    }

    // --- stale-ref fallback ------------------------------------------------------------------

    private fun element(
        ref: String,
        tag: String = "button",
        type: String = "",
        label: String,
        value: String = ""
    ) = BrowserElement(ref, tag, type, label, value)

    @Test
    fun aStaleRefIsRefoundByItsLabel() {
        val remembered = element("agx-3", label = "Masuk")
        val refreshed = listOf(
            element("agx-0", label = "Cari"),
            element("agx-5", label = "Masuk")
        )
        assertEquals("agx-5", BrowserScripts.matchDescriptor(remembered, refreshed))
    }

    @Test
    fun aStaleRefFallsBackToItsValueWhenTheLabelIsEmpty() {
        val remembered = element("agx-2", tag = "input", type = "text", label = "", value = "budi@contoh.id")
        val refreshed = listOf(
            element("agx-7", tag = "input", type = "text", label = "", value = "budi@contoh.id")
        )
        assertEquals("agx-7", BrowserScripts.matchDescriptor(remembered, refreshed))
    }

    @Test
    fun aRefIsNeverReResolvedPositivelyOrByPosition() {
        val remembered = element("agx-3", label = "Masuk")
        // Same position, different control: matching that would click the wrong thing.
        val refreshed = listOf(element("agx-3", label = "Keluar"))
        assertNull(BrowserScripts.matchDescriptor(remembered, refreshed))
        assertNull(BrowserScripts.matchDescriptor(null, refreshed))
        assertNull(BrowserScripts.matchDescriptor(remembered, emptyList()))
    }
}
