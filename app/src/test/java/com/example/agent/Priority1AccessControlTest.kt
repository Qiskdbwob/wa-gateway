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
}
