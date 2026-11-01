package cn.codexremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NewThreadScreen(hosts: List<HostState>, repo: Repository, initialHostId: String, open: (ThreadKey) -> Unit, home: () -> Unit) {
    var hostId by rememberSaveable { mutableStateOf(initialHostId.ifEmpty { hosts.singleOrNull()?.host?.id.orEmpty() }) }
    var busy by remember { mutableStateOf(false) }
    val selected = hosts.firstOrNull { it.host.id == hostId }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("在哪里开始？", fontSize = 26.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        Text("选定电脑和工作位置，再发送第一条指令。", fontSize = 13.sp, color = Color(0xFF647168))
        Text("1  执行电脑", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173D31))
        hosts.forEach { h ->
            Surface(color = if (h.host.id == hostId) Color(0xFFE7EED9) else Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { hostId = h.host.id }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = h.host.id == hostId, onClick = { if (!busy) hostId = h.host.id }, enabled = !busy)
                    Column(Modifier.weight(1f)) { Text(h.host.name, fontWeight = FontWeight.SemiBold); Text(if (h.online) "在线 · 使用这台电脑的账号和设置" else "离线 · 可写草稿，不能创建", fontSize = 11.sp, color = Color(0xFF647168)) }
                    Icon(Icons.Outlined.Computer, null, tint = Color(0xFF173D31), modifier = Modifier.size(20.dp))
                }
            }
        }
        if (hosts.isEmpty()) Text("请先添加并连接一台电脑。", fontSize = 14.sp)
        if (selected != null) key(hostId) { NewThreadForm(selected, repo, busy, { if (busy) false else { busy = true; true } }, { busy = false }, open, home) }
        else if (hosts.isNotEmpty()) Text("先选择要执行任务的电脑，不会自动替你切换。", color = Color(0xFF647168), fontSize = 13.sp)
    }
}

@Composable
private fun NewThreadForm(host: HostState, repo: Repository, busy: Boolean, tryStart: () -> Boolean, finish: () -> Unit, open: (ThreadKey) -> Unit, home: () -> Unit) {
    var draft by remember { mutableStateOf(repo.creationDraft(host.host.id)) }
    var catalog by remember { mutableStateOf<ProjectCatalog?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun save(name: String, value: String) { draft = JSONObject(draft.toString()).put(name, value); repo.creationDraft(host.host.id, draft) }
    LaunchedEffect(host.host.id, host.instance, host.online, reload) {
        catalog = null; error = ""
        if (host.online && host.compatible) {
            loading = true
            try { catalog = repo.projects(host.host.id) } catch (e: Exception) { error = e.message ?: "无法读取创建位置" } finally { loading = false }
        }
    }
    val location = draft.optString("location", "default")
    val projectId = draft.optString("projectId")
    val selectedProject = catalog?.projects?.firstOrNull { it.optString("id") == projectId }
    Text("2  工作位置", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173D31))
    LocationOption("Codex 默认目录", "不归属项目，由这台电脑自动创建独立工作目录。", location == "default", !busy) { save("location", "default"); save("projectId", "") }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    catalog?.projects?.forEach { project ->
        LocationOption(project.optString("name", "未命名项目"), project.optString("path"), location == "project" && projectId == project.optString("id"), !busy) { save("projectId", project.optString("id")); save("location", "project") }
    }
    if (catalog != null && catalog!!.projects.isEmpty()) Text("这台电脑暂未登记项目。想在现有代码库开始，请先在电脑 Codex 中添加项目，再点刷新。", color = Color(0xFF647168), fontSize = 11.sp)
    if (location == "project" && catalog != null && selectedProject == null) Text("之前选择的项目已不可用，请重新选择工作位置。", color = Color(0xFFAC3529), fontSize = 12.sp)
    if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = Color(0xFFAC3529))
    TextButton(onClick = { reload++ }, enabled = !loading && !busy && host.online) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Text("刷新项目", fontSize = 12.sp) }
    Text("3  首条指令", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173D31))
    OutlinedTextField(draft.optString("title"), { if (it.length <= 120) save("title", it) }, label = { Text("会话标题（可选）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    OutlinedTextField(draft.optString("text"), { if (it.length <= 32000) save("text", it) }, placeholder = { Text("希望 Codex 做什么？") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 280.dp), shape = RoundedCornerShape(12.dp))
    Surface(color = Color(0xFFE9EEDF), shape = RoundedCornerShape(10.dp)) {
        Text("执行位置：${host.host.name} / ${if (location == "default") "Codex 默认目录" else selectedProject?.optString("name") ?: "请重新选项目"}\n使用电脑默认模型和权限；不新建 worktree、不切换分支。点击后会立即执行首条指令。", fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(12.dp))
    }
    Button(onClick = {
        val choice = catalog ?: return@Button
        if (!tryStart()) return@Button
        error = ""
        scope.launch {
            try { val key = repo.create(host.host.id, choice, draft); if (key != null) open(key) else home() }
            catch (e: Exception) { error = e.message ?: "未能确认新建结果，请核对电脑会话列表" }
            finally { finish() }
        }
    }, enabled = !busy && host.online && host.compatible && catalog != null && draft.optString("text").isNotBlank() && (location == "default" || selectedProject != null), modifier = Modifier.fillMaxWidth().height(50.dp)) {
        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)) }
        Text(if (busy) "正在创建，请勿重复提交…" else "在 ${host.host.name} 创建并发送")
    }
    Text("断线不会排队或改发到其他电脑。结果未确认时先核对会话列表，不会自动重新创建。", color = Color(0xFF647168), fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LocationOption(title: String, detail: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Surface(color = if (selected) Color(0xFFE7EED9) else Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = choose)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = choose, enabled = enabled)
            Column(Modifier.padding(end = 7.dp)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 11.sp, color = Color(0xFF647168)) }
        }
    }
}
