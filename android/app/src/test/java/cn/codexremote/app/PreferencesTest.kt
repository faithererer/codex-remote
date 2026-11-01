package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreferencesTest {
    @Test fun selectedHostAndAttentionSurviveScreenAndProcessRecreation() {
        val stored = HomeSelection("host-a", true).json()
        assertEquals(HomeSelection("host-a", true), HomeSelection.parse(stored))
        assertEquals("host-a", HomeSelection.parse(stored).without("host-b").hostId)
        assertEquals("", HomeSelection.parse(stored).without("host-a").hostId)
    }
    @Test fun offlineOrRenamedHostsDoNotResetSelection() {
        val saved = HomeSelection("stable-id", false)
        val host = Host("stable-id", "renamed", "https://100.64.1.10:48761", "a".repeat(64), "", "")
        assertEquals(host.id, HomeSelection.parse(saved.json()).hostId)
        assertFalse(HostState(host).online)
    }
    @Test fun malformedPreferencesFallBackWithoutCrashing() { assertEquals(HomeSelection(), HomeSelection.parse("not-json")) }
    @Test fun finalCompletionBadgeIsNotShownForProgressOrFailure() {
        val m = JSONObject("""{"kind":"agentMessage","phase":"final_answer","turnStatus":"completed"}""")
        assertTrue(completedAnswer(m))
        assertFalse(completedAnswer(JSONObject(m.toString()).put("phase", "commentary")))
        assertFalse(completedAnswer(JSONObject(m.toString()).put("turnStatus", "inProgress")))
        assertFalse(completedAnswer(JSONObject(m.toString()).put("turnStatus", "failed")))
        assertFalse(completedAnswer(JSONObject(m.toString()).put("kind", "user")))
    }
    private fun catalog() = JSONObject("""{"instanceId":"instance-a","provider":"provider-a","model":"model-a","effort":"high","models":[{"id":"model-a","efforts":["high"]},{"id":"model-b","efforts":["low","high"]}]}""")
    @Test fun modelChangesCarryOriginalSettingsAndInstance() {
        val fields = modelOperation(catalog(), "model-b", "low")
        assertEquals("provider-a", fields.getString("expectedProvider"))
        assertEquals("model-a", fields.getString("expectedModel"))
        assertEquals("instance-a", fields.getString("catalogInstanceId"))
        assertFalse(fields.has("permissions")); assertFalse(fields.has("sandboxPolicy"))
    }
    @Test(expected = IllegalArgumentException::class) fun modelsFromAnotherPcAreRejected() { modelOperation(catalog(), "other-pc-model", "low") }
    @Test(expected = IllegalArgumentException::class) fun unsupportedEffortIsRejected() { modelOperation(catalog(), "model-b", "ultra") }
    private fun target() = JSONObject("""{"instanceId":"instance-a","allowed":true,"turnId":"turn-a","fingerprint":"hash-a","text":"old"}""")
    @Test fun editIsBoundToOriginalLatestMessageAndInstance() {
        val fields = editOperation(target(), " new ")
        assertEquals("turn-a", fields.getString("editTurnId")); assertEquals("hash-a", fields.getString("editFingerprint"))
        assertEquals("instance-a", fields.getString("editInstanceId")); assertEquals("new", fields.getString("text"))
        assertFalse(fields.has("allowEarlierMessages")); assertFalse(fields.has("permissions"))
    }
    @Test(expected = IllegalArgumentException::class) fun runningMessageCannotBeEdited() { editOperation(target().put("allowed", false), "new") }
    @Test(expected = IllegalArgumentException::class) fun unchangedMessageCannotBeResubmitted() { editOperation(target(), " old ") }
    @Test(expected = IllegalArgumentException::class) fun oversizedEditIsRejected() { editOperation(target(), "a".repeat(32001)) }
    @Test fun sameConversationIdOnDifferentPcsHasIndependentCompletion() {
        val unread = mapOf(ThreadKey("a", "same") to "turn-1", ThreadKey("b", "same") to "turn-1")
        assertEquals(mapOf(ThreadKey("b", "same") to "turn-1"), unread - ThreadKey("a", "same"))
    }
    @Test fun editingDraftSurvivesTurnProgressAndIsolatesHostAndTurn() {
        val original = target(); val progressed = target().put("fingerprint", "after-completion")
        val key = ThreadKey("a", "same")
        assertEquals(editDraftKey(key, original.getString("turnId")), editDraftKey(key, progressed.getString("turnId")))
        assertNotEquals(editDraftKey(key, "turn-a"), editDraftKey(ThreadKey("b", "same"), "turn-a"))
        assertNotEquals(editDraftKey(key, "turn-a"), editDraftKey(key, "turn-b"))
    }
    @Test fun readCompletionStaysReadAfterRestartOrReplayedEvent() {
        val key = ThreadKey("a", "same")
        val restored = completionMarkers(completionMarkersJson(mapOf(key to "turn-a")))
        assertFalse(isUnreadCompletion(key, "turn-a", restored))
        assertTrue(isUnreadCompletion(key, "turn-b", restored))
    }
    @Test fun completionReadDoesNotLeakAcrossPcsOrEmptyTurns() {
        val read = mapOf(ThreadKey("a", "same") to "turn-a")
        assertTrue(isUnreadCompletion(ThreadKey("b", "same"), "turn-a", read))
        assertFalse(isUnreadCompletion(ThreadKey("a", "same"), "", read))
    }
    @Test fun persistedCompletionMarkersAreBoundedAndPreserveRecentEntries() {
        val entries = (0 until 2100).associate { ThreadKey("a", "thread-$it") to "turn-$it" }
        val restored = completionMarkers(completionMarkersJson(entries))
        assertEquals(2000, restored.size)
        assertEquals("turn-2099", restored[ThreadKey("a", "thread-2099")])
        assertFalse(restored.containsKey(ThreadKey("a", "thread-0")))
    }
}
