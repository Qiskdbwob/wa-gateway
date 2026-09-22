package com.example.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.storage.RoomAgentSessionRepository
import com.example.agent.storage.db.AgentDatabase
import com.example.agent.storage.entity.AgentConfigEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAgentSessionRepositoryTest {

    private lateinit var database: AgentDatabase
    private lateinit var repository: RoomAgentSessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAgentSessionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testGetOrCreateSessionPersistsInDatabase() = runBlocking {
        val session1 = repository.getOrCreateSession(conversationId = "628123456789")
        assertNotNull(session1.sessionId)
        assertEquals("628123456789", session1.conversationId)

        // Calling again with same conversationId returns existing session
        val session2 = repository.getOrCreateSession(conversationId = "628123456789")
        assertEquals(session1.sessionId, session2.sessionId)

        val allSessions = repository.getAllSessions()
        assertEquals(1, allSessions.size)
        assertEquals("628123456789", allSessions[0].conversationId)
    }

    @Test
    fun testSaveAndRetrieveMessagesInOrder() = runBlocking {
        val session = repository.getOrCreateSession(conversationId = "user-abc")

        val msg1 = AgentMessage(
            sessionId = session.sessionId,
            role = AgentRole.USER,
            content = "Pesat 1",
            timestamp = 1000L
        )
        val msg2 = AgentMessage(
            sessionId = session.sessionId,
            role = AgentRole.ASSISTANT,
            content = "Balasan 1",
            timestamp = 2000L
        )
        val msg3 = AgentMessage(
            sessionId = session.sessionId,
            role = AgentRole.USER,
            content = "Pertanyaan 2",
            timestamp = 3000L
        )

        repository.saveMessage(msg1)
        repository.saveMessage(msg2)
        repository.saveMessage(msg3)

        val messages = repository.getMessages(session.sessionId, limit = 2)
        // Limit 2 should return last 2 messages in chronological order
        assertEquals(2, messages.size)
        assertEquals("Balasan 1", messages[0].content)
        assertEquals(AgentRole.ASSISTANT, messages[0].role)
        assertEquals("Pertanyaan 2", messages[1].content)
        assertEquals(AgentRole.USER, messages[1].role)

        // Check session preview and message count
        val allSessions = repository.getAllSessions()
        assertEquals(1, allSessions.size)
        assertEquals("Pertanyaan 2", allSessions[0].lastMessagePreview)
        assertEquals(3, allSessions[0].messageCount)
    }

    @Test
    fun testClearSessionResetsMessages() = runBlocking {
        val session = repository.getOrCreateSession(conversationId = "clear-test")
        repository.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.USER, content = "Halo"))

        assertEquals(1, repository.getMessages(session.sessionId).size)

        repository.clearSession(session.sessionId)
        assertEquals(0, repository.getMessages(session.sessionId).size)

        val updatedSession = repository.getAllSessions().find { it.sessionId == session.sessionId }
        assertNotNull(updatedSession)
        assertEquals(0, updatedSession?.messageCount)
        assertNull(updatedSession?.lastMessagePreview)
    }

    @Test
    fun testDeleteSessionCascade() = runBlocking {
        val session = repository.getOrCreateSession(conversationId = "delete-test")
        repository.saveMessage(AgentMessage(sessionId = session.sessionId, role = AgentRole.USER, content = "Pesan"))

        assertEquals(1, repository.getAllSessions().size)
        assertEquals(1, repository.getMessages(session.sessionId).size)

        repository.deleteSession(session.sessionId)
        assertEquals(0, repository.getAllSessions().size)
        assertEquals(0, repository.getMessages(session.sessionId).size)
    }

    @Test
    fun testSaveAndGetConfig() = runBlocking {
        val config = AgentConfigEntity(
            isAutoReplyEnabled = true,
            useEchoFallback = false,
            baseUrl = "https://custom-api.com/v1",
            apiKey = "sk-custom-secret",
            modelId = "gpt-4o",
            systemPrompt = "Custom prompt here"
        )

        repository.saveConfig(config)

        val loaded = repository.getConfig()
        assertNotNull(loaded)
        assertEquals(true, loaded?.isAutoReplyEnabled)
        assertEquals(false, loaded?.useEchoFallback)
        assertEquals("https://custom-api.com/v1", loaded?.baseUrl)
        assertEquals("sk-custom-secret", loaded?.apiKey)
        assertEquals("gpt-4o", loaded?.modelId)
        assertEquals("Custom prompt here", loaded?.systemPrompt)
    }
}
