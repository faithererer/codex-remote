package cn.codexremote.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Forest = Color(0xFF173D31)
private val Lime = Color(0xFFD2F56D)
private val Paper = Color(0xFFF3F5EF)
private val Ink = Color(0xFF1D2A23)

class MainActivity : ComponentActivity() {
    private val repository get() = (application as RemoteApplication).repository
    private val incoming = mutableStateOf<ThreadKey?>(null)
    private var scanned by mutableStateOf<String?>(null)
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { scanned = it } }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) beginMonitor() else repository.message.value = "通知权限未开启，后台事件可能无法提醒你" }
    private fun beginMonitor() { startForegroundService(Intent(this, MonitorService::class.java)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); route(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, secondary = Forest, secondaryContainer = Lime, onSecondaryContainer = Forest, background = Paper, surface = Paper, onSurface = Ink, outline = Color(0xFF819084)), typography = Typography(titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold), headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold))) {
                Console(repository, incoming.value, { incoming.value = null }, scanned, { scanned = null }, { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑桥接窗口的配对码").setBeepEnabled(false).setOrientationLocked(false)) }, {
                    if (repository.monitoring.value) { stopService(Intent(this, MonitorService::class.java)); repository.monitor(false) }
                    else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else beginMonitor()
                })
            }
        }
    }
    private fun route(intent: Intent?) { val host = intent?.getStringExtra("hostId"); val thread = intent?.getStringExtra("threadId"); if (!host.isNullOrEmpty() && !thread.isNullOrEmpty()) incoming.value = ThreadKey(host, thread) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); route(intent) }
    override fun onStart() { super.onStart(); repository.foreground(true) }
    override fun onStop() { repository.foreground(false); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Console(repo: Repository, incoming: ThreadKey?, consumeIncoming: () -> Unit, scanned: String?, consumeScan: () -> Unit, scan: () -> Unit, monitor: () -> Unit) {
    val hosts by repo.states.collectAsState(); val background by repo.monitoring.collectAsState(); val message by repo.message.collectAsState()
    val histories by repo.histories.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }; var selected by remember { mutableStateOf<ThreadKey?>(null) }
    var createHost by rememberSaveable { mutableStateOf("") }
    var forkKey by remember { mutableStateOf<ThreadKey?>(null) }
    var pairingText by remember { mutableStateOf("") }; var pairing by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { if (message.isNotEmpty()) { snackbar.showSnackbar(message); repo.message.value = "" } }
    LaunchedEffect(incoming) { if (incoming != null && hosts.containsKey(incoming.hostId)) { selected = incoming; screen = "thread"; consumeIncoming() } }
    LaunchedEffect(scanned) { if (scanned != null) { pairingText = scanned; screen = "pair"; consumeScan() } }
    BackHandler(screen != "home") { screen = "home"; selected = null }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Paper, topBar = {
        TopAppBar(expandedHeight = 56.dp, title = {
            if (screen == "thread") Column {
                Text(selected?.let { histories[it]?.optString("title") }?.ifBlank { "会话" } ?: "会话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hosts[selected?.hostId]?.host?.name ?: "电脑已移除", fontSize = 11.sp, color = Forest, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(if (screen == "home") "随行" else if (screen == "hosts") "我的电脑" else if (screen == "create") "新建会话" else "连接新电脑", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        },
            navigationIcon = { if (screen != "home") IconButton(onClick = { screen = "home"; selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { if (screen == "thread" && selected != null) IconButton(onClick = { forkKey = selected }) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Fork 当前会话") }; if (hosts.isNotEmpty() && screen != "create") IconButton(onClick = { createHost = selected?.hostId.orEmpty(); screen = "create" }) { Icon(Icons.Outlined.AddComment, "新建会话") }; IconButton(onClick = { repo.refreshAll() }) { Icon(Icons.Outlined.Refresh, "刷新") }; IconButton(onClick = { screen = "hosts" }) { Icon(Icons.Outlined.Dns, "电脑管理") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                "pair" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("一台电脑，一份信任。", style = MaterialTheme.typography.headlineLarge)
                    Text("电脑运行 CodexRemote.exe，手机与电脑都连接 Tailscale。扫码后，还需要在电脑上确认。", color = Color(0xFF56645A))
                    Text("若手机正在使用 Clash 等 VPN，请先切换到 Tailscale；仅安装但未连接是不够的。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { val launch = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn"); if (launch != null) context.startActivity(launch) else repo.message.value = "请先安装并登录 Tailscale" }, enabled = !pairing) { Text("打开 Tailscale") }
                    FilledTonalButton(onClick = scan, enabled = !pairing, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("扫描电脑配对码") }
                    OutlinedTextField(value = pairingText, onValueChange = { pairingText = it }, label = { Text("也可以粘贴电脑复制的配对码") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp), enabled = !pairing)
                    Button(onClick = { scope.launch { pairing = true; try { val name = repo.pair(pairingText); pairingText = ""; repo.message.value = "已连接 $name"; screen = "home" } catch (e: Exception) { repo.message.value = connectionMessage(e) } finally { pairing = false } } }, enabled = !pairing && pairingText.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { if (pairing) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }; Text(if (pairing) "请在电脑上确认…" else "核对并配对") }
                    Text("配对码 5 分钟有效，只能使用一次。证书不匹配时会拒绝连接；手机不保存模型账号密钥。", style = MaterialTheme.typography.bodySmall)
                }
                "hosts" -> HostsScreen(hosts.values.toList(), repo, { screen = "pair" })
                "create" -> NewThreadScreen(hosts.values.filter { it.host.followed }, repo, createHost, { key -> selected = key; screen = "thread" }, { screen = "home" })
                "thread" -> selected?.let { key -> ThreadScreen(key, repo, hosts[key.hostId]) }
                else -> HomeScreen(hosts.values.toList(), repo, background, monitor, { screen = "pair" }, { id -> createHost = id; screen = "create" }, { key -> selected = key; screen = "thread" })
            }
        }
    }
    forkKey?.let { key -> key(key) { ForkDialog(key, hosts[key.hostId], histories[key]?.optString("title").orEmpty(), repo, { child -> forkKey = null; selected = child; screen = "thread" }, { forkKey = null; screen = "home" }, { forkKey = null }) } }
}

@Composable
private fun HomeScreen(hosts: List<HostState>, repo: Repository, background: Boolean, monitor: () -> Unit, add: () -> Unit, create: (String) -> Unit, open: (ThreadKey) -> Unit) {
    val selection by repo.homeSelection.collectAsState(); val unread by repo.unreadCompletions.collectAsState()
    val filter = selection.hostId; val attention = selection.attention
    val followed = hosts.filter { it.host.followed }
    val tasks = followed.filter { filter.isEmpty() || filter == it.host.id }.flatMap { h -> h.threads.map { h to it } }.filter { !attention || it.second.optString("status") in listOf("active", "needsInput") }.sortedByDescending { it.second.optString("updatedAt") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (hosts.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Forest, Color(0xFF305642))), RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${followed.count { it.online }} 台在线 · ${followed.sumOf { h -> h.threads.count { it.optString("status") == "active" } }} 个任务运行中", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(if (background) "后台监控已开启" else "离开应用后继续关注", fontSize = 11.sp, color = Color(0xFFD4DFD4))
                }
                Switch(checked = background, onCheckedChange = { monitor() }, colors = SwitchDefaults.colors(checkedTrackColor = Lime, checkedThumbColor = Forest))
            } else Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Forest, Color(0xFF305642))), RoundedCornerShape(24.dp)).padding(22.dp)) {
                Text("CODEX  /  PRIVATE LINK", fontFamily = FontFamily.Monospace, color = Lime, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp)); Text("电脑在跑，\n你不必在场。", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Color.White, lineHeight = 39.sp)
                Spacer(Modifier.height(16.dp)); Text("${followed.count { it.online }} 台在线  ·  ${followed.sumOf { h -> h.threads.count { it.optString("status") == "active" } }} 个任务运行中", color = Color(0xFFD4DFD4))
                Spacer(Modifier.height(14.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text(if (background) "后台监控已开启" else "离开应用后继续关注", color = Color.White, modifier = Modifier.weight(1f)); Switch(checked = background, onCheckedChange = { monitor() }, colors = SwitchDefaults.colors(checkedTrackColor = Lime, checkedThumbColor = Forest)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter.isEmpty(), onClick = { repo.selectHome(selection.copy(hostId = "")) }, label = { Text("全部关注") })
                followed.forEach { h -> FilterChip(selected = filter == h.host.id, onClick = { repo.selectHome(selection.copy(hostId = h.host.id)) }, label = { Text((if (h.online) "● " else "○ ") + h.host.name) }) }
                AssistChip(onClick = add, label = { Text("添加") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) })
            }
        }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("任务看板", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (followed.isNotEmpty()) FilledTonalButton(onClick = { create(filter) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("新建会话", fontSize = 12.sp) } } }
        if (tasks.isNotEmpty() || attention) item { FilterChip(selected = attention, onClick = { repo.selectHome(selection.copy(attention = !attention)) }, label = { Text("只看运行 / 待处理") }) }
        followed.filter { !it.online }.forEach { h -> item(key = "offline-${h.host.id}") { Surface(color = Color(0xFFFFE9CF), shape = RoundedCornerShape(14.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text("${h.host.name} · 暂未连接", fontWeight = FontWeight.Bold); Text(h.error, style = MaterialTheme.typography.bodySmall); Text("不会把指令转发到其他电脑。", style = MaterialTheme.typography.bodySmall) } } } }
        if (hosts.isEmpty()) item { EmptyState("从第一台电脑开始", "在 Windows 上运行桥接程序，然后扫码。每台电脑独立连接，互不影响。", add) }
        else if (tasks.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (attention) "暂时没有运行或待处理任务" else "从新会话开始", style = MaterialTheme.typography.titleLarge); Text("可以在手机选择电脑和工作位置，直接发送首条指令。", color = Color(0xFF667268)); OutlinedButton(onClick = { create(filter) }) { Text("新建会话") } } }
        items(tasks, key = { "${it.first.host.id}/${it.second.optString("id")}" }) { (h, t) ->
            Card(onClick = { open(ThreadKey(h.host.id, t.optString("id"))) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(h.host.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Forest, modifier = Modifier.weight(1f)); StatusPill(t.optString("status")) }
                    Row(verticalAlignment = Alignment.CenterVertically) { if (unread.containsKey(ThreadKey(h.host.id, t.optString("id")))) { CompletionDot("有新的已完成回复"); Spacer(Modifier.width(7.dp)) }; Text(t.optString("title", "未命名会话"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    val summary = t.optString("summary"); if (summary.isNotEmpty()) Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF667268), fontSize = 13.sp)
                }
            }
        }
        item { Text("私网直连 · 不迁移对话 · 不自动重发指令", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF667268), modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

@Composable private fun StatusPill(status: String) { val text = when(status) { "active", "inProgress" -> "运行中"; "needsInput" -> "待处理"; "failed" -> "失败"; "completed" -> "已完成"; "notLoaded" -> "历史"; "interrupted" -> "已中断"; else -> "空闲" }; Surface(color = if (status == "needsInput") Color(0xFFFFD8A5) else if (status == "active") Lime else Color(0xFFEDF0E8), shape = RoundedCornerShape(30.dp)) { Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Forest) } }
@Composable private fun EmptyState(title: String, detail: String, add: () -> Unit) { Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = Color(0xFF667268)); OutlinedButton(onClick = add) { Text("添加电脑") } } }

@Composable
private fun HostsScreen(hosts: List<HostState>, repo: Repository, add: () -> Unit) {
    var editing by remember { mutableStateOf<HostState?>(null) }; var name by remember { mutableStateOf("") }; var removing by remember { mutableStateOf<HostState?>(null) }; var unlock by remember { mutableStateOf<HostState?>(null) }
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = add, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.Add, null); Text("添加另一台电脑") } }
        items(hosts, key = { it.host.id }) { h -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(h.host.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Switch(checked = h.host.followed, onCheckedChange = { repo.follow(h.host.id, it) }) }
            Text(if (h.online) "在线 · ${h.threads.size} 个最近会话" else h.error, color = Forest); Text(h.host.url, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("证书 ${h.host.fingerprint.take(16)}…", fontSize = 11.sp)
            Row { TextButton(onClick = { editing = h; name = h.host.name }) { Text("改名") }; TextButton(onClick = { removing = h }) { Text("移除") }; TextButton(onClick = { unlock = h }) { Text("核对未确认操作") } }
        } } }
        item { Text("取消关注只停止这台电脑的连接；移除会删除手机信任。要永久撤销手机权限，请同时在那台电脑桥接窗口撤销配对。", fontSize = 13.sp) }
        item { TextButton(onClick = { licenses = true }) { Text("开源许可 · ${BuildConfig.VERSION_NAME}", fontSize = 12.sp) } }
    }
    if (licenses) {
        val text = remember { listOf("THIRD-PARTY-NOTICES.md", "CommonMark-LICENSE.txt", "Apache-2.0.txt").joinToString("\n\n") { file -> context.assets.open("licenses/$file").bufferedReader().use { it.readText() } } }
        TextDetail("开源许可", text, monospace = false) { licenses = false }
    }
    editing?.let { h -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("电脑显示名称") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { repo.rename(h.host.id, name); editing = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    removing?.let { h -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 ${h.host.name}？") }, text = { Text("不会影响其他电脑。电脑上仍应撤销这台手机的配对权限。") }, confirmButton = { TextButton(onClick = { repo.remove(h.host.id); removing = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
    unlock?.let { h -> AlertDialog(onDismissRequest = { unlock = null }, title = { Text("先核对电脑上的会话") }, text = { Text("如果发送结果不确定，请先检查该指令是否已经执行。只有你已核对后才解除锁定；旧指令不会自动重发。") }, confirmButton = { TextButton(onClick = { repo.clearUncertain(h.host.id); unlock = null }) { Text("我已核对，解除锁定") } }, dismissButton = { TextButton(onClick = { unlock = null }) { Text("暂不解除") } }) }
}

@Composable
private fun ThreadScreen(key: ThreadKey, repo: Repository, host: HostState?) {
    val histories by repo.histories.collectAsState(); val view = histories[key]; val scope = rememberCoroutineScope()
    var draft by remember(key) { mutableStateOf(repo.draft(key)) }; var busy by remember(key) { mutableStateOf(false) }; var stopConfirm by remember { mutableStateOf(false) }
    var latestRequest by remember(key) { mutableIntStateOf(0) }
    var modelDialog by remember(key) { mutableStateOf(false) }; var editTarget by remember(key) { mutableStateOf<JSONObject?>(null) }
    DisposableEffect(key) { repo.watch(key); onDispose { repo.unwatch(key) } }
    val active = view?.optString("activeTurnId").orEmpty(); val live = view?.optBoolean("live", true) == true
    val enabled = host?.online == true && host.compatible && view != null && live && !busy
    fun operate(type: String, fields: JSONObject = JSONObject(), success: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try { repo.message.value = repo.operate(key, type, fields); if (type == "send" || type == "steer") { if (draft == fields.optString("text")) { draft = ""; repo.draft(key, "") }; latestRequest++ }; if (type == "edit") latestRequest++; success() }
            catch (e: Exception) { repo.message.value = e.message ?: "未能发送；请刷新核对" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!live || host?.online != true) Column(Modifier.fillMaxWidth().background(Color(0xFFFFE9CF)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            if (!live) TextButton(onClick = { operate("activate") }, enabled = host?.online == true && host.compatible && !busy) { Text("在电脑打开这个现有会话并接入", fontSize = 12.sp) }
            if (host?.online != true) Text("电脑离线：仅保存草稿，不会排队发送", color = Color(0xFF9A4F17), fontSize = 11.sp)
        }
        ConversationTimeline(key, view, Modifier.weight(1f), latestRequest, { turn -> repo.markRead(key, turn) }) { request -> RequestCard(request, enabled, { fields -> operate("decision", fields) }, { fields -> operate("answer", fields) }) }
        Column(Modifier.fillMaxWidth().background(Paper).imePadding().padding(horizontal = 10.dp, vertical = 5.dp)) {
            LatestFilesButton(view)
            LatestThought(view, active.isNotEmpty(), enabled) { stopConfirm = true }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { modelDialog = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp).weight(1f)) { Text(view?.optString("model")?.substringAfterLast('/')?.ifBlank { "选择模型" } ?: "选择模型", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Outlined.ExpandMore, "切换模型", Modifier.size(15.dp)) }
                TextButton(onClick = { editTarget = view?.optJSONObject("editTarget")?.let { JSONObject(it.toString()).put("instanceId", view.optString("instanceId")) } }, enabled = enabled && view?.optJSONObject("editTarget") != null, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp)) { Icon(Icons.Outlined.Edit, null, Modifier.size(13.dp)); Text("编辑最新消息", fontSize = 11.sp) }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it; repo.draft(key, it) }, placeholder = { Text(if (active.isNotEmpty()) "追加指示…" else "发送指令…", fontSize = 14.sp) }, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).heightIn(max = 120.dp), shape = RoundedCornerShape(14.dp), maxLines = 4)
                FilledIconButton(onClick = { operate(if (active.isEmpty()) "send" else "steer", JSONObject().put("text", draft)) }, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(48.dp)) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
            }
        }
    }
    if (modelDialog) ModelDialog(key, host, repo, enabled, { modelDialog = false }) { fields -> operate("model", fields) { modelDialog = false } }
    editTarget?.let { target -> EditMessageDialog(key, target, view, repo, enabled, { editTarget = null }) { fields, clear -> operate("edit", fields) { clear(); editTarget = null } } }
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text("中断 ${host?.host?.name} 上的任务？") }, text = { Text("只中断当前会话的这一轮，不影响其他电脑。") }, confirmButton = { TextButton(onClick = { stopConfirm = false; operate("interrupt") }) { Text("确认中断") } }, dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("继续运行") } })
}

@Composable
private fun RequestCard(request: JSONObject, enabled: Boolean, decision: (JSONObject) -> Unit, answer: (JSONObject) -> Unit) {
    val method = request.optString("method"); val params = request.optJSONObject("params") ?: JSONObject(); val requestId = request.optString("id")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE6BF))) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("需要你处理", style = MaterialTheme.typography.titleLarge)
        when(method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                SelectionContainer { Text(params.optString("command", params.optString("reason", "请检查本次修改")), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                if (params.optString("reason").isNotEmpty()) Text(params.optString("reason"), fontSize = 13.sp)
                Text("仅对本次请求有效。若电脑已经处理，这里会自动更新。", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { decision(JSONObject().put("requestId", requestId).put("decision", "accept")) }, enabled = enabled) { Text("允许本次") }; OutlinedButton(onClick = { decision(JSONObject().put("requestId", requestId).put("decision", "decline")) }, enabled = enabled) { Text("拒绝") } }
            }
            "item/tool/requestUserInput" -> {
                val questions = params.optJSONArray("questions")?.objects().orEmpty(); val answers = remember(requestId) { mutableStateMapOf<String, String>() }
                questions.forEach { question ->
                    val id = question.optString("id"); Text(question.optString("question"), fontWeight = FontWeight.SemiBold)
                    question.optJSONArray("options")?.objects()?.forEach { option ->
                        OutlinedButton(onClick = { answers[id] = option.optString("label") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text((if (answers[id] == option.optString("label")) "✓ " else "") + option.optString("label")); if (option.optString("description").isNotEmpty()) Text(option.optString("description"), fontSize = 11.sp) } }
                    }
                    OutlinedTextField(answers[id].orEmpty(), { answers[id] = it }, label = { Text("回答 / 其他") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), visualTransformation = if (question.optBoolean("isSecret")) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = if (question.optBoolean("isSecret")) KeyboardType.Password else KeyboardType.Text))
                }
                Button(onClick = { val values = JSONObject(); questions.forEach { q -> values.put(q.optString("id"), JSONArray().put(answers[q.optString("id")])) }; answer(JSONObject().put("requestId", requestId).put("answers", values)) }, enabled = enabled && questions.isNotEmpty() && questions.all { !answers[it.optString("id")].isNullOrBlank() }) { Text("提交回答") }
            }
            else -> { Text("这类工具或权限请求暂不在手机处理，请回电脑完成。不会自动批准。"); Text(method, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    } }
}
