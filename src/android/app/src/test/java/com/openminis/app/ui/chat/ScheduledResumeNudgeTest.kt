package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-resume-nudge] Port of iOS c9131e862's resume nudge: after
 * a scheduled fire inserted between tool calls is answered, one hidden
 * <system-reminder> continues the interrupted task.
 */
class ScheduledResumeNudgeTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test fun `nudge is hidden and asks to continue the interrupted task`() {
        // The old optional iOS substring scan matched an entire file after the
        // Swift constant moved. Check the Android behavior this suite owns.
        val text = ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT
        assertTrue(text.startsWith("<system-reminder>"))
        assertTrue(text.endsWith("</system-reminder>"))
        assertTrue(text.contains("continue it now from where you left off"))
        assertTrue(text.contains("already complete"))
    }

    @Test fun `owed only after a scheduled insert, cleared by a user batch`() {
        assertTrue(vm.contains("var scheduledResumeNudgeOwed = false"))
        assertTrue(vm.contains("scheduledResumeNudgeOwed = scheduledFire != null"))
    }

    @Test fun `sent once, on a clean stop with content, not when a user prompt waits`() {
        val gate = vm.substringAfter("val cleanStop = turnFinishReason == \"stop\" || turnFinishReason == \"end_turn\"")
            .substringBefore("// Auto-title after first exchange")
        assertTrue(gate.contains("if ((scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) || userTaskCheck != null) {"))
        assertTrue(gate.contains("scheduledResumeNudgeOwed = false"))
        assertTrue(gate.contains("chatRepository.appendMessage(nudgeSid, \"user\""))
        assertTrue(gate.trimEnd().endsWith("continue\n                }") || gate.contains("continue"))
    }
}
