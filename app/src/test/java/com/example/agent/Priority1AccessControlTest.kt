package com.example.agent

import com.example.agent.model.ToolPermission
import com.example.agent.storage.ContactAccessPolicy
import com.example.agent.storage.ContactAccessRepository
import com.example.agent.storage.agentIdFromJid
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.tool.DeletePathTool
import com.example.agent.tool.ToolRegistry
import com.example.agent.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Priority 1 — WhatsApp contact access control.
 *
 * The point of these tests is the security boundary: with the whitelist on, only listed
 * numbers may reach the agent; with it off, everything runs except explicitly blocked
 * numbers. The decision matrix is pure logic, so it is tested directly (no Room).
 */
class Priority1AccessControlTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun priority1_whitelistModeOnlyAllowsListedNumbers() {
        // rule | whitelist OFF | whitelist ON
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            ContactAccessPolicy.decide(null, whitelistMode = false)
        )
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            ContactAccessPolicy.decide(null, whitelistMode = true)
        )

        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            ContactAccessPolicy.decide(ContactRuleEntity.MODE_ALLOW, whitelistMode = false)
        )
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            ContactAccessPolicy.decide(ContactRuleEntity.MODE_ALLOW, whitelistMode = true)
        )

        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            ContactAccessPolicy.decide(ContactRuleEntity.MODE_BLOCK, whitelistMode = false)
        )
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            ContactAccessPolicy.decide(ContactRuleEntity.MODE_BLOCK, whitelistMode = true)
        )

        // A number that already tried to talk to the agent stays blocked until decided.
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            ContactAccessPolicy.decide(ContactRuleEntity.MODE_PENDING, whitelistMode = false)
        )
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            ContactAccessPolicy.decide("UNKNOWN_MODE", whitelistMode = false)
        )
    }

    @Test
    fun priority1_numbersAreNormalizedSoEveryJidFormMatches() {
        assertEquals("628123456789", ContactAccessRepository.normalizePhone("+62812-3456-789"))
        // Device suffixes of AD JIDs are dropped, not appended to the number.
        assertEquals("6281234567890", ContactAccessRepository.normalizePhone("6281234567890:34@s.whatsapp.net"))
        assertEquals("6281234567890", ContactAccessRepository.normalizePhone("6281234567890.0:34@s.whatsapp.net"))
        assertEquals("628123456789", ContactAccessRepository.normalizePhone("628123456789@c.us"))
        // Groups keep their id (without the server suffix) so a whole group can be blocked.
        assertEquals("120363012345", ContactAccessRepository.normalizePhone("120363012345@g.us"))
        assertEquals("", ContactAccessRepository.normalizePhone("   "))

        assertEquals("628123456789", agentIdFromJid("628123456789@s.whatsapp.net"))
        assertEquals("628123456789", agentIdFromJid("628123456789"))
    }

    @Test
    fun priority1_repositoryBlocksUnlistedNumbersOnlyWhenWhitelistIsOn() = runBlocking {
        val dao = FakeContactRuleDao()
        val repository = ContactAccessRepository(dao)

        // No rule + whitelist off -> allowed.
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            repository.getDecision("628111@s.whatsapp.net", whitelistMode = false)
        )
        // No rule + whitelist on -> blocked (that is the whole point of the mode).
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            repository.getDecision("628111@s.whatsapp.net", whitelistMode = true)
        )

        repository.allow("628111", "Istri")
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            repository.getDecision("628111@s.whatsapp.net", whitelistMode = true)
        )
        assertEquals(1, repository.whitelist().size)

        repository.block("628222", "Spammer")
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            repository.getDecision("628222@c.us", whitelistMode = false)
        )
        assertEquals(1, repository.blacklist().size)

        repository.remove("628222")
        assertNull(dao.findByContactId("628222"))
        // Blocking overrides a previous allow for the same number (one row per contact).
        repository.block("628111")
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            repository.getDecision("628111", whitelistMode = false)
        )
    }

    @Test
    fun priority1_deleteToolIsConfirmAndHiddenWithoutApprovalLayer() {
        val workspace = Workspace(temp.root)
        val deleteTool = DeletePathTool(workspace)
        assertEquals(ToolPermission.CONFIRM, deleteTool.permission)

        val registry = ToolRegistry.withBuiltIns(workspace)
        assertTrue(registry.all().any { it.name == "delete_path" })

        // Destructive tool is not advertised while nothing can approve it...
        assertTrue(registry.definitions().none { it.name == "delete_path" })
        // ...and is advertised as soon as approval routing is enabled.
        registry.approvalEnabled = true
        assertTrue(registry.definitions().any { it.name == "delete_path" })

        // Non-destructive file tools stay SAFE and always available.
        assertTrue(registry.get("read_file")!!.permission == ToolPermission.SAFE)
        assertTrue(registry.get("write_file")!!.permission == ToolPermission.SAFE)
    }

    @Test
    fun priority1_deleteStaysInsideTheWorkspace() = runBlocking {
        val workspace = Workspace(temp.root)
        val deleteTool = DeletePathTool(workspace)

        val outside = deleteTool.execute("""{"path":"../../etc/passwd"}""")
        assertFalse(outside.success)
        assertTrue(outside.error!!.contains("workspace", ignoreCase = true))

        val root = deleteTool.execute("""{"path":"."}""")
        assertFalse(root.success)

        val inside = temp.newFile("bocor.txt")
        assertTrue(inside.exists())
        val deleted = deleteTool.execute("""{"path":"bocor.txt"}""")
        assertTrue(deleted.success)
        assertFalse(inside.exists())
    }

    @Test
    fun priority1_prefixAndLocalFormatEntriesStillMatchTheSender() {
        val rules = listOf(
            ContactRuleEntity.allow("62811"), // too short to be a prefix rule
            ContactRuleEntity.allow("62812345"), // prefix of the international number
            ContactRuleEntity.allow("081298765432"), // Indonesian local shape
            ContactRuleEntity.block("62812345999"), // exact block beats a prefix allow
        )

        // The exact id always wins.
        assertEquals("62812345", ContactAccessRepository.matchingRule(rules, "62812345")?.contactId)
        // WhatsApp delivers full JIDs; those must match the entry saved as plain digits.
        assertEquals(
            "62812345",
            ContactAccessRepository.matchingRule(rules, "62812345:12@s.whatsapp.net")?.contactId
        )
        // A short exact entry still governs its own sender, even below the prefix floor.
        assertEquals(
            "62811",
            ContactAccessRepository.matchingRule(rules, "62811@s.whatsapp.net")?.contactId
        )
        // A saved prefix governs the full international number...
        assertEquals("62812345", ContactAccessRepository.matchingRule(rules, "628123456789")?.contactId)
        // ...an exact BLOCK beats a longer prefix ALLOW...
        val blocked = ContactAccessRepository.matchingRule(rules, "62812345999")
        assertEquals("62812345999", blocked?.contactId)
        assertEquals(ContactRuleEntity.MODE_BLOCK, blocked?.mode)
        // ...and an entry typed in local format still governs the 62-prefixed JID.
        assertEquals(
            "081298765432",
            ContactAccessRepository.matchingRule(rules, "6281298765432")?.contactId
        )
        // Too-short entries never widen the gate by themselves.
        assertNull(ContactAccessRepository.matchingRule(rules, "62811222333"))
        assertNull(ContactAccessRepository.matchingRule(rules, ""))
    }

    @Test
    fun priority1_prefixRulesDecideAccessLikeExactOnes() = runBlocking {
        val dao = FakeContactRuleDao()
        val repository = ContactAccessRepository(dao)

        // The user whitelisted only the prefix ("my own numbers"); the sender uses any
        // linked device of one of those numbers.
        repository.allow("62812345", "keluarga")
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            repository.getDecision("628123456789", whitelistMode = true)
        )
        assertEquals(
            ContactAccessRepository.Decision.ALLOW,
            repository.getDecision("628123456789:44@s.whatsapp.net", whitelistMode = true)
        )
        // A number outside the prefix is still blocked by the whitelist mode.
        assertEquals(
            ContactAccessRepository.Decision.BLOCK,
            repository.getDecision("628999999999", whitelistMode = true)
        )
    }
}
