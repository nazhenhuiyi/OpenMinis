package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SingleTaskCancellationTest {
    @After fun reset() = AgentJobRegistry.resetForTest()

    @Test fun cancelOneTaskKeepsSiblingAndRejectsLateCompletion() {
        val callbacks = mutableListOf<Pair<String, String>>()
        AgentJobRegistry.followUpDispatcher = { parent, text, _ -> callbacks += parent to text }
        fun start(title: String, child: String): AgentJob {
            val job = AgentJobRegistry.register(
                title = title, origin = AgentJobOrigin.TOOL,
                trigger = AgentJobTrigger.Immediate,
                target = AgentJobTarget.ChildOfCurrent("main", null), prompt = title,
            )
            AgentJobRegistry.markRunning(job.id, child)
            return job
        }
        val a = start("task A", "child-a")
        val b = start("task B", "child-b")
        var stopped = false
        AgentJobRegistry.markRunning(a.id, "child-a") { stopped = true }
        AgentJobRegistry.cancel(a.id, "user-stop-task")
        AgentJobRegistry.finish(a.id, AgentJobState.DONE, "late success")
        assertTrue(stopped)
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(a.id)?.state)
        assertEquals(AgentJobState.RUNNING, AgentJobRegistry.job(b.id)?.state)
        assertFalse(AgentJobRegistry.isDelegationMuted("main"))
        AgentJobRegistry.finish(b.id, AgentJobState.DONE, "B result")
        assertEquals(2, callbacks.size)
        assertTrue(callbacks.all { it.first == "main" })
        assertTrue(callbacks.first().second.contains("cancelled"))
        assertFalse(callbacks.any { it.second.contains("late success") })
    }
}
