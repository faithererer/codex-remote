package cn.codexremote.app

import org.json.JSONObject
import java.util.UUID

fun forkOperation(key: ThreadKey, instanceId: String, context: JSONObject, environment: String, operationId: String = UUID.randomUUID().toString()): JSONObject {
    require(key.hostId.isNotBlank() && instanceId.isNotBlank()) { "电脑身份或实例无效" }
    UUID.fromString(key.threadId); UUID.fromString(operationId)
    require(context.optString("hostId") == key.hostId && context.optString("instanceId") == instanceId && context.optString("threadId") == key.threadId) { "Fork 来源或电脑实例已变化，请重新读取" }
    require(context.optBoolean("canFork") && context.optString("activeTurnId").isEmpty()) { context.optString("reason", "请等待原任务结束后再 Fork") }
    require(environment == "same-directory" || environment == "worktree" && context.optBoolean("canWorktree")) { "工作位置不可用，请重新选择" }
    return JSONObject().put("hostId", key.hostId).put("instanceId", instanceId).put("threadId", key.threadId)
        .put("turnId", "").put("latestTurnId", context.optString("latestTurnId")).put("operationId", operationId).put("type", "fork").put("environment", environment)
}

fun readyForkThread(result: JSONObject): String? {
    val id = result.optString("threadId")
    if (result.optString("status") != "created" || result.optJSONObject("synchronization")?.optString("status") != "complete") return null
    return id.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
}
