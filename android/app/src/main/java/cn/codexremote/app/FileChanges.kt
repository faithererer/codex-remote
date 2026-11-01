package cn.codexremote.app

import org.json.JSONArray
import org.json.JSONObject

data class ChangedFile(val path: String, val kind: String, val movePath: String = "", val diff: String = "", val truncated: Boolean = false) {
    val name: String get() = path.replace('\\', '/').substringAfterLast('/').ifBlank { "未提供文件名" }
    val label: String get() = when (kind) { "add", "create" -> "新增"; "delete", "remove" -> "删除"; "rename", "move" -> "重命名"; else -> if (movePath.isNotEmpty()) "移动 / 修改" else "修改" }
    private val counts by lazy { diffLines(diff).let { lines -> lines.count { it.kind == DiffKind.ADDED } to lines.count { it.kind == DiffKind.REMOVED } } }
    val additions: Int get() = counts.first
    val deletions: Int get() = counts.second
}

private fun diffText(value: Any?): String = when (value) { is String -> value; is JSONObject -> value.optString("text"); else -> "" }
private val unifiedHunk = Regex("(?m)^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@")

fun nativeFileDiff(text: String, kind: String): String {
    if (text.isEmpty() || kind !in setOf("add", "create", "delete", "remove") || unifiedHunk.containsMatchIn(text)) return text
    // Native add/delete records contain full file text rather than an update-style unified patch.
    val lines = text.removeSuffix("\n").split('\n')
    val added = kind in setOf("add", "create")
    val header = if (added) "@@ -0,0 +1,${lines.size} @@" else "@@ -1,${lines.size} +0,0 @@"
    return header + "\n" + lines.joinToString("\n") { (if (added) "+" else "-") + it }
}

fun changedFiles(message: JSONObject): List<ChangedFile> {
    val changes = message.optJSONArray("changes") ?: if (message.optString("kind") == "fileChange") runCatching { JSONArray(message.optString("text")) }.getOrNull() else null
    if (changes != null) return changes.objects().map { change ->
        val kind = change.opt("kind")
        val type = if (kind is JSONObject) kind.optString("type", "update") else kind?.toString() ?: "update"
        ChangedFile(change.optString("path"), type,
            change.optString("movePath", (kind as? JSONObject)?.optString("move_path").orEmpty()), nativeFileDiff(diffText(change.opt("diff")), type), change.optBoolean("truncated"))
    }
    return if (message.optString("kind") == "diff") unifiedFiles(message.optString("text"), message.optBoolean("truncated")) else emptyList()
}

private fun cleanDiffPath(raw: String): String {
    val path = raw.substringBefore('\t').trim().removeSurrounding("\"")
    return if (path.startsWith("a/") || path.startsWith("b/")) path.drop(2) else path
}

fun unifiedFiles(text: String, truncated: Boolean = false): List<ChangedFile> {
    if (text.isBlank()) return emptyList()
    val sections = mutableListOf<List<String>>()
    var lines = mutableListOf<String>()
    val hasGitHeaders = text.lineSequence().any { it.startsWith("diff --git ") }
    text.lineSequence().forEach { line ->
        if ((line.startsWith("diff --git ") || (!hasGitHeaders && line.startsWith("--- ") && lines.any { it.startsWith("+++ ") })) && lines.isNotEmpty()) {
            sections += lines.toList(); lines = mutableListOf()
        }
        lines += line
    }
    if (lines.isNotEmpty()) sections += lines
    return sections.mapIndexed { index, part ->
        val before = part.firstOrNull { it.startsWith("--- ") }?.drop(4)?.let(::cleanDiffPath)
        val after = part.firstOrNull { it.startsWith("+++ ") }?.drop(4)?.let(::cleanDiffPath)
        val renameTo = part.firstOrNull { it.startsWith("rename to ") }?.removePrefix("rename to ")
        val renameFrom = part.firstOrNull { it.startsWith("rename from ") }?.removePrefix("rename from ")
        val git = part.firstOrNull { it.startsWith("diff --git ") }?.let { Regex(" b/(.+)$").find(it)?.groupValues?.get(1)?.removeSurrounding("\"") }
        val path = renameTo ?: after?.takeUnless { it == "/dev/null" } ?: before?.takeUnless { it == "/dev/null" } ?: git ?: "本轮差异"
        val kind = when { before == "/dev/null" || part.any { it.startsWith("new file mode ") } -> "add"; after == "/dev/null" || part.any { it.startsWith("deleted file mode ") } -> "delete"; renameTo != null -> "rename"; else -> "update" }
        ChangedFile(path, kind, renameFrom.orEmpty(), part.joinToString("\n"), truncated && index == sections.lastIndex)
    }
}

fun latestChangedFiles(view: JSONObject?): List<ChangedFile> {
    val messages = view?.optJSONArray("messages")?.objects().orEmpty()
    val lastTurn = view?.optString("activeTurnId").orEmpty().ifEmpty { messages.lastOrNull()?.optString("turnId").orEmpty() }
    val current = if (lastTurn.isEmpty()) messages.drop((messages.indexOfLast { it.optString("kind") == "user" } + 1).coerceAtLeast(0)) else messages.filter { it.optString("turnId") == lastTurn }
    // The aggregate native turn diff is authoritative when present; do not double-count individual patches.
    val summary = current.lastOrNull { it.optString("kind") == "diff" }?.let(::changedFiles)
    return summary?.takeIf { it.isNotEmpty() } ?: current.filter { it.optString("kind") == "fileChange" }.flatMap(::changedFiles)
        .groupBy { it.path }.values.map { patches -> patches.last().copy(diff = patches.joinToString("\n") { it.diff }, truncated = patches.any { it.truncated }) }
}

enum class DiffKind { HEADER, HUNK, ADDED, REMOVED, CONTEXT }
data class DiffLine(val text: String, val kind: DiffKind, val old: Int? = null, val new: Int? = null)
fun diffLines(text: String): List<DiffLine> {
    var old: Int? = null; var new: Int? = null
    return text.lineSequence().map { line ->
        when {
            line.startsWith("diff --git ") -> { old = null; new = null; DiffLine(line, DiffKind.HEADER) }
            line.startsWith("@@") -> {
                val match = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(line)
                old = match?.groupValues?.get(1)?.toIntOrNull(); new = match?.groupValues?.get(2)?.toIntOrNull()
                DiffLine(line, DiffKind.HUNK)
            }
            old == null || new == null -> DiffLine(line, DiffKind.HEADER)
            line.startsWith('+') -> DiffLine(line, DiffKind.ADDED, new = new).also { new = new?.plus(1) }
            line.startsWith('-') -> DiffLine(line, DiffKind.REMOVED, old = old).also { old = old?.plus(1) }
            line.startsWith(' ') -> DiffLine(line, DiffKind.CONTEXT, old, new).also { old = old?.plus(1); new = new?.plus(1) }
            else -> DiffLine(line, DiffKind.HEADER)
        }
    }.toList()
}
