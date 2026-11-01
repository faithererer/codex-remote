package cn.codexremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun ModelDialog(key: ThreadKey, host: HostState?, repo: Repository, enabled: Boolean, close: () -> Unit, submit: (JSONObject) -> Unit) {
    var catalog by remember(key) { mutableStateOf<JSONObject?>(null) }
    var error by remember(key) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var model by remember { mutableStateOf("") }; var effort by remember { mutableStateOf("") }
    LaunchedEffect(key, reload) {
        catalog = null; error = ""
        try { val result = repo.models(key); catalog = result; model = result.optString("model"); effort = result.optString("effort") }
        catch (e: Exception) { error = e.message ?: "模型目录读取失败" }
    }
    val choices = catalog?.optJSONArray("models")?.objects().orEmpty()
    val choice = choices.find { it.optString("id") == model }
    val efforts = choice?.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val current = catalog
    val unchanged = current?.optString("model") == model && current.optString("effort") == effort
    AlertDialog(onDismissRequest = close, title = { Text("切换模型") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(host?.host?.name.orEmpty(), fontWeight = FontWeight.SemiBold)
            if (current == null && error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("重新读取") } }
            choices.forEach { item ->
                val id = item.optString("id")
                OutlinedButton(onClick = {
                    model = id
                    val available = item.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    if (effort !in available) effort = item.optString("defaultEffort").takeIf { it in available } ?: available.firstOrNull().orEmpty()
                }, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
                    Text((if (model == id) "✓ " else "") + item.optString("name", id), fontSize = 13.sp)
                }
            }
            if (efforts.isNotEmpty()) {
                Text("思考强度", fontSize = 12.sp)
                // A wrapping layout stays usable on narrow screens and large system font sizes.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { efforts.forEach { value -> FilterChip(selected = effort == value, onClick = { effort = value }, enabled = enabled, label = { Text(value.ifBlank { "默认" }, fontSize = 11.sp) }) } }
            }
            Text(current?.optString("warning").orEmpty(), fontSize = 12.sp)
            if (current != null && host?.instance != current.optString("instanceId")) Text("电脑实例已改变，请关闭后重新选择。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { current?.let { submit(modelOperation(it, model, effort)) } }, enabled = enabled && current != null && host?.instance == current.optString("instanceId") && effort in efforts && !unchanged) { Text("应用到下一轮") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
fun EditMessageDialog(key: ThreadKey, target: JSONObject, view: JSONObject?, repo: Repository, enabled: Boolean, close: () -> Unit, submit: (JSONObject, () -> Unit) -> Unit) {
    val draftName = editDraftKey(key, target.optString("turnId"))
    var text by remember(draftName) { mutableStateOf(repo.store.get(draftName, target.optString("text"))) }
    val latest = view?.optJSONObject("editTarget")
    val fresh = view?.optString("instanceId") == target.optString("instanceId") && latest?.optString("fingerprint") == target.optString("fingerprint") && latest.optString("turnId") == target.optString("turnId")
    AlertDialog(onDismissRequest = close, title = { Text("编辑最新消息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("与电脑的编辑功能相同：撤回本轮对话并重新生成回复。已经修改的文件不会自动还原；不会新建聊天或自动中断任务。", fontSize = 12.sp)
            if (target.optString("text").isNotEmpty()) OutlinedTextField(text, { text = it; repo.store.put(draftName, it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp), maxLines = 12, label = { Text("修改指令") }, enabled = enabled)
            if (!fresh) Text("电脑上的最新消息已变化，请关闭后重新打开编辑。草稿保留。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            else if (latest?.optBoolean("allowed") != true) Text(latest?.optString("reason").orEmpty(), fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { if (latest != null) submit(editOperation(JSONObject(latest.toString()).put("instanceId", target.optString("instanceId")), text)) { repo.store.put(draftName, "") } }, enabled = enabled && fresh && latest?.optBoolean("allowed") == true && text.isNotBlank() && text.length <= 32000 && text.trim() != target.optString("text")) { Text("修改并重新生成") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
