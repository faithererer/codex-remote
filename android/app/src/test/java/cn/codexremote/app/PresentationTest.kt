package cn.codexremote.app

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    private fun message(kind: String, text: String = "") = JSONObject().put("kind", kind).put("text", text)
    @Test fun structuredChangesRetainRealPathsAndDiffs() {
        val changes = JSONArray("""[{"path":"C:\\repo\\a.kt","kind":"update","diff":"@@ -1 +1 @@\n-old\n+new","movePath":"C:\\repo\\b.kt"}]""")
        val files = changedFiles(message("fileChange").put("changes", changes))
        assertEquals("a.kt", files.single().name); assertEquals("C:\\repo\\a.kt", files.single().path)
        assertEquals(1, files.single().additions); assertEquals(1, files.single().deletions)
    }
    @Test fun legacyFileChangesSupportObjectKindsAndDiffs() {
        val files = changedFiles(message("fileChange", """[{"path":"new.txt","kind":{"type":"add"},"diff":{"text":"@@ -0,0 +1 @@\n+added"}}]"""))
        assertEquals("新增", files.single().label); assertTrue(files.single().diff.contains("+added"))
    }
    @Test fun nativeAddedAndDeletedFilesContainFullTextRatherThanHunks() {
        val added = changedFiles(message("fileChange").put("changes", JSONArray("""[{"path":"new.kt","kind":"add","diff":"first\nsecond\n"}]"""))).single()
        assertEquals(2, added.additions); assertEquals(0, added.deletions); assertTrue(added.diff.contains("+first\n+second"))
        val deleted = changedFiles(message("fileChange").put("changes", JSONArray("""[{"path":"old.kt","kind":"delete","diff":"old\n"}]"""))).single()
        assertEquals(1, deleted.deletions); assertEquals(0, deleted.additions)
    }
    @Test fun unifiedDiffSplitsFilesIncludingSpacesAndDeletion() {
        val files = unifiedFiles("diff --git a/a name.txt b/a name.txt\n--- a/a name.txt\n+++ b/a name.txt\n@@ -1 +1 @@\n-old\n+new\ndiff --git a/deleted.txt b/deleted.txt\n--- a/deleted.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-deleted")
        assertEquals(listOf("a name.txt", "deleted.txt"), files.map { it.path }); assertEquals("delete", files[1].kind)
    }
    @Test fun unifiedWithoutGitHeadersStillSplitsFiles() {
        val files = unifiedFiles("--- a/one\n+++ b/one\n@@ -1 +1 @@\n-a\n+b\n--- a/two\n+++ b/two\n@@ -0,0 +1 @@\n+second")
        assertEquals(listOf("one", "two"), files.map { it.path })
    }
    @Test fun numberedDiffTracksAddedRemovedAndContextLines() {
        val lines = diffLines("@@ -10,2 +20,2 @@\n-old\n+new\n same\n\\ No newline at end of file")
        assertEquals(10, lines[1].old); assertNull(lines[1].new); assertEquals(20, lines[2].new)
        assertEquals(11, lines[3].old); assertEquals(21, lines[3].new); assertEquals(DiffKind.HEADER, lines[4].kind)
    }
    @Test fun codeContainingTripleSignsIsNotMistakenForAFileHeader() {
        val lines = diffLines("--- a/a\n+++ b/a\n@@ -1 +1 @@\n--- text\n+++ text")
        assertEquals(DiffKind.REMOVED, lines[3].kind); assertEquals(DiffKind.ADDED, lines[4].kind)
    }
    @Test fun latestFilesNeverLeakFromPreviousTurnOrDoubleCountSummary() {
        val old = message("fileChange").put("turnId", "old").put("changes", JSONArray("""[{"path":"old.kt","diff":""}]"""))
        val current = message("fileChange").put("turnId", "new").put("changes", JSONArray("""[{"path":"a.kt","diff":"@@ -1 +1 @@\n-a\n+b"}]"""))
        val view = JSONObject().put("activeTurnId", "new").put("messages", JSONArray(listOf(old, current)))
        assertEquals(listOf("a.kt"), latestChangedFiles(view).map { it.path })
        view.getJSONArray("messages").put(message("diff", "--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-a\n+b").put("turnId", "new"))
        assertEquals(1, latestChangedFiles(view).size)
        view.put("activeTurnId", "another"); assertTrue(latestChangedFiles(view).isEmpty())
    }
    @Test fun fileChangesRemainVisibleOutsideCollapsedCommands() {
        val rows = timelineEntries(listOf(message("commandExecution").put("id", "c"), message("fileChange").put("id", "f"), message("agentMessage").put("id", "a")))
        assertEquals("fileChange", rows[1].message?.optString("kind")); assertEquals(1, rows[0].executions.size)
    }
    @Test fun markdownParsesHeadingsListsCodeLinksAndTables() {
        val document = MarkdownParser.parse("# 标题\n\n**加粗** 和 `code`\n\n1. first\n2. second\n\n```kotlin\nval x = 1\n```\n\n[link](https://example.com)\n\n| A | B |\n|---|---|\n| 1 | 2 |")
        assertTrue(document.children().any { it is Heading }); assertTrue(document.children().any { it is OrderedList })
        assertTrue(document.children().any { it is FencedCodeBlock }); assertTrue(document.children().any { it is TableBlock })
    }
    @Test fun streamingIncompleteMarkdownDoesNotCrash() {
        listOf("**partial", "```kotlin\nval", "[link](", "| header |", "<script>alert(1)</script>").forEach { assertNotNull(MarkdownParser.parse(it)) }
    }
    @Test fun onlyExplicitSafeWebLinksCanOpen() {
        assertTrue(safeWebLink("https://example.com/path")); assertTrue(safeWebLink("http://example.com"))
        listOf("javascript:alert(1)", "file:///C:/secret", "intent://app", "https://user:secret@example.com", "//example.com", "data:text/html,anything").forEach { assertFalse(safeWebLink(it)) }
    }
    @Test fun defaultCreationKeepsHostIdentityAndDoesNotIncludeArbitraryOverrides() {
        val operation = newThreadOperation("pc-A", "backend-A", JSONObject().put("text", "Task").put("model", "injected").put("cwd", "C:/other"), emptyList())
        assertEquals("pc-A", operation.getString("hostId")); assertEquals("backend-A", operation.getString("instanceId"))
        assertEquals("", operation.getString("threadId")); assertEquals("default", operation.getString("location")); assertFalse(operation.has("model")); assertFalse(operation.has("cwd"))
    }
    @Test fun projectCreationRequiresTheChosenHostsCatalog() {
        val draft = JSONObject().put("text", "Task").put("location", "project").put("projectId", "repo-A")
        assertEquals("repo-A", newThreadOperation("pc-A", "a", draft, listOf(JSONObject().put("id", "repo-A"))).getString("projectId"))
        assertThrows(IllegalArgumentException::class.java) { newThreadOperation("pc-B", "b", draft, listOf(JSONObject().put("id", "repo-B"))) }
        assertThrows(IllegalArgumentException::class.java) { newThreadOperation("pc-A", "a", JSONObject().put("text", " "), emptyList()) }
    }
}
