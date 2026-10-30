package com.openminis.app.agent.jobs
import org.junit.Assert.*
import org.junit.Test
class HelperInstructionDeliveryTest {
    private fun args() = HelperRunner.parseArgs("""{"task":"Calculate local totals","context":"CSV in workspace"}""")
    @Test fun omittedRestrictionsTravelWithDelegationAndLaterCorrection() {
        val initial = "只处理本地资料，不联网，不安装依赖；金额精确到分。"
        val correction = "改为只统计十月，输出两个可打开的文件。"
        val prompt = HelperRunner.childPrompt(args(), listOf(initial, correction))
        assertTrue(prompt.contains(initial)); assertTrue(prompt.contains(correction))
        assertTrue(prompt.indexOf(initial) < prompt.indexOf(correction))
        assertTrue(prompt.contains("CSV in workspace"))
    }
    @Test fun legacyDelegationWithoutUserSnapshotStillWorks() {
        assertEquals("Calculate local totals\n\n--- Context from the delegating agent ---\nCSV in workspace", HelperRunner.childPrompt(args()))
    }
    @Test fun oversizedUserMessageDisclosesMissingContextInsteadOfSilentTruncation() {
        val prompt = HelperRunner.childPrompt(args(), listOf("x".repeat(13_000)))
        assertTrue(prompt.contains("Message truncated")); assertTrue(prompt.length < 13_000)
    }
    @Test fun failedFinalAnswerDoesNotDenyExistingFilesOrInviteBlindRedelegation() {
        val note = HelperRunner.emptyResultNote("failed")
        assertTrue(note.contains("Files may already contain partial or completed work"))
        assertTrue(note.contains("Inspect the existing files"))
        assertFalse(note.contains("there is no deliverable"))
        assertFalse(note.contains("re-delegate with a narrower task"))
    }
    @Test fun recoveryDoesNotTreatTranscriptAsProofOfCompletedFiles() {
        val notice = HelperRunner.resumeNotice()
        assertTrue(notice.contains("inspect the actual output files"))
        assertTrue(notice.contains("partial file")); assertTrue(notice.contains("side effects"))
        assertFalse(notice.contains("tool results above are still valid"))
    }
}
