package cn.codexremote.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    private fun message(id: String, kind: String, text: String = id) = JSONObject().put("id", id).put("kind", kind).put("text", text)
    @Test fun onlyLatestThoughtIsVisibleWithoutAccumulatingRows() {
        val messages = listOf(message("u", "user"), message("r1", "reasoning", "older"), message("r2", "reasoning", "latest"), message("a", "agentMessage"))
        assertEquals(2, timelineEntries(messages).size)
        assertEquals("latest", latestThought(JSONObject().put("messages", JSONArray(messages))))
    }
    @Test fun latestFieldReplacesOldSummaries() {
        val view = JSONObject().put("messages", JSONArray().put(message("old", "reasoning", "stale"))).put("latestReasoning", JSONObject().put("text", "newest"))
        assertEquals("newest", latestThought(view))
        view.put("latestReasoning", JSONObject.NULL)
        assertEquals("", latestThought(view))
    }
    @Test fun noOldThoughtWhileNewUserTurnStarts() {
        assertEquals("", latestThought(JSONObject().put("messages", JSONArray().put(message("r", "reasoning", "stale")).put(message("u", "user")))))
    }
    @Test fun adjacentToolsBecomeOneCompactGroup() {
        val rows = timelineEntries(listOf(message("u", "user"), message("c1", "commandExecution"), message("r", "reasoning"), message("c2", "mcpToolCall"), message("a", "agentMessage")))
        assertEquals(3, rows.size)
        assertEquals(2, rows[1].executions.size)
    }
    @Test fun appendingExecutionDoesNotChangeGroupIdentity() {
        val first = message("c1", "commandExecution")
        assertEquals(timelineEntries(listOf(first)).first().id, timelineEntries(listOf(first, message("c2", "commandExecution"))).first().id)
    }
    @Test fun multilineCommandIsNeverParsedAsItsOutput() {
        val item = message("c", "commandExecution").put("command", "one\ntwo").put("output", "three\nfour").put("exitCode", 0)
        val view = executionView(item)
        assertEquals("one\ntwo", view.command); assertEquals("three\nfour", view.output); assertEquals("退出码 0", view.status); assertTrue(view.structured); assertFalse(view.failed)
    }
    @Test fun nonzeroExitIsFailureAndNullExitCanStillRun() {
        val item = message("c", "commandExecution").put("status", "inProgress").put("exitCode", JSONObject.NULL)
        assertEquals("运行中", executionView(item).status)
        item.put("exitCode", 2)
        assertTrue(executionView(item).failed)
    }
    @Test fun legacyMixedLogIsNotSilentlySplitIntoIncorrectSections() {
        val view = executionView(message("c", "commandExecution", "multiline command\noutput"))
        assertFalse(view.structured); assertEquals("", view.command); assertEquals("multiline command\noutput", view.output)
    }
}
