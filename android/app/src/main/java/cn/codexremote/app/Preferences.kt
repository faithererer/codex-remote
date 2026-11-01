package cn.codexremote.app

import org.json.JSONArray
import org.json.JSONObject

data class HomeSelection(val hostId: String = "", val attention: Boolean = false) {
    fun json(): String = JSONObject().put("hostId", hostId).put("attention", attention).toString()
    fun without(id: String) = if (hostId == id) copy(hostId = "") else this
    companion object {
        fun parse(value: String): HomeSelection = runCatching { JSONObject(value).let { HomeSelection(it.optString("hostId"), it.optBoolean("attention")) } }.getOrDefault(HomeSelection())
    }
}

fun modelOperation(catalog: JSONObject, model: String, effort: String): JSONObject {
    require(catalog.optJSONArray("models")?.objects()?.any { it.optString("id") == model && it.optJSONArray("efforts")?.let { a -> (0 until a.length()).any { a.optString(it) == effort } } == true } == true) { "请选择此电脑目录中的模型和思考强度" }
    return JSONObject().put("model", model).put("effort", effort).put("expectedModel", catalog.getString("model")).put("expectedEffort", catalog.optString("effort")).put("expectedProvider", catalog.getString("provider")).put("catalogInstanceId", catalog.getString("instanceId"))
}

fun completedAnswer(message: JSONObject): Boolean = message.optString("kind") == "agentMessage" && message.optString("phase") == "final_answer" && message.optString("turnStatus") == "completed"
fun editDraftKey(key: ThreadKey, turnId: String): String = "edit-draft-${key.hostId}-${key.threadId}-$turnId"
fun completionMarkers(value: String): Map<ThreadKey, String> = JSONArray(value).objects().associate { ThreadKey(it.getString("hostId"), it.getString("threadId")) to it.getString("turnId") }
fun completionMarkersJson(markers: Map<ThreadKey, String>): String = JSONArray(markers.entries.toList().takeLast(2000).map { (key, turn) -> JSONObject().put("hostId", key.hostId).put("threadId", key.threadId).put("turnId", turn) }).toString()
fun isUnreadCompletion(key: ThreadKey, turnId: String, read: Map<ThreadKey, String>): Boolean = turnId.isNotEmpty() && read[key] != turnId

fun editOperation(target: JSONObject, text: String): JSONObject {
    require(target.optBoolean("allowed")) { target.optString("reason", "最新消息暂不能编辑") }
    require(text.isNotBlank() && text.length <= 32000 && text.trim() != target.optString("text")) { "请输入修改后的消息" }
    return JSONObject().put("editTurnId", target.getString("turnId")).put("editFingerprint", target.getString("fingerprint")).put("text", text.trim()).put("editInstanceId", target.getString("instanceId"))
}
