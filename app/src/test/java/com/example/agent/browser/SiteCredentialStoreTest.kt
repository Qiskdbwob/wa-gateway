package com.example.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential blob is persisted in Room and encrypted by SecretCipher; these tests cover the
 * part that can silently corrupt data — the encode/decode round trip, including passwords that
 * contain the separators or newlines.
 */
class SiteCredentialStoreTest {

    @Test
    fun roundTripKeepsEveryField() {
        val original = listOf(
            SiteCredential("instagram", "https://www.instagram.com/accounts/login/", "user@example.com", "s3cret!", "catatan"),
            SiteCredential("x", "https://x.com/i/flow/login", "another", "p@ss")
        )
        val decoded = SiteCredentialStore.decode(SiteCredentialStore.encode(original))
        assertEquals(2, decoded.size)
        assertEquals(original[0], decoded[0])
        assertEquals("", decoded[1].notes)
        assertEquals("p@ss", decoded[1].password)
    }

    @Test
    fun separatorsAndNewlinesInValuesCannotSplitRecords() {
        val messy = listOf(
            SiteCredential("site", "url", "user\u0001name", "pass\u0002word\nline2")
        )
        val decoded = SiteCredentialStore.decode(SiteCredentialStore.encode(messy))
        assertEquals(1, decoded.size)
        assertEquals("site", decoded[0].site)
        assertEquals("pass word line2", decoded[0].password)
    }

    @Test
    fun blankInputDecodesToEmptyList() {
        assertTrue(SiteCredentialStore.decode("").isEmpty())
        assertTrue(SiteCredentialStore.decode("   ").isEmpty())
    }

    @Test
    fun brokenRecordsAreSkippedInsteadOfCrashing() {
        // A record without the required four fields is ignored; a valid one still decodes.
        val raw = "not-a-record" + "\u0002" + "site\u0001url\u0001user\u0001pass"
        val decoded = SiteCredentialStore.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("site", decoded[0].site)
    }

    @Test
    fun findMatchesBySiteNameCaseInsensitively() {
        val list = listOf(
            SiteCredential("instagram", "", "u", "p"),
            SiteCredential("facebook", "", "u2", "p2")
        )
        assertEquals("u", SiteCredentialStore.find(list, "Instagram")?.username)
        assertEquals("u2", SiteCredentialStore.find(list, "facebook.com")?.username)
        assertEquals("u", SiteCredentialStore.find(list, "https://www.instagram.com")?.username)
        assertNull(SiteCredentialStore.find(list, "tiktok"))
        assertNull(SiteCredentialStore.find(list, ""))
    }
}
