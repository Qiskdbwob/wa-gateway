package com.example.agent.browser

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the orchestration layer: what the model actually sees ([describe]), how a
 * stored credential is used, and — most importantly — that a captcha/2FA handoff parks the turn
 * and reports honestly instead of inventing a result.
 *
 * The engine is a hand-written fake (the project deliberately has no mocking library: see
 * `FakeDaos.kt` and `ScriptedProvider`), which also lets the handoff be driven with virtual time.
 */
class BrowserAutomationManagerTest {

    private class FakeBrowserEngine(
        private val pages: List<BrowserSnapshot> = emptyList()
    ) : BrowserEngine {
        override val kind: String = "fake"

        val opened = mutableListOf<String>()
        val typed = mutableListOf<Triple<String, String, Boolean>>()
        val selected = mutableListOf<Pair<String, String>>()
        val pressed = mutableListOf<String>()

        private var snapshotCount = 0

        override suspend fun open(url: String): BrowserActionResult {
            opened += url
            return BrowserActionResult(ok = true, url = url, detail = "dibuka", changed = true)
        }

        override suspend fun snapshot(maxChars: Int): BrowserSnapshot {
            if (pages.isEmpty()) return BrowserSnapshot("", "", "", emptyList())
            val page = pages[minOf(snapshotCount, pages.size - 1)]
            snapshotCount += 1
            return page
        }

        override suspend fun click(ref: String): BrowserActionResult =
            BrowserActionResult(ok = true, url = "https://contoh.test", detail = "klik $ref", changed = true)

        override suspend fun typeText(ref: String, text: String, submit: Boolean): BrowserActionResult {
            typed += Triple(ref, text, submit)
            return BrowserActionResult(ok = true, url = "https://contoh.test", detail = "ketik $ref", changed = true)
        }

        override suspend fun selectOption(ref: String, value: String): BrowserActionResult {
            selected += ref to value
            return BrowserActionResult(ok = true, url = "https://contoh.test", detail = "pilih $ref", changed = true)
        }

        override suspend fun pressKey(key: String): BrowserActionResult {
            pressed += key
            return BrowserActionResult(ok = true, url = "https://contoh.test", detail = "tombol $key", changed = true)
        }

        override suspend fun scroll(direction: String, amountPx: Int): BrowserActionResult =
            BrowserActionResult(ok = true, detail = "gulir")

        override suspend fun screenshot(): ByteArray? = null
        override suspend fun currentUrl(): String = "https://contoh.test"
        override suspend fun pageTitle(): String = "Contoh"
        override fun isReady(): Boolean = true
        override suspend fun clearSession() {}
        override fun dispose() {}
    }

    private fun loginForm(): BrowserSnapshot = BrowserSnapshot(
        url = "https://contoh.test/login",
        title = "Masuk",
        text = "Masuk ke akun Anda",
        elements = listOf(
            BrowserElement("agx-0", "input", "search", "Cari di situs", ""),
            BrowserElement("agx-1", "input", "email", "Email atau telepon", ""),
            BrowserElement("agx-2", "input", "password", "Kata sandi", ""),
            BrowserElement("agx-3", "button", "", "Masuk", "")
        )
    )

    private fun signedInPage(): BrowserSnapshot = BrowserSnapshot(
        url = "https://contoh.test/feed",
        title = "Beranda",
        text = "Selamat datang",
        elements = listOf(BrowserElement("agx-0", "button", "", "Profil", ""))
    )

    private fun challengePage(): BrowserSnapshot = BrowserSnapshot(
        url = "https://contoh.test/challenge",
        title = "Verifikasi",
        text = "Selesaikan captcha untuk melanjutkan",
        elements = emptyList()
    )

    private fun credential() = listOf(
        SiteCredential("contoh", "https://contoh.test/login", "budi@contoh.id", "rahasia")
    )

    // --- what the model sees -----------------------------------------------------------------

    @Test
    fun describeShowsUrlTitleTextAndAddressableRefs() {
        val manager = BrowserAutomationManager(engine = FakeBrowserEngine(), credentials = { emptyList() })
        val described = manager.describe(loginForm())
        assertTrue(described.contains("https://contoh.test/login"))
        assertTrue(described.contains("Judul: Masuk"))
        assertTrue(described.contains("Masuk ke akun Anda"))
        assertTrue(described.contains("agx-1"))
        assertTrue(described.contains("Email atau telepon"))
    }

    // --- generic login -----------------------------------------------------------------------

    @Test
    fun loginWithoutAStoredAccountRefusesInsteadOfGuessing() = runTest {
        val manager = BrowserAutomationManager(engine = FakeBrowserEngine(), credentials = { emptyList() })
        val result = manager.login("instagram", "conv-1")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Pengaturan"))
        assertTrue(result.error!!.contains("instagram"))
    }

    @Test
    fun loginFillsTheAccountFieldNotTheSearchBox() = runTest {
        val engine = FakeBrowserEngine(listOf(loginForm(), signedInPage()))
        val manager = BrowserAutomationManager(engine = engine, credentials = { credential() })

        val result = manager.login("contoh", "conv-1")

        assertTrue(result.ok)
        assertEquals(listOf("https://contoh.test/login"), engine.opened)
        // agx-0 is a search box; the account field must be agx-1.
        assertEquals(listOf("agx-1", "agx-2"), engine.typed.map { it.first })
        assertEquals("budi@contoh.id", engine.typed.first().second)
        assertFalse(engine.typed.first().third)
        assertEquals("rahasia", engine.typed.last().second)
        assertTrue(engine.typed.last().third)
    }

    @Test
    fun loginReportsAFormThatIsStillThereAsAFailure() = runTest {
        val engine = FakeBrowserEngine(listOf(loginForm(), loginForm()))
        val manager = BrowserAutomationManager(engine = engine, credentials = { credential() })

        val result = manager.login("contoh", "conv-1")

        assertFalse(result.ok)
        assertTrue(result.error!!.contains("belum berhasil"))
        assertTrue(result.error!!.contains("form login masih tampil"))
    }

    @Test
    fun aPageWithoutAnyLoginFieldIsNotGuessedAt() = runTest {
        val engine = FakeBrowserEngine(listOf(signedInPage()))
        val manager = BrowserAutomationManager(engine = engine, credentials = { credential() })

        val result = manager.login("contoh", "conv-1")

        assertFalse(result.ok)
        assertTrue(result.error!!.contains("tidak dikenali"))
        assertTrue(engine.typed.isEmpty())
    }

    // --- human handoff ----------------------------------------------------------------------

    @Test
    fun aCaptchaHandoffParksTheTurnAndResumesOnlyWhenTheUserConfirms() = runTest {
        val engine = FakeBrowserEngine(listOf(loginForm(), challengePage(), signedInPage()))
        val notified = mutableListOf<String>()
        val manager = BrowserAutomationManager(
            engine = engine,
            credentials = { credential() },
            notifyUser = { conversationId, message -> notified += "$conversationId|$message" }
        )

        val pending = async { manager.login("contoh", "conv-1") }
        runCurrent()

        assertNotNull(manager.pendingUserAction.value)
        assertEquals(1, notified.size)
        assertTrue(notified.single().startsWith("conv-1|"))
        assertTrue(notified.single().contains("captcha", ignoreCase = true))

        manager.completeUserAction()
        advanceUntilIdle()

        assertTrue(pending.await().ok)
        assertNull(manager.pendingUserAction.value)
    }

    @Test
    fun abandoningTheHandoffIsReportedAsAFailureNotASuccess() = runTest {
        val engine = FakeBrowserEngine(listOf(loginForm(), challengePage(), signedInPage()))
        val manager = BrowserAutomationManager(engine = engine, credentials = { credential() })

        val pending = async { manager.login("contoh", "conv-1") }
        runCurrent()
        assertNotNull(manager.pendingUserAction.value)

        manager.abandonUserAction()
        advanceUntilIdle()

        val result = pending.await()
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Verifikasi manual belum selesai"))
        assertNull(manager.pendingUserAction.value)
    }

    @Test
    fun aHandoffNobodyCompletesTimesOutInsteadOfHangingForever() = runTest {
        val manager = BrowserAutomationManager(engine = FakeBrowserEngine(), credentials = { emptyList() })

        val pending = async { manager.requestUserHelp("selesaikan captcha") }
        runCurrent()
        assertNotNull(manager.pendingUserAction.value)

        // No one taps "Selesai": the virtual clock runs the 6-minute watchdog out.
        advanceUntilIdle()

        assertFalse(pending.await())
        assertNull(manager.pendingUserAction.value)
    }

    // --- new actions reach the engine ---------------------------------------------------------

    @Test
    fun selectAndPressKeyAreForwardedToTheEngine() = runTest {
        val engine = FakeBrowserEngine()
        val manager = BrowserAutomationManager(engine = engine, credentials = { emptyList() })

        assertTrue(manager.select("agx-4", "Indonesia").ok)
        assertTrue(manager.pressKey("Escape").ok)

        assertEquals(listOf("agx-4" to "Indonesia"), engine.selected)
        assertEquals(listOf("Escape"), engine.pressed)
    }

    // --- account-field selection --------------------------------------------------------------

    @Test
    fun theAccountFieldIsPreferredOverASearchBox() {
        val picked = BrowserAutomationManager.pickUserField(loginForm().elements)
        assertEquals("agx-1", picked?.ref)
    }

    @Test
    fun withoutAnyHintTheFirstTextInputIsStillUsed() {
        val elements = listOf(
            BrowserElement("agx-0", "input", "text", "q", ""),
            BrowserElement("agx-1", "input", "text", "s", "")
        )
        assertEquals("agx-0", BrowserAutomationManager.pickUserField(elements)?.ref)
        assertNull(BrowserAutomationManager.pickUserField(emptyList()))
        // Buttons and password fields are never treated as the account field.
        assertNull(
            BrowserAutomationManager.pickUserField(
                listOf(
                    BrowserElement("agx-0", "button", "", "Masuk", ""),
                    BrowserElement("agx-1", "input", "password", "Kata sandi", "")
                )
            )
        )
    }
}
