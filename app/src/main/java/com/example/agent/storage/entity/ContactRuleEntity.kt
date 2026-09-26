package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Priority 1 — WhatsApp contact access control (whitelist / blacklist).
 *
 * `contactId` is the normalized phone digits (no `+`, no JID suffix), so a number stays
 * matched whether WhatsApp sends `62812...@s.whatsapp.net`, `62812...@c.us` or a bare
 * number. One row per number per mode keeps the table trivially indexed.
 *
 * Entries with [ALLOWED] / [BLOCKED] are managed directly by the user (UI or chat
 * commands). [PENDING] is written by the approval layer for requesters that tried to
 * use a CONFIRM tool before being approved.
 */
@Entity(
    tableName = "contact_rules",
    indices = [Index(value = ["contactId"], unique = true)]
)
data class ContactRuleEntity(
    @PrimaryKey
    val id: String,
    /** Normalized phone digits, e.g. "628123456789". */
    val contactId: String,
    val mode: String, // ContactRule.MODE_ALLOW / MODE_BLOCK
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MODE_ALLOW = "ALLOWED"
        const val MODE_BLOCK = "BLOCKED"
        const val MODE_PENDING = "PENDING"

        fun allow(contactId: String, label: String? = null) =
            ContactRuleEntity(
                id = contactId,
                contactId = contactId,
                mode = MODE_ALLOW,
                label = label
            )

        fun block(contactId: String, label: String? = null) =
            ContactRuleEntity(
                id = contactId,
                contactId = contactId,
                mode = MODE_BLOCK,
                label = label
            )

        fun pending(contactId: String, label: String? = null) =
            ContactRuleEntity(
                id = contactId,
                contactId = contactId,
                mode = MODE_PENDING,
                label = label
            )
    }
}
