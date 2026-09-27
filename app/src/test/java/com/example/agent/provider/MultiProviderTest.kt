package com.example.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-provider is policy, not plumbing: which provider answers, which ones are lined up behind
 * it, and in what order. All of that is pure data here, so it is tested without Room, without
 * Android and without a network.
 */
class MultiProviderTest {

  private fun provider(
    id: String,
    keys: List<String> = listOf("sk-$id"),
    enabled: Boolean = true,
    order: Int = 0,
    baseUrl: String = "https://api.example.com/v1",
    modelId: String = "model-$id",
  ) = ProviderDescriptor(
    id = id,
    label = "Provider $id",
    baseUrl = baseUrl,
    modelId = modelId,
    keys = keys,
    enabled = enabled,
    sortOrder = order,
  )

  @Test
  fun `the selected provider wins while it can still answer`() {
    val list = listOf(provider("a", order = 0), provider("b", order = 1))
    assertEquals("b", ProviderDirectory.resolveActive(list, "b")?.id)
  }

  @Test
  fun `a provider without keys is not considered able to answer`() {
    val list = listOf(provider("a", keys = emptyList(), order = 0), provider("b", order = 1))
    assertEquals("b", ProviderDirectory.resolveActive(list, "a")?.id)
    assertFalse(provider("a", keys = emptyList()).usable)
  }

  @Test
  fun `deleting the active provider degrades to the first usable one instead of failing every turn`() {
    val list = listOf(provider("a", order = 0), provider("b", order = 1))
    assertEquals("a", ProviderDirectory.resolveActive(list, "gone")?.id)
  }

  @Test
  fun `a disabled provider is skipped even when keys are present`() {
    val list = listOf(provider("a", enabled = false, order = 0), provider("b", order = 1))
    assertEquals("b", ProviderDirectory.resolveActive(list, "a")?.id)
    assertFalse(provider("a", enabled = false).usable)
  }

  @Test
  fun `no provider at all resolves to null rather than a blank one`() {
    assertNull(ProviderDirectory.resolveActive(emptyList(), "anything"))
    assertNull(ProviderDirectory.resolveActive(listOf(provider("a", keys = emptyList())), "a"))
  }

  @Test
  fun `failover order is the active provider first, then the rest in display order`() {
    val list = listOf(
      provider("a", order = 0),
      provider("b", order = 1),
      provider("c", order = 2),
    )
    val order = ProviderDirectory.failoverOrder(list, "c").map { it.id }
    assertEquals(listOf("c", "a", "b"), order)
  }

  @Test
  fun `failover order skips providers the router could only waste an attempt on`() {
    val list = listOf(
      provider("a", keys = emptyList(), order = 0),
      provider("b", order = 1),
      provider("c", enabled = false, order = 2),
      provider("d", order = 3),
    )
    assertEquals(listOf("b", "d"), ProviderDirectory.failoverOrder(list, "a").map { it.id })
  }

  @Test
  fun `failover order is empty when nothing can answer, leaving the echo fallback in charge`() {
    assertTrue(ProviderDirectory.failoverOrder(listOf(provider("a", keys = emptyList())), "a").isEmpty())
  }

  @Test
  fun `sanitizing keys drops blanks and duplicates and caps the count`() {
    val many = (1..40).map { "sk-$it" }
    val sanitized = ProviderDirectory.sanitizeKeys(listOf(" ", "", "sk-1", "sk-1", "sk-2") + many)
    assertEquals(ProviderDirectory.MAX_KEYS_PER_PROVIDER, sanitized.size)
    assertEquals(sanitized.distinct(), sanitized)
    assertEquals("sk-1", sanitized.first())
  }

  @Test
  fun `a new provider id never collides with an existing one`() {
    var existing = emptyList<String>()
    existing = existing + ProviderDirectory.newProviderId(existing)
    existing = existing + ProviderDirectory.newProviderId(existing)
    assertEquals(listOf("provider-1", "provider-2"), existing)
    // Even with a gap in the numbering, ids stay unique.
    assertEquals("provider-3", ProviderDirectory.newProviderId(listOf("provider-1", "provider-3")))
  }

  @Test
  fun `the next slot follows the highest sort order`() {
    assertEquals(0, ProviderDirectory.nextSortOrder(emptyList()))
    assertEquals(3, ProviderDirectory.nextSortOrder(listOf(provider("a", order = 0), provider("b", order = 2))))
  }

  @Test
  fun `moving a provider swaps it with its neighbour and clamps at the ends`() {
    val list = listOf(provider("a", order = 0), provider("b", order = 1), provider("c", order = 2))
    assertEquals(listOf("b", "a", "c"), ProviderDirectory.move(list, "a", 1).map { it.id })
    assertEquals(listOf("a", "c", "b"), ProviderDirectory.move(list, "c", -1).map { it.id })
    // First item cannot move up, last cannot move down, and an unknown id changes nothing.
    assertEquals(listOf("a", "b", "c"), ProviderDirectory.move(list, "a", -1).map { it.id })
    assertEquals(listOf("a", "b", "c"), ProviderDirectory.move(list, "c", 1).map { it.id })
    assertEquals(list, ProviderDirectory.move(list, "missing", 1))
  }

  @Test
  fun `the legacy single provider is carried over with its url, model and whole key pool`() {
    val seeded = ProviderDirectory.seedFromLegacy(
      baseUrl = "https://gateway.local/v1",
      modelId = "my-model",
      keys = listOf("sk-primary", "sk-pool-1", "sk-primary"),
    )
    assertEquals(ProviderDirectory.LEGACY_PROVIDER_ID, seeded.id)
    assertEquals("https://gateway.local/v1", seeded.baseUrl)
    assertEquals("my-model", seeded.modelId)
    assertEquals(listOf("sk-primary", "sk-pool-1"), seeded.keys)
    assertTrue(seeded.usable)
  }

  @Test
  fun `a blank legacy config falls back to the documented defaults`() {
    val seeded = ProviderDirectory.seedFromLegacy(baseUrl = "  ", modelId = "", keys = listOf("sk-1"))
    assertEquals(ProviderConfig().baseUrl, seeded.baseUrl)
    assertEquals(ProviderConfig().modelId, seeded.modelId)
  }

  @Test
  fun `a provider with no label still renders a name`() {
    val unnamed = provider("a").copy(label = "   ")
    assertEquals("Provider a", unnamed.displayLabel)
    // Without a label either, the base URL still names the row better than its raw id does.
    assertEquals("https://x/v1", provider("a").copy(label = "", baseUrl = "https://x/v1").displayLabel)
  }
}
