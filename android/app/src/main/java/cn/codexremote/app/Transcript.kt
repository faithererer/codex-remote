package cn.codexremote.app

import org.json.JSONObject

data class TimelineEntry(val id: String, val message: JSONObject? = null, val executions: List<JSONObject> = emptyList())

fun isConversation(kind: String) = kind in setOf("user", "userMessage", "steeringUserMessage", "agentMessage", "history", "error")

fun timelineEntries(messages: List<JSONObject>): List<TimelineEntry> {
    val result = mutableListOf<TimelineEntry>()
    val executions = mutableListOf<JSONObject>()
    fun flush() {
        if (executions.isNotEmpty()) {
            result += TimelineEntry("execution/" + executions.first().optString("id").ifEmpty { result.size.toString() }, executions = executions.toList())
            executions.clear()
        }
    }
    messages.forEachIndexed { index, message ->
        val kind = message.optString("kind")
        if (kind == "reasoning") return@forEachIndexed
        if (isConversation(kind) || kind in setOf("fileChange", "diff") || (message.optJSONArray("images")?.length() ?: 0) > 0) {
            flush()
            result += TimelineEntry("message/" + message.optString("id").ifEmpty { index.toString() }, message)
        } else executions += message
    }
    flush()
    return result
}

fun latestThought(view: JSONObject?): String {
    if (view == null) return ""
    if (view.has("latestReasoning")) return view.optJSONObject("latestReasoning")?.optString("text").orEmpty()
    val messages = view.optJSONArray("messages")?.objects().orEmpty()
    val active = view.optString("activeTurnId")
    val lastInput = messages.indexOfLast { it.optString("kind") in setOf("user", "userMessage") }
    return messages.drop((lastInput + 1).coerceAtLeast(0)).lastOrNull {
        it.optString("kind") == "reasoning" && (active.isEmpty() || !it.has("turnId") || it.optString("turnId") == active)
    }?.optString("text")?.lineSequence()?.lastOrNull { it.isNotBlank() }.orEmpty()
}

fun thoughtPreview(text: String) = text.replace("**", "").replace(Regex("\\s+"), " ").trim()

data class ExecutionView(val title: String, val command: String, val output: String, val status: String, val failed: Boolean, val structured: Boolean)

fun executionView(message: JSONObject): ExecutionView {
    val kind = message.optString("kind")
    val code = if (message.has("exitCode") && !message.isNull("exitCode")) message.optInt("exitCode") else null
    val failed = message.optString("status") == "failed" || (code != null && code != 0)
    val status = when {
        code != null -> "退出码 $code"
        message.optString("status") == "inProgress" -> "运行中"
        failed -> "失败"
        message.optString("status") == "completed" -> "完成"
        else -> "记录"
    }
    val title = when (kind) {
        "commandExecution" -> "命令"
        "mcpToolCall" -> message.optString("toolName").ifBlank { "工具调用" }
        "fileChange" -> "文件修改"
        "diff" -> "修改差异"
        else -> "执行记录"
    }
    return ExecutionView(title, message.optString("command"), if (message.has("output")) message.optString("output") else message.optString("text"), status, failed, message.has("command"))
}
