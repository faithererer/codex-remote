package cn.codexremote.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class HostState(val host: Host, val online: Boolean = false, val instance: String = "", val compatible: Boolean = false, val error: String = "等待连接", val threads: List<JSONObject> = emptyList())
data class ThreadKey(val hostId: String, val threadId: String)
data class ProjectCatalog(val hostId: String, val instanceId: String, val projects: List<JSONObject>)
data class Notice(val hostId: String, val threadId: String, val title: String, val text: String)
fun shouldNotify(previous: String?, current: String, previousRequests: String, currentRequests: String): Boolean =
    (current == "needsInput" && (previous != "needsInput" || previousRequests != currentRequests)) ||
    (previous in listOf("active", "needsInput") && current in listOf("completed", "failed", "interrupted"))

class RemoteApplication : Application() { lateinit var repository: Repository; override fun onCreate() { super.onCreate(); repository = Repository(this) } }

class Repository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = SecureStore(context)
    val states = MutableStateFlow<Map<String, HostState>>(emptyMap())
    val histories = MutableStateFlow<Map<ThreadKey, JSONObject>>(emptyMap())
    val message = MutableStateFlow("")
    val monitoring = MutableStateFlow(false)
    val homeSelection = MutableStateFlow(HomeSelection())
    val unreadCompletions = MutableStateFlow<Map<ThreadKey, String>>(emptyMap())
    private var readCompletions: Map<ThreadKey, String> = emptyMap()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val apis = ConcurrentHashMap<String, HostApi>()
    private val selected = ConcurrentHashMap.newKeySet<ThreadKey>()
    private val signals = ConcurrentHashMap<ThreadKey, JSONObject>()
    private val operationGate = OperationGate()
    private fun ownsApi(id: String, api: HostApi) = apis[id] === api && states.value[id]?.host?.url == api.host.url
    private var visible = false
    var notify: ((Notice) -> Unit)? = null
    init {
        try {
            states.value = store.hosts().associate { it.id to HostState(it) }
            homeSelection.value = HomeSelection.parse(store.get("home-selection", "{}")).let { if (it.hostId.isEmpty() || states.value[it.hostId]?.host?.followed == true) it else it.copy(hostId = "") }
            unreadCompletions.value = completionMarkers(store.get("unread-completions", "[]")).filterKeys { states.value.containsKey(it.hostId) }
            readCompletions = completionMarkers(store.get("read-completions", "[]")).filterKeys { states.value.containsKey(it.hostId) }
        }
        catch (_: Exception) { message.value = "手机安全存储无法解密。为保护信任关系，请清除应用数据后重新配对。" }
    }
    fun foreground(value: Boolean) { visible = value; reconcile() }
    fun monitor(value: Boolean) { monitoring.value = value; reconcile() }
    @Synchronized private fun reconcile() {
        val wanted = states.value.values.filter { it.host.followed && (visible || monitoring.value) }.map { it.host.id }.toSet()
        jobs.keys.filter { it !in wanted }.forEach { jobs.remove(it)?.cancel(); apis.remove(it)?.close(); states.update { old -> old[it]?.let { s -> old + (it to s.copy(online = false)) } ?: old } }
        wanted.filter { jobs[it]?.isActive != true }.forEach { id -> jobs[id] = scope.launch { connect(id) } }
    }
    private fun save() = store.save(states.value.values.map { it.host })
    @Synchronized fun rename(id: String, value: String) { if (value.isBlank() || value.length > 60) return; states.update { old -> old + (id to old.getValue(id).copy(host = old.getValue(id).host.copy(name = value.trim()))) }; save() }
    @Synchronized fun selectHome(value: HomeSelection) { require(value.hostId.isEmpty() || states.value[value.hostId]?.host?.followed == true); store.put("home-selection", value.json()); homeSelection.value = value }
    @Synchronized fun follow(id: String, value: Boolean) { states.update { old -> old + (id to old.getValue(id).copy(host = old.getValue(id).host.copy(followed = value))) }; if (!value) selectHome(homeSelection.value.without(id)); save(); reconcile() }
    @Synchronized fun remove(id: String) { jobs.remove(id)?.cancel(); apis.remove(id)?.close(); states.update { it - id }; histories.update { all -> all.filterKeys { it.hostId != id } }; selected.removeAll { it.hostId == id }; signals.keys.removeAll { it.hostId == id }; selectHome(homeSelection.value.without(id)); unreadCompletions.update { all -> all.filterKeys { it.hostId != id } }; readCompletions = readCompletions.filterKeys { it.hostId != id }; store.put("read-completions", completionMarkersJson(readCompletions)); saveUnread(); store.put("pending-$id", "{}"); save() }
    @Synchronized private fun saveUnread() = store.put("unread-completions", completionMarkersJson(unreadCompletions.value))
    @Synchronized fun markRead(key: ThreadKey, turnId: String) {
        if (turnId.isEmpty() || !states.value.containsKey(key.hostId)) return
        if (readCompletions[key] != turnId) { readCompletions = readCompletions + (key to turnId); store.put("read-completions", completionMarkersJson(readCompletions)) }
        if (unreadCompletions.value[key] == turnId) { unreadCompletions.update { it - key }; saveUnread() }
    }
    @Synchronized private fun completed(key: ThreadKey, turnId: String) { if (states.value.containsKey(key.hostId) && isUnreadCompletion(key, turnId, readCompletions)) { unreadCompletions.update { it + (key to turnId) }; saveUnread() } }
    suspend fun pair(text: String): String = withContext(Dispatchers.IO) {
        val code = PairCode.parse(text)
        val previous = states.value[code.host.id]
        require(previous == null || previous.host.fingerprint == code.host.fingerprint) { "同一电脑的证书已改变。请先在手机移除旧电脑，再重新核对配对。" }
        val api = HostApi(code.host)
        try {
            val body = JSONObject().put("hostId", code.host.id).put("secret", code.secret).put("deviceName", android.os.Build.MODEL.take(70))
            val result = api.request("/api/v1/pair", body, false)
            require(result.getString("hostId") == code.host.id) { "电脑身份不匹配" }
            val host = code.host.copy(token = result.getString("token"), deviceId = result.getString("deviceId"))
            synchronized(this@Repository) { jobs.remove(host.id)?.cancel(); apis.remove(host.id)?.close(); states.update { it + (host.id to HostState(host)) }; save(); reconcile() }
            host.name
        } finally { api.close() }
    }
    suspend fun changeAddress(id: String, value: String) = withContext(Dispatchers.IO) {
        operationGate.run(id) {
            val original = states.value[id]?.host ?: error("电脑已移除")
            val candidate = original.copy(url = connectionAddress(value))
            val api = HostApi(candidate)
            try {
                val verified = hostAtVerifiedAddress(original, candidate.url, api.request("/api/v1/status"))
                synchronized(this@Repository) {
                    val current = states.value[id]?.host ?: error("电脑已移除")
                    require(current.url == original.url && current.fingerprint == original.fingerprint && current.token == original.token) { "配对或地址已变化，请重新打开设置" }
                    // Persist only the verified URL. Pending operations, drafts and trust stay on this host.
                    val next = current.copy(url = verified.url)
                    store.save(states.value.values.map { if (it.host.id == id) next else it.host })
                    jobs.remove(id)?.cancel(); apis.remove(id)?.close()
                    histories.update { all -> all.filterKeys { it.hostId != id } }
                    states.update { all -> all + (id to HostState(next)) }
                    reconcile()
                }
            } finally { api.close() }
        }
    }
    private suspend fun connect(id: String) {
        var backoff = 1000L; var disconnectedAt = 0L; var warned = false; var sequence = 0L; var instance = ""
        while (currentCoroutineContext().isActive && states.value.containsKey(id)) {
            val host = states.value.getValue(id).host
            val api = HostApi(host); apis.put(id, api)?.close()
            try {
                val status = api.request("/api/v1/status")
                if (!ownsApi(id, api)) throw CancellationException("连接地址已更改")
                require(status.getString("hostId") == id) { "电脑身份不匹配" }
                val next = status.getString("instanceId")
                if (next != instance) { instance = next; sequence = 0; histories.update { all -> all.filterKeys { it.hostId != id } } }
                states.update { old -> old[id]?.let { old + (id to it.copy(online = status.optBoolean("desktopConnected"), compatible = status.optBoolean("compatible"), instance = instance, error = status.optString("error"))) } ?: old }
                refresh(id, api); reconcilePending(id, api)
                for (key in selected.filter { it.hostId == id }) load(key)
                backoff = 1000; disconnectedAt = 0; warned = false
                suspendCancellableCoroutine<Unit> { continuation ->
                    val socket = api.events(instance, sequence, object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!ownsApi(id, api)) return
                            try {
                                val event = JSONObject(text); val eventInstance = event.optString("instanceId")
                                if (eventInstance != instance || event.optString("type") == "reset") {
                                    instance = eventInstance; sequence = event.optLong("sequence", 0)
                                    states.update { old -> old[id]?.let { old + (id to it.copy(instance = instance)) } ?: old }
                                    histories.update { all -> all.filterKeys { it.hostId != id } }
                                    scope.launch { refresh(id, api); for (key in selected.filter { it.hostId == id }) load(key) }
                                } else {
                                    sequence = maxOf(sequence, event.optLong("sequence", 0))
                                    when (event.optString("type")) {
                                        "catalog" -> {
                                            val data = event.getJSONObject("data")
                                            updateCatalog(id, data.optJSONArray("threads") ?: JSONArray())
                                            if (data.has("desktopConnected")) states.update { all -> all[id]?.let { all + (id to it.copy(online = data.optBoolean("desktopConnected"), compatible = data.optBoolean("compatible"), error = data.optString("error"))) } ?: all }
                                        }
                                        "catalogInvalidated" -> scope.launch { refresh(id, api) }
                                        "threadUpdated" -> {
                                            val data = event.getJSONObject("data"); val key = ThreadKey(id, data.getString("threadId")); val taskStatus = data.optString("status")
                                            val previous = signals.put(key, data)
                                            if (taskStatus == "completed" && (previous?.optString("status") != "completed" || previous.optString("turnId") != data.optString("turnId"))) completed(key, data.optString("turnId"))
                                            states.update { all -> all[id]?.let { h -> all + (id to h.copy(threads = h.threads.map { t -> if (t.optString("id") == key.threadId) JSONObject(t.toString()).put("status", taskStatus).put("pendingCount", data.optInt("pendingCount")) else t })) } ?: all }
                                            if (monitoring.value && shouldNotify(previous?.optString("status"), taskStatus, previous?.optString("requestIds").orEmpty(), data.optString("requestIds"))) notify?.invoke(Notice(id, key.threadId, host.name, when(taskStatus) { "needsInput" -> "任务需要你处理"; "failed" -> "任务执行失败"; "interrupted" -> "任务已中断"; else -> "任务已完成" }))
                                            if (key in selected) scope.launch { load(key) }
                                        }
                                    }
                                }
                            } catch (_: Exception) { webSocket.cancel() }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (continuation.isActive) continuation.resume(Unit) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (continuation.isActive) continuation.resume(Unit) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); if (continuation.isActive) continuation.resume(Unit) }
                    })
                    continuation.invokeOnCancellation { socket.cancel() }
                }
                throw IllegalStateException("实时连接断开，正在重连")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                if (!ownsApi(id, api)) throw CancellationException("连接地址已更改")
                states.update { all -> all[id]?.let { all + (id to it.copy(online = false, error = connectionMessage(e, host.url))) } ?: all }
                if (!warned && monitoring.value && System.currentTimeMillis() - disconnectedAt > 60000) { warned = true; notify?.invoke(Notice(id, "", host.name, "持续断连，请检查电脑和当前网络")) }
            } finally { apis.remove(id, api); api.close() }
            delay(backoff + kotlin.random.Random.nextLong(500)); backoff = (backoff * 2).coerceAtMost(30000)
        }
    }
    private fun updateCatalog(id: String, threads: JSONArray) { states.update { all -> all[id]?.let { all + (id to it.copy(threads = threads.objects())) } ?: all } }
    private suspend fun refresh(id: String, api: HostApi) {
        try {
            val status = api.request("/api/v1/status"); require(status.getString("hostId") == id)
            if (!ownsApi(id, api)) return
            states.update { all -> all[id]?.let { all + (id to it.copy(online = status.optBoolean("desktopConnected"), compatible = status.optBoolean("compatible"), instance = status.getString("instanceId"), error = status.optString("error"))) } ?: all }
            val result = api.request("/api/v1/threads"); require(result.getString("hostId") == id); if (ownsApi(id, api)) updateCatalog(id, result.getJSONArray("threads"))
        }
        catch (e: Exception) { if (e is CancellationException) throw e; message.value = "${states.value[id]?.host?.name}：${e.message}" }
    }
    fun refreshAll() { scope.launch { for ((id, api) in apis) refresh(id, api); for (key in selected) load(key) } }
    fun watch(key: ThreadKey) { selected.add(key); scope.launch { load(key) } }
    fun unwatch(key: ThreadKey) { selected.remove(key) }
    private suspend fun load(key: ThreadKey) {
        val api = apis[key.hostId] ?: return
        try { val view = api.request("/api/v1/threads/${key.threadId}"); if (ownsApi(key.hostId, api) && view.optString("instanceId") == states.value[key.hostId]?.instance) histories.update { it + (key to view) } }
        catch (e: Exception) { if (e is CancellationException) throw e; message.value = e.message ?: "读取失败" }
    }
    suspend fun operate(key: ThreadKey, type: String, fields: JSONObject = JSONObject()): String = withContext(Dispatchers.IO) {
      operationGate.run(key.hostId) {
        val host = states.value[key.hostId] ?: error("电脑已移除")
        require(host.online && host.compatible) { "电脑离线或原生协议尚未就绪；只保存草稿，不排队执行" }
        val history = histories.value[key] ?: error("请先刷新会话状态")
        require(history.optString("instanceId") == host.instance) { "实例已经改变，请刷新" }
        if (type == "model") require(fields.optString("catalogInstanceId") == host.instance) { "模型目录所属实例已变化，请重新选择" }
        if (type == "edit") require(fields.optString("editInstanceId") == host.instance) { "编辑所属实例已变化，请重新打开编辑" }
        val body = JSONObject(fields.toString()).put("hostId", key.hostId).put("instanceId", host.instance).put("threadId", key.threadId).put("turnId", history.optString("activeTurnId")).put("operationId", UUID.randomUUID().toString()).put("type", type)
        val api = apis[key.hostId] ?: error("电脑未连接")
        val pendingName = "pending-${key.hostId}"; val old = JSONObject(store.get(pendingName, "{}"))
        require(old.length() == 0) { "上次发送结果未确认。请先刷新并核对会话，不会重复发送" }
        store.put(pendingName, body.toString())
        try {
            val result = api.request("/api/v1/operations", body)
            if (result.optString("status") == "uncertain") error("结果未确认；草稿已保留，请核对电脑会话。操作号：${body.getString("operationId")}")
            store.put(pendingName, "{}")
            load(key)
            if (result.optString("status") == "rejected") error(result.optJSONObject("result")?.optString("error") ?: "电脑未接受指令")
            if (type == "model" && result.optJSONObject("result")?.optBoolean("applied") != true) error("电脑模型设置已变化，请刷新后重新选择")
            "电脑已接收"
        } catch (e: Exception) {
            if (store.get(pendingName, "{}") != "{}") message.value = "发送结果未确认，请核对会话。不会自动重发。"
            throw e
        }
      }
    }
    suspend fun models(key: ThreadKey): JSONObject = withContext(Dispatchers.IO) {
        val host = states.value[key.hostId] ?: error("电脑已移除")
        require(host.online && host.compatible) { "电脑离线，不能读取模型" }
        val result = (apis[key.hostId] ?: error("电脑未连接")).request("/api/v1/threads/${key.threadId}/models")
        require(result.optString("hostId") == key.hostId && result.optString("threadId") == key.threadId && result.optString("instanceId") == host.instance && states.value[key.hostId]?.instance == host.instance) { "电脑实例已变化，请重新选择模型" }
        result
    }
    suspend fun projects(id: String): ProjectCatalog = withContext(Dispatchers.IO) {
        val host = states.value[id] ?: error("电脑已移除")
        require(host.online && host.compatible) { "电脑离线或原生协议尚未就绪，不能读取创建位置" }
        val response = (apis[id] ?: error("电脑未连接")).request("/api/v1/projects")
        require(response.optString("hostId") == id && response.optString("instanceId") == states.value[id]?.instance) { "电脑实例已变化，请刷新位置" }
        ProjectCatalog(id, response.getString("instanceId"), response.optJSONArray("projects")?.objects().orEmpty())
    }
    suspend fun create(id: String, catalog: ProjectCatalog, draft: JSONObject): ThreadKey? = withContext(Dispatchers.IO) {
        operationGate.run(id) {
            val host = states.value[id] ?: error("电脑已移除")
            require(host.online && host.compatible && host.instance == catalog.instanceId && catalog.hostId == id) { "电脑离线或实例已变化，请刷新创建位置。不会转到其他电脑。" }
            val body = newThreadOperation(id, host.instance, draft, catalog.projects)
            val pendingName = "pending-$id"
            require(JSONObject(store.get(pendingName, "{}")).length() == 0) { "上次操作尚未确认，请先核对会话列表，不会重复新建" }
            val api = apis[id] ?: error("电脑未连接")
            store.put(pendingName, body.toString())
            val response = try { api.request("/api/v1/operations", body) } catch (e: Exception) {
                message.value = "新建结果未确认。请先核对该电脑会话列表，不要重复创建。"; throw e
            }
            when (response.optString("status")) {
                "accepted" -> {
                    store.put(pendingName, "{}"); store.put("create-draft-$id", "{}")
                    refresh(id, api)
                    val threadId = response.optJSONObject("result")?.optString("threadId").orEmpty()
                    if (runCatching { UUID.fromString(threadId) }.isSuccess) ThreadKey(id, threadId)
                    else { message.value = "${host.host.name} 已接收新建请求，准备完成后会出现在任务列表中；不要重复创建。"; null }
                }
                "rejected" -> { store.put(pendingName, "{}"); error(response.optJSONObject("result")?.optString("error") ?: "电脑未接受新建请求") }
                else -> error("新建结果未确认，请先核对该电脑的会话列表，不会自动重试。操作号：${body.optString("operationId")}")
            }
        }
    }
    fun creationDraft(id: String) = JSONObject(store.get("create-draft-$id", "{}"))
    fun creationDraft(id: String, draft: JSONObject) = store.put("create-draft-$id", draft.toString())
    suspend fun forkContext(key: ThreadKey): JSONObject = withContext(Dispatchers.IO) {
        val host = states.value[key.hostId] ?: error("电脑已移除")
        require(host.online && host.compatible) { "电脑离线或原生协议尚未就绪，不能 Fork" }
        val result = (apis[key.hostId] ?: error("电脑未连接")).request("/api/v1/threads/${key.threadId}/fork-context")
        require(result.optString("hostId") == key.hostId && result.optString("instanceId") == states.value[key.hostId]?.instance && result.optString("threadId") == key.threadId) { "电脑或实例已变化，请重新读取来源" }
        result
    }
    suspend fun fork(key: ThreadKey, context: JSONObject, environment: String): JSONObject = withContext(Dispatchers.IO) {
        operationGate.run(key.hostId) {
            val host = states.value[key.hostId] ?: error("电脑已移除")
            require(host.online && host.compatible) { "电脑离线或原生协议尚未就绪，不会排队或转到其他电脑" }
            val body = forkOperation(key, host.instance, context, environment)
            val pendingName = "pending-${key.hostId}"
            require(JSONObject(store.get(pendingName, "{}")).length() == 0) { "上次操作尚未确认，请先核对，不会重复 Fork" }
            require(lastFork(key).length() == 0) { "这个会话的上次 Fork 尚待核对，不会重复提交" }
            val api = apis[key.hostId] ?: error("电脑未连接")
            store.put(pendingName, body.toString())
            val response = api.request("/api/v1/operations", body)
            when (response.optString("status")) {
                "accepted" -> {
                    val result = response.optJSONObject("result") ?: JSONObject()
                    store.put("fork-result-${key.hostId}-${key.threadId}", result.toString())
                    store.put(pendingName, "{}"); refresh(key.hostId, api)
                    result
                }
                "rejected" -> { store.put(pendingName, "{}"); error(response.optJSONObject("result")?.optString("error") ?: "电脑未接受 Fork") }
                else -> error("Fork 结果未确认。请先核对电脑会话列表；不会自动重试。操作号：${body.optString("operationId")}")
            }
        }
    }
    fun lastFork(key: ThreadKey) = JSONObject(store.get("fork-result-${key.hostId}-${key.threadId}", "{}"))
    fun clearForkResult(key: ThreadKey) = store.put("fork-result-${key.hostId}-${key.threadId}", "{}")
    private fun reconcilePending(id: String, api: HostApi) {
        val body = JSONObject(store.get("pending-$id", "{}")); val op = body.optString("operationId"); if (op.isEmpty()) return
        try {
            val result = api.request("/api/v1/operations/$op")
            if (result.optString("status") in listOf("accepted", "rejected")) {
                if (body.optString("type") == "fork" && result.optString("status") == "accepted") store.put("fork-result-$id-${body.optString("threadId")}", (result.optJSONObject("result") ?: JSONObject()).toString())
                store.put("pending-$id", "{}"); message.value = "${states.value[id]?.host?.name}：已核对上次操作：${result.optString("status")}"
            }
            else message.value = "${states.value[id]?.host?.name}：上次操作仍未确认。核对会话后可在电脑设置中解除发送锁定。"
        } catch (_: Exception) { message.value = "${states.value[id]?.host?.name}：请先核对上次操作是否执行，手机没有重发。" }
    }
    fun clearUncertain(id: String) { store.put("pending-$id", "{}"); message.value = "发送锁定已解除；旧指令不会重发。" }
    fun draft(key: ThreadKey) = store.get("draft-${key.hostId}-${key.threadId}", "")
    fun draft(key: ThreadKey, value: String) = store.put("draft-${key.hostId}-${key.threadId}", value)
}
