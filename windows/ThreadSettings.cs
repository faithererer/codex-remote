using System.Text.Json.Nodes;
using Tomlyn;
using Tomlyn.Model;

namespace CodexRemote;

static class ThreadSettings
{
    public static string Model(JsonNode state) => (state["latestCollaborationMode"]?["settings"]).S("model", state.S("latestModel"));
    public static JsonNode EditTarget(JsonNode state)
    {
        var turn = StateView.Turns(state).LastOrDefault();
        var input = turn?["params"].Array("input") ?? [];
        var text = input.Count == 1 && input[0].S("type") == "text" ? input[0].S("text") : "";
        string reason = "";
        if (turn == null || text.Length is < 1 or > 32000) reason = "最新轮次不是可编辑的纯文字消息";
        else if (HasAdditionalUserMessages(turn)) reason = "最新轮次包含追加消息，请在电脑编辑，避免覆盖原指令";
        else if (text.Contains("## My request:") || text.StartsWith("<page-comment-context>") || text.StartsWith("# Files ")) reason = "这条消息带有电脑端上下文，请在电脑编辑";
        else if (turn["params"]?["useAppServerPermissionDefault"]?.GetValue<bool>() != true) reason = "此轮使用自定义或未确认的权限，请在电脑编辑，以保持原权限设置";
        else if (StateView.ActiveTurn(state).Length > 0 || state.Array("requests").Count > 0) reason = "请等当前任务结束后编辑；不会自动中断任务";
        return J.Node(new { turnId = turn.S("turnId"), text = reason.Length == 0 || reason.StartsWith("请等") ? text : "", fingerprint = turn == null ? "" : J.Hash(turn.ToJsonString()), allowed = reason.Length == 0, reason });
    }
    static bool HasAdditionalUserMessages(JsonNode turn)
    {
        var items = turn.Array("items").OfType<JsonNode>().ToList();
        if (items.Any(i => i.S("type") == "steeringUserMessage")) return true;
        var users = items.Where(i => i.S("type") == "userMessage").ToList();
        if (users.Count > 1) return true;
        if (users.Count == 0) return false;
        var client = turn["params"].S("clientUserMessageId");
        return client.Length > 0 ? users[0].S("clientId") != client : !ReferenceEquals(users[0], items.FirstOrDefault());
    }
    public static JsonObject EditArguments(JsonNode state, JsonNode body)
    {
        var target = EditTarget(state);
        if (target["allowed"]?.GetValue<bool>() != true) throw new ApiError(409, target.S("reason"));
        if (body.S("editTurnId") != target.S("turnId") || body.S("editFingerprint") != target.S("fingerprint")) throw new ApiError(409, "最新消息已经变化，请重新打开编辑");
        var text = body.S("text").Trim();
        if (text.Length is < 1 or > 32000 || text == target.S("text")) throw new ApiError(400, "请输入修改后的消息（1 至 32000 字符）");
        // The desktop performs its own rollback/re-submit. Never reproduce it with raw RPC.
        return J.Node(new { conversationId = state.S("id"), turnId = target.S("turnId"), message = text, allowEarlierMessages = false, shouldSendPermissionOverrides = false }).AsObject();
    }
    public static JsonArray Catalog(JsonNode state, JsonNode? configured, bool providerMatches)
    {
        var choices = new JsonArray(); var seen = new HashSet<string>();
        if (providerMatches)
        foreach (var model in configured.Array("models").Take(200))
        {
            var id = model.S("slug");
            if (id.Length is < 1 or > 200 || model.S("visibility", "list") != "list" || !seen.Add(id)) continue;
            choices.Add(J.Node(new { id, name = model.S("display_name", id), efforts = model.Array("supported_reasoning_levels").Select(e => e.S("effort")).Where(e => e.Length > 0).Distinct(), defaultEffort = model.S("default_reasoning_level") }));
        }
        var current = Model(state);
        if (current.Length > 0 && seen.Add(current)) choices.Add(J.Node(new { id = current, name = current, efforts = new[] { state.S("latestReasoningEffort") }, defaultEffort = state.S("latestReasoningEffort") }));
        return choices;
    }
    public static JsonObject ModelArguments(JsonNode state, JsonNode body, JsonArray choices)
    {
        if (body.S("expectedModel") != Model(state) || body.S("expectedEffort") != state.S("latestReasoningEffort") || body.S("expectedProvider") != state.S("modelProvider")) throw new ApiError(409, "电脑上的模型设置已经变化，请重新选择");
        var choice = choices.FirstOrDefault(c => c.S("id") == body.S("model")) ?? throw new ApiError(400, "模型不在这台电脑当前提供商的目录中");
        var effort = body.S("effort");
        if (!choice.Array("efforts").Any(e => e?.ToString() == effort)) throw new ApiError(400, "该模型不支持所选思考强度");
        return J.Node(new { conversationId = state.S("id"), threadSettings = new { model = body.S("model"), effort }, condition = new { ifModelEquals = Model(state), ifEffortEquals = state["latestReasoningEffort"]?.Copy() } }).AsObject();
    }
    public static (JsonNode? Catalog, bool Matches) ReadCatalog(JsonNode state)
    {
        // Read only the configured catalog; never copy provider secrets or guess models from another PC.
        var home = Environment.GetEnvironmentVariable("CODEX_HOME");
        if (string.IsNullOrWhiteSpace(home)) home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".codex");
        var root = Path.Combine(home, "config.toml");
        if (!File.Exists(root) || new FileInfo(root).Length > 1024 * 1024) return (null, false);
        var table = Toml.ToModel(File.ReadAllText(root));
        var provider = table.TryGetValue("model_provider", out var p) ? p as string ?? "" : "openai";
        var catalog = table.TryGetValue("model_catalog_json", out var c) ? c as string : null;
        if (table.TryGetValue("profile", out var profile) && profile is string name && table.TryGetValue("profiles", out var profiles) && profiles is TomlTable all && all.TryGetValue(name, out var entry) && entry is TomlTable active)
        {
            if (active.TryGetValue("model_provider", out p)) provider = p as string ?? "";
            if (active.TryGetValue("model_catalog_json", out c)) catalog = c as string;
        }
        // A project-level configuration needs native layered discovery, which is not exposed by this IPC.
        for (var dir = new DirectoryInfo(state.S("cwd")); dir != null; dir = dir.Parent)
        {
            var scoped = Path.Combine(dir.FullName, ".codex", "config.toml");
            var defaultGlobal = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".codex", "config.toml");
            if (File.Exists(scoped) && !string.Equals(Path.GetFullPath(scoped), Path.GetFullPath(root), StringComparison.OrdinalIgnoreCase) && !string.Equals(Path.GetFullPath(scoped), defaultGlobal, StringComparison.OrdinalIgnoreCase)) return (null, false);
        }
        if (catalog == null || provider != state.S("modelProvider")) return (null, false);
        var path = Path.GetFullPath(catalog, home);
        if (!File.Exists(path) || new FileInfo(path).Length > 4 * 1024 * 1024) return (null, false);
        return (JsonNode.Parse(File.ReadAllText(path)), true);
    }
}
