package cn.codexremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ForkDialog(key: ThreadKey, host: HostState?, title: String, repo: Repository, open: (ThreadKey) -> Unit, home: () -> Unit, close: () -> Unit) {
    var context by remember(key) { mutableStateOf<JSONObject?>(null) }
    var result by remember(key) { mutableStateOf(repo.lastFork(key).takeIf { it.length() > 0 }) }
    var environment by remember(key) { mutableStateOf("same-directory") }
    var busy by remember(key) { mutableStateOf(false) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf("") }
    var reload by remember(key) { mutableIntStateOf(0) }
    var resetConfirm by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key, host?.instance, host?.online, reload) {
        context = null; error = ""
        if (host?.online == true && host.compatible) {
            loading = true
            try { context = repo.forkContext(key) }
            catch (e: Exception) { error = e.message ?: "无法读取来源。请先在电脑打开原会话并刷新。" }
            finally { loading = false }
        }
    }
    val ready = result?.let(::readyForkThread)
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Fork 当前会话", fontSize = 20.sp) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${host?.host?.name ?: "电脑已移除"} / ${title.ifBlank { "当前会话" }}", fontSize = 13.sp)
            Text("保留对话历史，创建一份独立聊天。不会自动发送新指令，也不会中断原任务。新聊天仍在这台电脑执行。", fontSize = 12.sp)
            if (result != null) {
                Text(when { ready != null -> "Fork 已完成，可打开新会话。"; result!!.optString("status") == "queued" -> "已提交：电脑正在创建新工作树。准备完成后请从任务列表打开，不要重复 Fork。"; else -> "Fork 已创建，但历史同步或环境设置未确认完成。请先在电脑检查并修复，不要再次 Fork 或发送指令。" }, fontSize = 13.sp)
                SelectionContainer { Text(result!!.optString("threadId").takeIf { it != "null" }.orEmpty().ifEmpty { result!!.optString("clientThreadId") }, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                if (ready == null) TextButton(onClick = { resetConfirm = true }, enabled = !busy) { Text("我已在电脑核对上次结果…", fontSize = 11.sp) }
            } else {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val canFork = context?.optBoolean("canFork") == true && host?.online == true && host.compatible
                Row {
                    RadioButton(environment == "same-directory", onClick = { environment = "same-directory" }, enabled = canFork && !busy)
                    Column(Modifier.padding(top = 8.dp)) { Text("当前工作区（原目录）", fontSize = 13.sp); Text("共用实际文件；两边修改会相互可见。", fontSize = 11.sp) }
                }
                Row {
                    RadioButton(environment == "worktree", onClick = { environment = "worktree" }, enabled = canFork && context?.optBoolean("canWorktree") == true && !busy)
                    Column(Modifier.padding(top = 8.dp)) { Text("新工作树（worktree）", fontSize = 13.sp); Text("从当前工作区状态建立隔离副本，需 Git 仓库。", fontSize = 11.sp) }
                }
                context?.optString("cwd")?.takeIf { it.isNotEmpty() }?.let { path -> SelectionContainer { Text(path, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } }
                if (context != null && !context!!.optBoolean("canWorktree")) Text("${if (!canFork) context!!.optString("reason") else "当前目录不是已确认的 Git 仓库，不能新建工作树。"}", fontSize = 12.sp, color = Color(0xFF9B4D1A))
                if (host?.online != true) Text("电脑离线，不排队创建，也不会改发到其他电脑。", fontSize = 12.sp)
                if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = Color(0xFFAC3529))
                TextButton(onClick = { reload++ }, enabled = !busy && !loading && host?.online == true) { Text("刷新来源状态", fontSize = 12.sp) }
            }
        }
    }, confirmButton = {
        when {
            ready != null -> TextButton(onClick = { repo.clearForkResult(key); open(ThreadKey(key.hostId, ready)) }) { Text("打开新会话") }
            result != null -> TextButton(onClick = home) { Text("查看任务列表") }
            else -> Button(onClick = {
                val source = context ?: return@Button
                if (busy) return@Button
                busy = true; error = ""
                scope.launch {
                    try {
                        val created = repo.fork(key, source, environment); result = created
                        readyForkThread(created)?.let { id -> repo.clearForkResult(key); open(ThreadKey(key.hostId, id)) }
                    } catch (e: Exception) { error = e.message ?: "结果未确认，请先核对电脑，不要重复提交" }
                    finally { busy = false }
                }
            }, enabled = !busy && !loading && host?.online == true && host.compatible && context?.optBoolean("canFork") == true && (environment == "same-directory" || context?.optBoolean("canWorktree") == true)) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "正在 Fork…" else "创建 Fork")
            }
        }
    }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("关闭") } })
    if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text("已核对电脑结果？") }, text = { Text("只有确认上次工作树设置已完成或失败并已处理，才清除此提示。清除不会撤销已有 Fork，也不会自动再次创建。") }, confirmButton = { TextButton(onClick = { repo.clearForkResult(key); result = null; resetConfirm = false; reload++ }) { Text("已核对，清除提示") } }, dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("返回") } })
}
