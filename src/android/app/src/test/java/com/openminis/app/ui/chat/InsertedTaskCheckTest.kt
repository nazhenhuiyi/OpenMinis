package com.openminis.app.ui.chat
import org.junit.Assert.*
import org.junit.Test
class InsertedTaskCheckTest {
    @Test fun sideQuestionGetsOneCheckWithoutReplayingAnything() {
        val check = InsertedTaskCheck(); check.inserted(false)
        val text = check.takeIfReady(false, true, false, 1, 200)!!
        assertTrue(text.contains("latest user instructions"))
        assertTrue(text.contains("do not restart or re-delegate"))
        assertTrue(text.contains("Check actual files first"))
        assertNull(check.takeIfReady(false, true, false, 2, 200))
    }
    @Test fun waitingUserAndNonfinalOrFailedResponseKeepTheirOwnControl() {
        val check = InsertedTaskCheck(); check.inserted(false)
        assertNull(check.takeIfReady(false, true, true, 1, 200))
        assertNull(check.takeIfReady(true, true, false, 1, 200))
        assertNull(check.takeIfReady(false, false, false, 1, 200))
        assertNotNull(check.takeIfReady(false, true, false, 1, 200))
    }
    @Test fun exhaustedBudgetAndFreshProcessCannotCreateReplay() {
        val check = InsertedTaskCheck(); check.inserted(false)
        assertNull(check.takeIfReady(false, true, false, 199, 200))
        assertNull(InsertedTaskCheck().takeIfReady(false, true, false, 1, 200))
    }
    @Test fun scheduledInsertUsesItsExistingSeparateContinuation() {
        val check = InsertedTaskCheck(); check.inserted(false); check.inserted(true)
        assertNull(check.takeIfReady(false, true, false, 1, 200))
    }
}
