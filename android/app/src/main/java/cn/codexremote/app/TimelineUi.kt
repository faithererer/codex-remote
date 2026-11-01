package cn.codexremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Green = Color(0xFF173D31)
private val Muted = Color(0xFF647168)
private val Red = Color(0xFFAC3529)

@Composable
fun ConversationTimeline(thread: ThreadKey, view: JSONObject?, modifier: Modifier = Modifier, latestRequest: Int = 0, requestCard: @Composable (JSONObject) -> Unit) {
    val messages = view?.optJSONArray("messages")?.objects().orEmpty()
    val entries = remember(view) { timelineEntries(messages) }
    val requests = view?.optJSONArray("requests")?.objects().orEmpty()
    val state = remember(thread) { LazyListState() }
    val scope = rememberCoroutineScope()
    var following by remember(thread) { mutableStateOf(true) }
    var positioned by remember(thread) { mutableStateOf(false) }
    var autoScrolling by remember(thread) { mutableStateOf(false) }
    val atLatest by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset <= 8 } }
    val revision = messages.lastOrNull { it.optString("kind") != "reasoning" }?.toString()?.hashCode()

    LaunchedEffect(thread, latestRequest) {
        following = true
        autoScrolling = true
        try { state.scrollToItem(0) } finally { autoScrolling = false }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to atLatest }.collect { (scrolling, bottom) ->
            if (scrolling && !autoScrolling) following = bottom
        }
    }
    LaunchedEffect(thread, view != null, entries.size, revision, requests.toString()) {
        if (view != null && (!positioned || following) && !state.isScrollInProgress) {
            positioned = true
            autoScrolling = true
            try { state.scrollToItem(0) } finally { autoScrolling = false }
        }
    }
    Box(modifier) {
        // The stable tail anchor makes first load and growing streamed text start at the latest content.
        LazyColumn(state = state, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag("conversation-timeline"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item(key = "tail") { Spacer(Modifier.height(1.dp).testTag("timeline-tail")) }
            items(requests.asReversed(), key = { "request/" + it.optString("id") }) { requestCard(it) }
            items(entries.asReversed(), key = { it.id }) { entry ->
                if (entry.message != null) {
                    if (entry.message.optString("kind") in setOf("fileChange", "diff")) FileChangeCard(entry.message) else ConversationMessage(entry.message)
                } else ExecutionGroup(entry)
            }
            if (view == null) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取原桌面会话…", fontSize = 12.sp) }
            if (view?.optBoolean("hasOlder") == true) item(key = "older") { Text("这里只显示最近记录，完整历史保留在电脑。", fontSize = 11.sp, color = Muted) }
        }
        if (!atLatest && positioned) {
            SmallFloatingActionButton(onClick = {
                following = true
                scope.launch { autoScrolling = true; try { state.animateScrollToItem(0) } finally { autoScrolling = false } }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("jump-to-latest"), containerColor = Green, contentColor = Color.White) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("回到最新", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ConversationMessage(message: JSONObject) {
    val kind = message.optString("kind")
    val user = kind in setOf("user", "userMessage", "steeringUserMessage")
    val text = message.optString("text")
    var detail by remember { mutableStateOf(false) }
    val longInput = user && (text.length > 600 || text.count { it == '\n' } > 7)
    Column(Modifier.fillMaxWidth().padding(start = if (user) 20.dp else 0.dp).background(if (user) Green else Color.White, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(if (user) "你" else if (kind == "error") "执行错误" else "Codex", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (user) Color(0xFFD2F56D) else if (kind == "error") Red else Muted)
        if (!user && kind != "error") MarkdownText(text, Modifier.padding(top = 5.dp))
        else SelectionContainer { Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = if (user) Color.White else Color(0xFF1D2A23), modifier = Modifier.padding(top = 4.dp), maxLines = if (longInput) 7 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) }
        if (longInput) Text("查看完整消息", fontSize = 11.sp, color = Color(0xFFD2F56D), modifier = Modifier.clickable { detail = true }.padding(top = 5.dp))
    }
    if (detail) TextDetail("你的消息", text) { detail = false }
}

@Composable
private fun ExecutionGroup(entry: TimelineEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    val latest = executionView(entry.executions.last())
    Surface(color = Color(0xFFE8EDE5), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp), tint = Green)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("执行记录 · ${entry.executions.size} 项", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Green)
                    Text(latest.command.ifBlank { latest.title }.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Muted)
                }
                Text(latest.status, fontSize = 10.sp, color = if (latest.failed) Red else Muted)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开执行记录", Modifier.size(18.dp), tint = Green)
            }
            if (expanded) {
                entry.executions.takeLast(5).forEach { message -> key(message.optString("id")) { ExecutionItem(message) } }
                if (entry.executions.size > 5) TextButton(onClick = { all = true }) { Text("查看全部 ${entry.executions.size} 项记录", fontSize = 12.sp) }
            }
        }
    }
    if (all) Dialog(onDismissRequest = { all = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(12.dp), shape = RoundedCornerShape(18.dp)) {
            Column { DetailHeading("全部执行记录") { all = false }; LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(10.dp)) { items(entry.executions) { ExecutionItem(it) } } }
        }
    }
}

@Composable
private fun ExecutionItem(message: JSONObject) {
    val data = executionView(message)
    var command by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(data.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(data.status, fontSize = 10.sp, color = if (data.failed) Red else Muted) }
        if (data.command.isNotBlank()) {
            Text(data.command, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFD8EAD1), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(Green, RoundedCornerShape(5.dp)).clickable { command = true }.padding(8.dp))
        }
        val label = if (!data.structured && message.optString("kind") == "commandExecution") "原始记录（电脑端待更新）" else if (data.command.isNotEmpty()) "执行结果" else "详情 / 结果"
        Row(Modifier.fillMaxWidth().clickable { output = true }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Subject, null, Modifier.size(14.dp), tint = Muted)
            Text("$label · ${if (data.output.isEmpty()) "暂无输出" else "${data.output.lineSequence().count()} 行"}", fontSize = 11.sp, color = if (data.failed) Red else Muted, modifier = Modifier.weight(1f).padding(start = 5.dp))
            Icon(Icons.Outlined.OpenInFull, "查看执行结果", Modifier.size(14.dp), tint = Muted)
        }
    }
    if (command) TextDetail("命令", data.command) { command = false }
    if (output) TextDetail("${data.title} · ${data.status} · 执行结果", data.output.ifEmpty { "暂无输出" }) { output = false }
}

@Composable
fun LatestThought(view: JSONObject?, active: Boolean, enabled: Boolean, stop: () -> Unit) {
    val thought = latestThought(view)
    var detail by remember { mutableStateOf(false) }
    if (thought.isNotBlank() || active) Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).testTag("latest-thought"), verticalAlignment = Alignment.CenterVertically) {
        if (active) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = Green) else Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = Muted)
        Text(thoughtPreview(thought).ifBlank { "任务运行中…" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 7.dp).clickable(enabled = thought.isNotEmpty()) { detail = true })
        if (active) IconButton(onClick = stop, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.StopCircle, "中断此轮", Modifier.size(19.dp)) }
    }
    if (detail) TextDetail(if (active) "正在思考 · 最新摘要" else "最新思考摘要", thought, monospace = false) { detail = false }
}

@Composable
private fun DetailHeading(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭") } }
}

@Composable
fun TextDetail(title: String, text: String, monospace: Boolean = true, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(12.dp), shape = RoundedCornerShape(18.dp)) {
            Column { DetailHeading(title, close); HorizontalDivider(); SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { Text(text, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default, fontSize = 12.sp, lineHeight = 19.sp) } }
        }
    }
}
