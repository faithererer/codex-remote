using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Pipes;
using System.Management;
using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace CodexRemote;

sealed class PipePeer : IAsyncDisposable
{
    readonly NamedPipeClientStream stream;
    readonly SemaphoreSlim writer = new(1);
    readonly ConcurrentDictionary<string, TaskCompletionSource<JsonNode>> pending = new();
    readonly CancellationTokenSource stopped = new();
    public event Action<JsonNode>? Received;
    public event Action? Closed;
    public string ClientId { get; private set; } = "";
    public bool Connected => stream.IsConnected && !stopped.IsCancellationRequested;
    public PipePeer(string name) => stream = new(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
    public async Task Connect(bool router)
    {
        await stream.ConnectAsync(3000);
        _ = ReadLoop();
        if (router) { var response = await Request("initialize", new { clientType = "codex-remote" }, 0); ClientId = response["result"].S("clientId"); }
    }
    public async Task Write(JsonNode node)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(node.ToJsonString());
        if (bytes.Length > 8 * 1024 * 1024) throw new ApiError(413, "消息过大");
        var head = new byte[4]; BinaryPrimitives.WriteInt32LittleEndian(head, bytes.Length);
        await writer.WaitAsync(stopped.Token);
        try { await stream.WriteAsync(head, stopped.Token); await stream.WriteAsync(bytes, stopped.Token); await stream.FlushAsync(stopped.Token); }
        finally { writer.Release(); }
    }
    public Task Broadcast(string method, object parameters, string owner) => Write(J.Node(new { type = "broadcast", method, sourceClientId = ClientId, version = 1, targetClientIds = new[] { owner }, @params = parameters }));
    public async Task<JsonNode> Request(string method, object parameters, int version = 1, string? target = null, bool rpc = false)
    {
        var id = Guid.NewGuid().ToString();
        var completion = new TaskCompletionSource<JsonNode>(TaskCreationOptions.RunContinuationsAsynchronously);
        pending[id] = completion;
        try
        {
            await Write(rpc ? J.Node(new { jsonrpc = "2.0", id, method, @params = parameters }) : J.Node(new { type = "request", requestId = id, sourceClientId = ClientId, method, @params = parameters, version, targetClientId = target, timeoutMs = 15000 }));
            var result = await completion.Task.WaitAsync(TimeSpan.FromSeconds(25), stopped.Token);
            if (result.S("resultType") == "error" || result["error"] != null) throw new ApiError(409, "Codex 未接受请求：" + (result["error"]?.ToJsonString() ?? "unknown"));
            return result;
        }
        finally { pending.TryRemove(id, out _); }
    }
    async Task ReadLoop()
    {
        try
        {
            var head = new byte[4];
            while (!stopped.IsCancellationRequested)
            {
                await stream.ReadExactlyAsync(head, stopped.Token);
                int length = BinaryPrimitives.ReadInt32LittleEndian(head);
                if (length is <= 0 or > 64 * 1024 * 1024) throw new IOException("Invalid desktop IPC frame");
                var bytes = new byte[length]; await stream.ReadExactlyAsync(bytes, stopped.Token);
                var node = JsonNode.Parse(bytes)!;
                if (node.S("type") == "client-discovery-request") { await Write(J.Node(new { type = "client-discovery-response", requestId = node.S("requestId"), response = new { canHandle = false } })); continue; }
                var id = node.S("requestId", node.S("id"));
                if ((node.S("type") == "response" || node["jsonrpc"] != null) && pending.TryRemove(id, out var completion)) completion.TrySetResult(node);
                else Received?.Invoke(node);
            }
        }
        catch { }
        finally
        {
            stopped.Cancel();
            foreach (var item in pending.Values) item.TrySetException(new IOException("Codex IPC disconnected"));
            Closed?.Invoke();
        }
    }
    public ValueTask DisposeAsync() { stopped.Cancel(); stream.Dispose(); return ValueTask.CompletedTask; }
}

static class StateView
{
    public static string DiffText(JsonNode? value) => value is JsonValue ? value.ToString() : value.S("text", value.S("diff"));
    public static string LimitDiff(string text) => text.Length > 160000 ? text[..160000] + "\n[差异过长，剩余内容请在电脑查看]" : text;
    public static JsonArray Changes(JsonNode item)
    {
        int budget = 320000;
        var result = new JsonArray();
        foreach (var change in item.Array("changes").OfType<JsonNode>().Take(100))
        {
            var diff = DiffText(change["diff"]);
            var limit = Math.Min(96000, budget); var truncated = diff.Length > limit;
            var shown = diff[..Math.Min(diff.Length, limit)]; budget -= shown.Length;
            var kind = change["kind"];
            result.Add(J.Node(new { path = change.S("path"), kind = kind is JsonValue ? kind.ToString() : kind.S("type", "update"), movePath = kind is JsonObject ? kind.S("move_path", kind.S("movePath")) : "", diff = shown, truncated }));
        }
        return result;
    }
    public static IEnumerable<JsonNode> Turns(JsonNode state)
    {
        if (state["turnHistory"]?["history"] is JsonNode history)
            return history.Array("islands").SelectMany(i => i.Array("entries")).Select(e => history["entitiesByKey"]?[e.S("value")]).OfType<JsonNode>();
        return state.Array("turns").OfType<JsonNode>();
    }
    public static string ActiveTurn(JsonNode state) => Turns(state).LastOrDefault(t => t.S("status") == "inProgress")?.S("turnId") ?? "";
    static string UserText(JsonNode? item) => item.S("text", string.Join("\n", item.Array("content").Where(c => c.S("type") == "text").Select(c => c.S("text"))));
    static JsonNode? InitialUserEcho(JsonNode turn, string input)
    {
        var clientId = turn["params"].S("clientUserMessageId");
        var items = turn.Array("items").OfType<JsonNode>().ToList();
        if (clientId.Length > 0)
        {
            var identified = items.FirstOrDefault(i => i.S("type") == "userMessage" && i.S("clientId") == clientId);
            if (identified != null) return identified;
        }
        // Old snapshots lack client IDs. Only the initial item may replace the input fallback.
        var first = items.FirstOrDefault();
        return first?.S("type") == "userMessage" && first.S("clientId").Length == 0 && UserText(first) == input ? first : null;
    }
    public static JsonNode Normalize(JsonNode state, string instance)
    {
        static string Bounded(string value) => value.Length > 20000 ? value[..20000] + "\n[输出过长，完整内容请在电脑查看]" : value;
        var all = new List<JsonNode>();
        var turns = Turns(state).ToList();
        var currentTurn = turns.LastOrDefault(t => t.S("status") == "inProgress") ?? turns.LastOrDefault();
        JsonNode? latestReasoning = null;
        int reasoningPosition = 0;
        foreach (var turn in turns)
        {
            var input = string.Join("\n", turn["params"].Array("input").Where(n => n.S("type") == "text").Select(n => n.S("text")));
            var initialEcho = input.Length > 0 ? InitialUserEcho(turn, input) : null;
            if (input.Length > 0) all.Add(J.Node(new { id = turn.S("turnId") + "-user", turnId = turn.S("turnId"), kind = "user", text = initialEcho == null ? input : UserText(initialEcho), clientId = turn["params"].S("clientUserMessageId"), nativeId = initialEcho?.S("id") ?? "" }));
            foreach (var item in turn.Array("items").OfType<JsonNode>())
            {
                if (ReferenceEquals(item, initialEcho)) continue;
                string type = item.S("type");
                if (type == "reasoning")
                {
                    var summary = item.Array("summary").Select(n => n is JsonValue ? n!.ToString() : n.S("text")).LastOrDefault(s => !string.IsNullOrWhiteSpace(s));
                    if (ReferenceEquals(turn, currentTurn) && summary != null)
                    {
                        latestReasoning = J.Node(new { id = item.S("id"), turnId = turn.S("turnId"), kind = "reasoning", text = Bounded(summary) });
                        reasoningPosition = all.Count;
                    }
                    continue;
                }
                string text = type switch
                {
                    "agentMessage" or "userMessage" => UserText(item),
                    "steeringUserMessage" => item["restoreMessage"].S("text", string.Join("\n", item.Array("input").Where(c => c.S("type") == "text").Select(c => c.S("text")))),
                    "commandExecution" => item.S("command") + "\n" + item.S("aggregatedOutput") + (item["exitCode"] == null ? "" : "\n退出码: " + item.S("exitCode")),
                    "fileChange" => string.Join("\n", item.Array("changes").Select(c => c.S("path"))),
                    "mcpToolCall" => item.S("server") + "/" + item.S("tool") + "\n" + (item["result"]?.ToJsonString(J.Options) ?? item["error"]?.ToJsonString(J.Options) ?? item.S("status")),
                    _ => item.S("text", item.S("status"))
                };
                if (text.Length == 0 && type != "fileChange") continue;
                bool truncated = text.Length > 20000;
                var message = J.Node(new { id = item.S("id"), turnId = turn.S("turnId"), kind = type, text = Bounded(text), truncated, status = item.S("status") });
                if (type == "commandExecution")
                {
                    message["command"] = Bounded(item.S("command"));
                    message["output"] = Bounded(item.S("aggregatedOutput"));
                    message["exitCode"] = item["exitCode"]?.Copy();
                    message["durationMs"] = item["durationMs"]?.Copy();
                }
                if (type == "mcpToolCall") message["toolName"] = item.S("server") + "/" + item.S("tool");
                if (type == "fileChange") { message["changes"] = Changes(item); message["changesTruncated"] = item.Array("changes").Count > 100; }
                all.Add(message);
            }
            var turnDiff = DiffText(turn["diff"]);
            if (turnDiff.Length > 0) all.Add(J.Node(new { id = turn.S("turnId") + "-diff", turnId = turn.S("turnId"), kind = "diff", text = LimitDiff(turnDiff), truncated = turnDiff.Length > 160000 }));
            if (turn["error"] != null) all.Add(J.Node(new { id = turn.S("turnId") + "-error", turnId = turn.S("turnId"), kind = "error", text = turn["error"]!.ToJsonString() }));
        }
        // Preserve one legacy reasoning row for old clients; new clients use the rolling status field.
        if (latestReasoning != null) all.Insert(reasoningPosition, latestReasoning.Copy());
        return J.Node(new { threadId = state.S("id"), instanceId = instance, title = state.S("title"), activeTurnId = ActiveTurn(state), latestReasoning, requests = state.Array("requests"), messages = all.TakeLast(600), hasOlder = all.Count > 600, status = state.Array("requests").Count > 0 ? "needsInput" : ActiveTurn(state).Length > 0 ? "active" : "idle", model = state.S("latestModel") });
    }
    public static void Apply(JsonNode state, JsonArray patches)
    {
        foreach (var patch in patches.OfType<JsonNode>())
        {
            var path = patch.Array("path"); if (path.Count == 0) throw new InvalidDataException("Root replacement requires snapshot");
            JsonNode target = state;
            foreach (var component in path.Take(path.Count - 1)) target = target is JsonArray array ? array[int.Parse(component!.ToString())]! : target[component!.ToString()]!;
            var key = path[^1]!.ToString(); var value = patch["value"]?.DeepClone(); var op = patch.S("op");
            if (target is JsonArray list)
            {
                int index = int.Parse(key);
                if (op == "remove") list.RemoveAt(index); else if (op == "add") list.Insert(index, value); else if (op == "replace") list[index] = value; else throw new InvalidDataException("Unknown patch");
            }
            else { if (op == "remove") target.AsObject().Remove(key); else if (op is "add" or "replace") target[key] = value; else throw new InvalidDataException("Unknown patch"); }
        }
    }
}

sealed class Desktop(EventHub events) : IAsyncDisposable
{
    readonly object sync = new();
    PipePeer? ipc;
    readonly Dictionary<string, (JsonNode State, long Revision)> states = [];
    readonly ConcurrentDictionary<string, string> owners = new();
    readonly ConcurrentDictionary<string, byte> dirty = new();
    readonly SemaphoreSlim connectionLock = new(1);
    JsonArray catalog = [];
    string caller = "";
    string backend = "";
    public string Version { get; private set; } = "";
    public bool Compatible { get; private set; }
    public bool Connected => ipc?.Connected == true;
    public string LastError { get; private set; } = "尚未连接 Codex";
    public async Task Ensure()
    {
        if (Connected) return;
        await connectionLock.WaitAsync();
        try
        {
            if (Connected) return;
            await CheckBuild();
            var peer = new PipePeer("codex-ipc");
            peer.Received += OnMessage;
            peer.Closed += () => { lock (sync) states.Clear(); owners.Clear(); events.Reset(); };
            await peer.Connect(true); ipc = peer; LastError = "";
        }
        finally { connectionLock.Release(); }
    }
    async Task CheckBuild()
    {
        Compatible = false;
        foreach (var process in Process.GetProcessesByName("ChatGPT"))
        {
            try
            {
                var exe = process.MainModule!.FileName;
                var archive = Path.Combine(Path.GetDirectoryName(exe)!, "resources", "app.asar");
                if (!File.Exists(archive)) continue;
                Version = Directory.GetParent(Path.GetDirectoryName(exe)!)!.Name;
                using var file = File.OpenRead(archive);
                Compatible = Convert.ToHexString(await SHA256.HashDataAsync(file)) == "BBEC1D64E135B718A154B31655BB8AD9A39472BAC1B2CABBA991A9D97A339DD8";
                return;
            }
            catch { }
        }
    }
    void OnMessage(JsonNode message)
    {
        if (message.S("method") != "thread-stream-state-changed" || message["params"].S("hostId") != "local") return;
        var parameters = message["params"]!; var id = parameters.S("conversationId"); var change = parameters["change"]!;
        lock (sync)
        {
            try
            {
                if (change.S("type") == "snapshot") states[id] = (change["conversationState"]!.Copy(), change["revision"]!.GetValue<long>());
                else if (states.TryGetValue(id, out var existing) && existing.Revision == change["baseRevision"]!.GetValue<long>())
                { StateView.Apply(existing.State, change.Array("patches")); states[id] = (existing.State, change["revision"]!.GetValue<long>()); }
                else { states.Remove(id); owners.TryRemove(id, out _); }
            }
            catch { states.Remove(id); owners.TryRemove(id, out _); }
        }
        dirty[id] = 0;
    }
    static string CallerId()
    {
        var index = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".codex", "session_index.jsonl");
        if (!File.Exists(index)) throw new ApiError(503, "请先在 Codex 中打开一个现有会话");
        foreach (var line in File.ReadLines(index).Reverse())
            try { var id = JsonNode.Parse(line).S("id"); if (Guid.TryParse(id, out _)) return id; } catch { }
        throw new ApiError(503, "找不到现有 Codex 会话");
    }
    async Task<JsonNode> AppTool(string tool, object arguments, string? target = null)
    {
        // Only these read/control tools are reachable; raw desktop RPC is never exposed by HTTP.
        if (tool is not ("list_threads" or "read_thread" or "navigate_to_codex_page" or "list_projects" or "create_thread" or "fork_thread")) throw new ApiError(400, "工具不允许");
        var pipes = Directory.GetFiles(@"\\.\pipe\", "codex-app-tools-*");
        if (pipes.Length != 1) throw new ApiError(503, "未找到唯一的 Codex 桌面工具服务，请只运行一个桌面实例");
        if (caller.Length == 0) caller = CallerId();
        await using var peer = new PipePeer(Path.GetFileName(pipes[0])); await peer.Connect(false);
        var response = await peer.Request("tools/call", new { arguments, callerSource = "codex", callId = Guid.NewGuid().ToString(), hostId = "local", @namespace = "codex_app", threadId = target ?? caller, tool, turnId = "remote-" + Guid.NewGuid() }, rpc: true);
        var result = response["result"]!;
        if (result["success"]?.GetValue<bool>() != true) throw new ApiError(409, "桌面工具拒绝了请求：" + string.Join("\n", result.Array("contentItems").Select(n => n.S("text"))));
        var text = string.Join("\n", result.Array("contentItems").Select(n => n.S("text")));
        try { return JsonNode.Parse(text)!; } catch { return J.Node(new { text }); }
    }
    public async Task<JsonArray> Catalog()
    {
        await Ensure();
        var result = await AppTool("list_threads", new { limit = 50 });
        var threads = result.Array("pinnedThreads").Concat(result.Array("threads")).OfType<JsonNode>().Where(t => t.S("hostId") == "local" && t.S("kind") == "codex").GroupBy(t => t.S("id")).Select(g => g.First().Copy()).ToArray();
        foreach (var thread in threads)
        {
            var id = thread.S("id");
            lock (sync) if (states.TryGetValue(id, out var state)) { thread["status"] = state.State.Array("requests").Count > 0 ? "needsInput" : StateView.ActiveTurn(state.State).Length > 0 ? "active" : thread.S("status"); thread["pendingCount"] = state.State.Array("requests").Count; }
        }
        lock (sync) catalog = new JsonArray(threads);
        return new JsonArray(threads.Select(t => t.Copy()).ToArray());
    }
    async Task<string> Follow(string id)
    {
        await Ensure();
        var found = await ipc!.Request("thread-owner-discovery", new { hostId = "local", conversationId = id });
        var owner = found.S("handledByClientId");
        if (owner.Length == 0) throw new ApiError(409, "请先在电脑 Codex 打开此会话，再在手机刷新");
        owners[id] = owner;
        await ipc.Broadcast("thread-stream-following-changed", new { conversationId = id, hostId = "local", following = true }, owner);
        await ipc.Request("thread-follower-load-complete-history", new { conversationId = id }, 1, owner);
        return owner;
    }
    public async Task<JsonNode> Snapshot(string id, bool force = false)
    {
        if (!Guid.TryParse(id, out _)) throw new ApiError(400, "会话标识无效");
        await Ensure();
        bool exists; lock (sync) exists = states.ContainsKey(id);
        if (force || !exists) await Follow(id);
        lock (sync) if (states.TryGetValue(id, out var state)) return state.State.Copy();
        throw new ApiError(409, "尚未获得实时状态，请在电脑打开会话后刷新");
    }
    public async Task<JsonNode> History(string id)
    {
        try { return StateView.Normalize(await Snapshot(id), events.InstanceId); }
        catch (ApiError)
        {
            var history = await AppTool("read_thread", new { threadId = id, hostId = "local", turnLimit = 10, includeOutputs = true, maxOutputCharsPerItem = 20000 }, id);
            var historical = new JsonObject { ["id"] = id, ["title"] = history["thread"].S("title", "历史会话"), ["turns"] = new JsonArray(history.Array("turns").Reverse().Select(t => t?.Copy()).ToArray()) };
            var view = StateView.Normalize(historical, events.InstanceId);
            view["live"] = false; view["hasOlder"] = history["page"]?["hasMore"]?.Copy() ?? false;
            view["warning"] = "历史已读取。点击下方按钮在原电脑打开此会话，即可实时控制；不会新建任务。";
            return view;
        }
    }
    public async Task<JsonNode> Control(JsonNode body)
    {
        if (!Compatible) throw new ApiError(409, "Codex 版本尚未验证，控制已锁定；不会使用其他后端代替");
        if (backend.Length == 0 || BackendIdentity() != backend || body.S("instanceId") != events.InstanceId) throw new ApiError(409, "Codex 后端已改变，请重新读取状态");
        var id = body.S("threadId"); var type = body.S("type");
        if (type == "fork")
        {
            var context = await ForkContext(id);
            var arguments = Forking.Arguments(body, context);
            if (BackendIdentity() != backend || body.S("instanceId") != events.InstanceId) throw new ApiError(409, "实例已变化，请重新确认 Fork 来源");
            JsonNode forked;
            try { forked = await AppTool("fork_thread", arguments, id); }
            catch (ApiError e) { throw new IOException("Fork outcome requires reconciliation", e); }
            events.Publish("catalogInvalidated", new { });
            return forked;
        }
        if (type == "create")
        {
            var projects = await Projects();
            var arguments = Creation.Arguments(body, projects);
            if (BackendIdentity() != backend || body.S("instanceId") != events.InstanceId) throw new ApiError(409, "实例已变化，请重新确认创建位置");
            JsonNode created;
            try { created = await AppTool("create_thread", arguments); }
            catch (ApiError e) { throw new IOException("Creation outcome requires reconciliation", e); }
            // A created thread may still report a failed first turn. Never submit that prompt again.
            if (created.S("threadId").Length == 0 && Guid.TryParse(created.S("conversationId"), out _)) created["threadId"] = created.S("conversationId");
            events.Publish("catalogInvalidated", new { });
            return created;
        }
        if (type == "activate")
        {
            lock (sync) if (!catalog.OfType<JsonNode>().Any(t => t.S("id") == id)) throw new ApiError(404, "此会话不在本机现有桌面会话列表中");
            await AppTool("navigate_to_codex_page", new { threadId = id }, id);
            for (int attempt = 0; attempt < 8; attempt++) { try { await Snapshot(id, true); return J.Node(new { opened = true }); } catch (ApiError) { await Task.Delay(500); } }
            throw new IOException("Desktop opened but the live owner is not confirmed");
        }
        var state = await Snapshot(id, true); var active = StateView.ActiveTurn(state); var expected = body.S("turnId");
        if (expected != active) throw new ApiError(409, "目标轮次已经变化，请刷新后重新确认");
        var owner = owners[id]; var parameters = new JsonObject { ["conversationId"] = id };
        string method; int version = 1;
        if (type is "send" or "steer")
        {
            var text = body.S("text").Trim();
            if (text.Length is < 1 or > 32000) throw new ApiError(400, "指令必须为 1 至 32000 字符");
            var messageId = body.S("operationId");
            var input = J.Node(new[] { new { type = "text", text, text_elements = new object[0] } });
            if (type == "send")
            {
                if (active.Length > 0) throw new ApiError(409, "正在运行，请使用追加指示");
                method = "thread-follower-start-turn"; version = 2;
                // Omit model/permissions/sandbox overrides: the native desktop inherits its real settings.
                parameters["turnStart"] = J.Node(new { request = new { threadId = id, clientUserMessageId = messageId, input, turnTrigger = "composer" }, context = new { inheritThreadSettings = true } });
            }
            else
            {
                if (active.Length == 0) throw new ApiError(409, "当前没有可追加指示的轮次");
                method = "thread-follower-steer-turn";
                parameters["input"] = input;
                parameters["clientUserMessageId"] = messageId;
                parameters["restoreMessage"] = J.Node(new { id = messageId, text, createdAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), cwd = state.S("cwd"), context = new { prompt = text, addedFiles = new object[0], imageAttachments = new object[0], fileAttachments = new object[0], workspaceRoots = new[] { state.S("cwd") } } });
            }
        }
        else if (type == "interrupt")
        {
            if (active.Length == 0) throw new ApiError(409, "任务已经结束");
            method = "thread-follower-interrupt-turn"; version = 4;
            parameters["expectedTurnId"] = active; parameters["mode"] = "user-stop";
        }
        else if (type is "decision" or "answer")
        {
            var request = state.Array("requests").OfType<JsonNode>().FirstOrDefault(r => r.S("id") == body.S("requestId")) ?? throw new ApiError(409, "请求已被电脑或其他设备处理");
            if (request["params"].S("turnId") != expected) throw new ApiError(409, "审批轮次不匹配");
            parameters["requestId"] = request["id"]!.Copy();
            if (type == "decision")
            {
                var decision = body.S("decision"); if (decision is not ("accept" or "decline" or "cancel")) throw new ApiError(400, "只支持本次允许、拒绝或取消");
                method = request.S("method") switch { "item/commandExecution/requestApproval" => "thread-follower-command-approval-decision", "item/fileChange/requestApproval" => "thread-follower-file-approval-decision", _ => throw new ApiError(400, "此类权限或工具请求请在电脑处理") };
                parameters["decision"] = decision;
            }
            else
            {
                if (request.S("method") != "item/tool/requestUserInput") throw new ApiError(400, "此类工具问题请在电脑处理");
                method = "thread-follower-submit-user-input";
                var supplied = body["answers"] as JsonObject ?? throw new ApiError(400, "答案格式不正确"); var answers = new JsonObject();
                foreach (var question in request["params"].Array("questions"))
                {
                    var key = question.S("id"); var answer = supplied[key] as JsonArray;
                    if (answer is null || answer.Count != 1 || answer[0]?.ToString().Length is not (>= 1 and <= 4000)) throw new ApiError(400, "请回答所有问题");
                    answers[key] = J.Node(new { answers = answer });
                }
                parameters["response"] = new JsonObject { ["answers"] = answers };
            }
        }
        else throw new ApiError(400, "不支持的操作");
        if (BackendIdentity() != backend || body.S("instanceId") != events.InstanceId) throw new ApiError(409, "Codex 后端已改变，旧操作未发送");
        try { return (await ipc!.Request(method, parameters, version, owner))["result"]!.Copy(); }
        catch (ApiError e) { throw new IOException("Desktop dispatch outcome requires reconciliation", e); }
    }
    public async Task<JsonArray> Projects()
    {
        await Ensure();
        return Creation.LocalProjects(await AppTool("list_projects", new { }));
    }
    public async Task<JsonNode> ForkContext(string id)
    {
        if (!(await Catalog()).OfType<JsonNode>().Any(t => t.S("id") == id)) throw new ApiError(404, "Fork 来源不在这台电脑的本地 Codex 会话列表中");
        var state = await Snapshot(id, true);
        return Forking.Context(state, events.InstanceId, Forking.GitRoot(state.S("cwd")));
    }
    string BackendIdentity()
    {
        using var search = new ManagementObjectSearcher("SELECT ProcessId, ParentProcessId, CommandLine, CreationDate FROM Win32_Process WHERE Name='codex.exe'");
        var ids = new List<string>();
        foreach (ManagementObject process in search.Get())
        {
            var command = process["CommandLine"]?.ToString() ?? "";
            // Desktop currently uses the default stdio transport without a literal "stdio" argument.
            if (!command.Contains("app-server")) continue;
            try { if (Process.GetProcessById(Convert.ToInt32(process["ParentProcessId"])).ProcessName != "ChatGPT") continue; } catch { continue; }
            ids.Add(process["ProcessId"] + ":" + process["CreationDate"]);
        }
        return ids.Count == 1 ? ids[0] : "";
    }
    public async Task Monitor(CancellationToken cancellation)
    {
        int tick = 0;
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                var next = BackendIdentity();
                if (next != backend) { backend = next; lock (sync) states.Clear(); owners.Clear(); events.Reset(); if (ipc != null) { await ipc.DisposeAsync(); ipc = null; } }
                await Ensure();
                if (tick++ % 5 == 0)
                {
                    var threads = await Catalog(); events.Publish("catalog", new { threads, desktopConnected = Connected, compatible = Compatible, error = LastError });
                    foreach (var thread in threads.OfType<JsonNode>().Where(t => t.S("status") is "active" or "idle" or "needsInput")) if (!owners.ContainsKey(thread.S("id"))) try { await Follow(thread.S("id")); } catch { }
                }
                foreach (var id in dirty.Keys) if (dirty.TryRemove(id, out _))
                {
                    JsonNode? state; lock (sync) state = states.TryGetValue(id, out var value) ? value.State.Copy() : null;
                    events.Publish("threadUpdated", new { threadId = id, turnId = state == null ? "" : StateView.Turns(state).LastOrDefault()?.S("turnId") ?? "", requestIds = state == null ? "" : string.Join("|", state.Array("requests").Select(r => r.S("id")).Order()), status = state == null ? "unknown" : state.Array("requests").Count > 0 ? "needsInput" : StateView.ActiveTurn(state).Length > 0 ? "active" : StateView.Turns(state).LastOrDefault()?.S("status", "idle") ?? "idle", pendingCount = state?.Array("requests").Count ?? 0 });
                }
                LastError = "";
            }
            catch (Exception e) { LastError = e is ApiError ? e.Message : "Codex 尚未连接，请确认桌面已启动"; }
            try { await Task.Delay(1000, cancellation); } catch (OperationCanceledException) { break; }
        }
    }
    public async ValueTask DisposeAsync() { if (ipc != null) await ipc.DisposeAsync(); }
}
