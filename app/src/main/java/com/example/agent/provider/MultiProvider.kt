package com.example.agent.provider

/**
 * One provider row as the rest of the agent sees it: keys already decrypted, ordering resolved.
 *
 * The model layer used to hold exactly one provider (one base URL, one model, one key pool).
 * A user may now keep several — different gateways, different quotas — so each row is described
 * here, and the policy (which one is active, in what order to fail over) lives in
 * [ProviderDirectory]: away from Room and away from Android, so it is unit-testable on the JVM.
 */
data class ProviderDescriptor(
  val id: String,
  val label: String,
  val baseUrl: String,
  val modelId: String,
  val keys: List<String> = emptyList(),
  val enabled: Boolean = true,
  val sortOrder: Int = 0,
) {
  /** A provider can only answer if it is enabled, has a base URL, and has at least one key. */
  val usable: Boolean
    get() = enabled && baseUrl.isNotBlank() && keys.isNotEmpty()

  /** Never renders blank, even when the user cleared the label field. */
  val displayLabel: String
    get() = label.trim().ifBlank { baseUrl.trim().ifBlank { id } }
}

object ProviderDirectory {

  /** Upper bound on keys per provider; the UI caps its input at the same number. */
  const val MAX_KEYS_PER_PROVIDER = 20

  /** Id given to the row seeded from a pre-multi-provider install. */
  const val LEGACY_PROVIDER_ID = "provider-legacy"

  /**
   * The provider that should serve requests. The explicitly selected one wins as long as it
   * still exists and can answer; otherwise the first usable provider in display order — so
   * draining or disabling the active provider degrades instead of failing every turn.
   */
  fun resolveActive(providers: List<ProviderDescriptor>, activeId: String?): ProviderDescriptor? {
    val ordered = providers.sortedBy { it.sortOrder }
    val selected = ordered.firstOrNull { it.id == activeId }
    if (selected != null && selected.usable) return selected
    return ordered.firstOrNull { it.usable }
  }

  /**
   * Failover order for the model router: the active provider first, then every other usable
   * provider in display order. Providers without keys are skipped, because the router would
   * only spend a retry attempt on a request that cannot succeed.
   */
  fun failoverOrder(providers: List<ProviderDescriptor>, activeId: String?): List<ProviderDescriptor> {
    val active = resolveActive(providers, activeId)
    val rest = providers.sortedBy { it.sortOrder }.filter { it.usable && it.id != active?.id }
    return listOfNotNull(active) + rest
  }

  /** Deterministic id for a user-added provider, so renames never create a second row. */
  fun newProviderId(existing: Collection<String>): String {
    var index = existing.size + 1
    while (existing.contains("provider-$index")) index++
    return "provider-$index"
  }

  /** Next free slot at the end of the display order. */
  fun nextSortOrder(providers: List<ProviderDescriptor>): Int =
    (providers.maxOfOrNull { it.sortOrder } ?: -1) + 1

  /**
   * Moves a provider one slot up or down for failover priority, returning the new order. Pure so
   * the reorder rule has a test instead of existing only inside a click handler.
   */
  fun move(providers: List<ProviderDescriptor>, id: String, delta: Int): List<ProviderDescriptor> {
    val ordered = providers.sortedBy { it.sortOrder }.toMutableList()
    val from = ordered.indexOfFirst { it.id == id }
    if (from < 0) return providers
    val to = (from + delta).coerceIn(0, ordered.size - 1)
    if (to == from) return providers
    ordered.add(to, ordered.removeAt(from))
    return ordered
  }

  /** Drops keys past [MAX_KEYS_PER_PROVIDER] so one paste cannot create an unmanageable blob. */
  fun sanitizeKeys(keys: List<String>): List<String> =
    ProviderKeyPool.normalize(keys).take(MAX_KEYS_PER_PROVIDER)

  /** Seeds the first row from the single-provider configuration of an older install. */
  fun seedFromLegacy(baseUrl: String, modelId: String, keys: List<String>): ProviderDescriptor {
    val defaults = ProviderConfig()
    return ProviderDescriptor(
      id = LEGACY_PROVIDER_ID,
      label = "Provider utama",
      baseUrl = baseUrl.trim().ifBlank { defaults.baseUrl },
      modelId = modelId.trim().ifBlank { defaults.modelId },
      keys = sanitizeKeys(keys),
      enabled = true,
      sortOrder = 0,
    )
  }
}
