package com.openminis.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainConversationTest {
    @Test fun concurrentResolutionAndReopenPreserveHistoryAndBinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "main-conversation-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val repo = ChatRepository(db.chatDao())
            val old = repo.createSession("old-model", "Existing conversation")
            repo.appendMessage(old.id, "user", "[{\"type\":\"text\",\"value\":\"old history\"}]")
            val resolved = (1..20).map { async(Dispatchers.IO) {
                ChatRepository(db.chatDao()).mainConversation("initial-model", false).id
            } }.awaitAll()
            assertEquals(setOf(ChatRepository.MAIN_CONVERSATION_ID), resolved.toSet())
            val id = resolved.first()
            repo.appendMessage(id, "user", "[{\"type\":\"text\",\"value\":\"first topic\"}]")
            repo.updateSessionModel(id, "chosen-model")
            db.close()
            db = open()
            val reopened = ChatRepository(db.chatDao())
            val main = reopened.mainConversation("different-default", true)
            assertEquals(id, main.id)
            assertEquals("chosen-model", main.modelId)
            assertEquals(0, main.memoryEnabled)
            assertFalse(main.isHiddenFromLists)
            assertEquals(1, reopened.loadMessages(id).size)
            assertEquals(1, reopened.loadMessages(old.id).size)
            assertEquals(2, db.chatDao().listSessions().size)
            // Explicit deletion starts an empty main conversation; never adopts an old chat.
            reopened.deleteSessionOnly(id)
            val fresh = reopened.mainConversation("new-model", true)
            assertEquals(id, fresh.id)
            assertTrue(reopened.loadMessages(id).isEmpty())
            assertNotNull(reopened.getSession(old.id))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
