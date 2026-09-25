package com.example.agent.storage

import com.example.agent.storage.dao.ContactRuleDao
import com.example.agent.storage.entity.ContactRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Priority 1 — WhatsApp contact access control.
 *
 * Decision matrix (whitelistMode comes from AgentConfigEntity):
 *
 *   rule                  | whitelist OFF      | whitelist ON
 *   ----------------------+--------------------+------------------
 *   none                  | ALLOW              | BLOCK
 *   BLOCKED               | BLOCK              | BLOCK
 *   ALLOWED               | ALLOW              | ALLOW
 *   PENDING               | BLOCK (until user  | BLOCK (until user
 *                         |  approves)         |  approves)
 */
class ContactAccessRepository(private val dao: ContactRuleDao) {

    enum class Decision { ALLOW, BLOCK }

    data class AccessCheck(val decision: Decision, val rule: ContactRuleEntity?)

    suspend fun getDecision(contactId: String, whitelistMode: Boolean): Decision {
        val rule = dao.findByContactId(normalizePhone(contactId))
        return ContactAccessPolicy.decide(rule?.mode, whitelistMode)
    }

    suspend fun getAccess(contactId: String, whitelistMode: Boolean): AccessCheck =
        AccessCheck(
            decision = getDecision(contactId, whitelistMode),
            rule = dao.findByContactId(normalizePhone(contactId))
        )

    suspend fun allow(contactId: String, label: String? = null) =
        dao.upsert(ContactRuleEntity.allow(normalizePhone(contactId), label))

    suspend fun block(contactId: String, label: String? = null) =
        dao.upsert(ContactRuleEntity.block(normalizePhone(contactId), label))

    suspend fun markPending(contactId: String, label: String? = null) =
        dao.upsert(ContactRuleEntity.pending(normalizePhone(contactId), label))

    suspend fun remove(contactId: String) = dao.deleteByContactId(normalizePhone(contactId))

    suspend fun whitelist(): List<ContactRuleEntity> = dao.getByMode(ContactRuleEntity.MODE_ALLOW)
    suspend fun blacklist(): List<ContactRuleEntity> = dao.getByMode(ContactRuleEntity.MODE_BLOCK)
    suspend fun pending(): List<ContactRuleEntity> = dao.getByMode(ContactRuleEntity.MODE_PENDING)
    fun whitelistFlow(): Flow<List<ContactRuleEntity>> = dao.getByModeFlow(ContactRuleEntity.MODE_ALLOW)
    fun blacklistFlow(): Flow<List<ContactRuleEntity>> = dao.getByModeFlow(ContactRuleEntity.MODE_BLOCK)

    companion object {
        /**
         * Normalizes any WhatsApp identifier to bare digits: JID suffixes (`@s.whatsapp.net`,
         * `@c.us`, `@g.us`), device suffixes (`.0:12@...`), `+`, spaces and dashes are all
         * stripped. Groups (ending `@g.us`) are returned unchanged so they can be
         * whitelisted/blocked as whole chats.
         */
        fun normalizePhone(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            // Group JIDs keep their id so a group can be allowed/blocked as one entry.
            if (trimmed.endsWith("@g.us")) return trimmed.substringBefore('@')
            val noJid = trimmed.substringBefore('@')
            // AD JIDs carry a device suffix ("62812:34", "62812.0:34"); only the user part
            // before it is the phone number, so those digits must not be appended.
            val userPart = noJid.substringBefore(':').substringBefore('.')
            return userPart.filter { it.isDigit() }
        }
    }
}

/** Extracts the bare-number id from a JID, e.g. "62812...@s.whatsapp.net" -> "62812...". */
fun agentIdFromJid(jid: String): String = jid.substringBefore('@')

/** Pure decision logic so the security matrix is unit-testable without Room. */
object ContactAccessPolicy {
    fun decide(ruleMode: String?, whitelistMode: Boolean): ContactAccessRepository.Decision {
        return when (ruleMode) {
            ContactRuleEntity.MODE_ALLOW -> ContactAccessRepository.Decision.ALLOW
            ContactRuleEntity.MODE_BLOCK -> ContactAccessRepository.Decision.BLOCK
            ContactRuleEntity.MODE_PENDING -> ContactAccessRepository.Decision.BLOCK
            null ->
                if (whitelistMode) ContactAccessRepository.Decision.BLOCK
                else ContactAccessRepository.Decision.ALLOW
            else -> ContactAccessRepository.Decision.BLOCK
        }
    }
}
