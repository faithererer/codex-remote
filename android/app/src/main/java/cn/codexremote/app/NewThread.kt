package cn.codexremote.app

import org.json.JSONObject
import java.util.UUID

fun newThreadOperation(hostId: String, instanceId: String, draft: JSONObject, projects: List<JSONObject>, operationId: String = UUID.randomUUID().toString()): JSONObject {
    require(hostId.isNotBlank() && instanceId.isNotBlank()) { "电脑身份或实例无效" }
    val text = draft.optString("text").trim()
    val title = draft.optString("title").trim()
    require(text.length in 1..32000) { "首条指令必须为 1 至 32000 字符" }
    require(title.length <= 120) { "标题最多 120 字符" }
    val location = draft.optString("location", "default")
    val project = draft.optString("projectId")
    require((location == "default" && project.isEmpty()) || (location == "project" && project.isNotEmpty() && projects.any { it.optString("id") == project })) { "请重新选择这台电脑上的项目" }
    UUID.fromString(operationId)
    return JSONObject().put("hostId", hostId).put("instanceId", instanceId).put("threadId", "").put("operationId", operationId).put("type", "create")
        .put("text", text).put("title", title).put("location", location).put("projectId", project)
}
