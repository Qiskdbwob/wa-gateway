package com.example.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keys pool ("multi-key") policy.
 *
 * These tests pin the two decisions that keep the feature from making things worse: which
 * failures may burn another key, and in which order keys are tried. A pool must never turn a
 * server error or a bad request into a spray of extra calls, and it must never silently drop a
 * key the user pasted (or count the same key twice).
 */
class ProviderKeyPoolTest {

    // ==========================================
    // Normalisation / parsing
    // ==========================================

    @Test
    fun normalizeTrimsDropsBlanksAndDuplicates() {
        val keys = ProviderKeyPool.normalize(listOf("  sk-a  ", "", "sk-a", "   ", "sk-b"))

        assertEquals(listOf("sk-a", "sk-b"), keys)
    }

    @Test
    fun parseAcceptsOneKeyPerLineAndCommonSeparators() {
        val raw = "sk-a\nsk-b\n\n  sk-c  ,\nsk-d;sk-e"

        assertEquals(listOf("sk-a", "sk-b", "sk-c", "sk-d", "sk-e"), ProviderKeyPool.parse(raw))
    }

    @Test
    fun parseOfEmptyBlobYieldsNoKeys() {
        assertTrue(ProviderKeyPool.parse("").isEmpty())
        assertTrue(ProviderKeyPool.parse("   \n  , ; ").isEmpty())
    }

    @Test
    fun formatRoundTripsThroughParse() {
        val keys = listOf("sk-a", "sk-b", "sk-c")

        assertEquals("sk-a\nsk-b\nsk-c", ProviderKeyPool.format(keys))
        assertEquals(keys, ProviderKeyPool.parse(ProviderKeyPool.format(keys)))
        // The Settings field should never show duplicates either.
        assertEquals("sk-a\nsk-b", ProviderKeyPool.format(listOf("sk-a", "sk-b", "sk-a")))
    }

    // ==========================================
    // Rotation policy
    // ==========================================

    @Test
    fun configKeyPoolPutsPrimaryKeyFirstAndSkipsBlanks() {
        val config = ProviderConfig(
            apiKey = "sk-primary",
            apiKeys = listOf("", "sk-backup", "sk-primary")
        )

        assertEquals(listOf("sk-primary", "sk-backup"), config.keyPool())
    }

    @Test
    fun rotateOnlyOnKeyScopedFailures() {
        // Key-scoped: a rejected key, exhausted billing, rate limit/quota.
        assertTrue(ProviderKeyPool.shouldRotateOn(401))
        assertTrue(ProviderKeyPool.shouldRotateOn(402))
        assertTrue(ProviderKeyPool.shouldRotateOn(403))
        assertTrue(ProviderKeyPool.shouldRotateOn(429))

        // Not key-scoped: the key is fine, so the retry/fallback policy (not the pool) decides.
        assertFalse(ProviderKeyPool.shouldRotateOn(400))
        assertFalse(ProviderKeyPool.shouldRotateOn(404))
        assertFalse(ProviderKeyPool.shouldRotateOn(500))
        assertFalse(ProviderKeyPool.shouldRotateOn(502))
        assertFalse(ProviderKeyPool.shouldRotateOn(503))
    }

    @Test
    fun nextKeyAfterFailureWalksThePoolOnceThenGivesUp() {
        // Second key available after the first failed: keep going.
        assertTrue(ProviderKeyPool.nextKeyAfterFailure(429, attempted = 1, poolSize = 3))
        // Last key failed: stop, so the Agent Loop can retry/fallback as usual.
        assertFalse(ProviderKeyPool.nextKeyAfterFailure(429, attempted = 3, poolSize = 3))
        // Single key behaves exactly like before the pool existed.
        assertFalse(ProviderKeyPool.nextKeyAfterFailure(429, attempted = 1, poolSize = 1))
        // Non key-scoped failure never walks the pool.
        assertFalse(ProviderKeyPool.nextKeyAfterFailure(503, attempted = 1, poolSize = 5))
    }

    @Test
    fun rotatedSpreadsLoadWithoutLosingKeys() {
        val keys = listOf("k1", "k2", "k3")

        assertEquals(listOf("k1", "k2", "k3"), ProviderKeyPool.rotated(keys, 0))
        assertEquals(listOf("k2", "k3", "k1"), ProviderKeyPool.rotated(keys, 1))
        assertEquals(listOf("k3", "k1", "k2"), ProviderKeyPool.rotated(keys, 2))
        // Wraps around, so the counter can grow forever without bounds checks.
        assertEquals(listOf("k2", "k3", "k1"), ProviderKeyPool.rotated(keys, 4))
        assertEquals(keys.toSet(), ProviderKeyPool.rotated(keys, 7).toSet())
        // A negative index cannot happen (AtomicInteger), but must not crash if it does.
        assertEquals(listOf("k3", "k1", "k2"), ProviderKeyPool.rotated(keys, -1))
    }

    @Test
    fun rotatedLeavesSingleKeyPoolsAlone() {
        assertEquals(listOf("only"), ProviderKeyPool.rotated(listOf("only"), 5))
        assertTrue(ProviderKeyPool.rotated(emptyList(), 3).isEmpty())
    }
}
