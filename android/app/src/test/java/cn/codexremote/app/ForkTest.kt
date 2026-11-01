package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForkTest {
    private val key = ThreadKey("pc-a", "11111111-1111-4111-8111-111111111111")
    private fun context() = JSONObject().put("hostId", key.hostId).put("threadId", key.threadId).put("instanceId", "i").put("latestTurnId", "latest").put("activeTurnId", "").put("canFork", true).put("canWorktree", true)
    @Test fun forkRoutesByHostAndRealSourceNeverByTitle() {
        val body = forkOperation(key, "i", context().put("title", "same title").put("cwd", "C:/injected"), "same-directory")
        assertEquals(key.hostId, body.getString("hostId")); assertEquals(key.threadId, body.getString("threadId"))
        assertEquals("latest", body.getString("latestTurnId")); assertEquals("", body.getString("turnId"))
        assertFalse(body.has("cwd")); assertFalse(body.has("text")); assertFalse(body.has("title"))
    }
    @Test fun otherComputerOrInstanceOrSourceIsRejected() {
        listOf("hostId", "instanceId", "threadId").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) { forkOperation(key, "i", context().put(field, "different"), "same-directory") }
        }
    }
    @Test fun activeAndUnsupportedWorktreeCannotBeSubmitted() {
        assertThrows(IllegalArgumentException::class.java) { forkOperation(key, "i", context().put("activeTurnId", "running"), "same-directory") }
        assertThrows(IllegalArgumentException::class.java) { forkOperation(key, "i", context().put("canFork", false), "same-directory") }
        assertThrows(IllegalArgumentException::class.java) { forkOperation(key, "i", context().put("canWorktree", false), "worktree") }
        assertThrows(IllegalArgumentException::class.java) { forkOperation(key, "i", context(), "remote") }
        assertEquals("worktree", forkOperation(key, "i", context(), "worktree").getString("environment"))
    }
    @Test fun pendingClientIdIsNeverTreatedAsReadyThread() {
        assertNull(readyForkThread(JSONObject().put("status", "queued").put("clientThreadId", key.threadId)))
        assertNull(readyForkThread(JSONObject().put("status", "queued").put("threadId", key.threadId)))
    }
    @Test fun incompleteSynchronizationIsNeverOpenedForSending() {
        val result = JSONObject().put("status", "created").put("threadId", key.threadId)
        assertNull(readyForkThread(result)); assertNull(readyForkThread(result.put("synchronization", JSONObject().put("status", "failed"))))
        assertEquals(key.threadId, readyForkThread(result.put("synchronization", JSONObject().put("status", "complete"))))
        assertNull(readyForkThread(result.put("threadId", "invalid")))
    }
}
