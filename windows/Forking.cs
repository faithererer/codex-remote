using System.Text.Json.Nodes;

namespace CodexRemote;

static class Forking
{
    public static string GitRoot(string cwd)
    {
        if (cwd.Length == 0 || !Path.IsPathFullyQualified(cwd) || cwd.StartsWith(@"\\")) return "";
        try
        {
            for (var directory = new DirectoryInfo(cwd); directory != null; directory = directory.Parent)
                if (Directory.Exists(Path.Combine(directory.FullName, ".git")) || File.Exists(Path.Combine(directory.FullName, ".git"))) return directory.FullName;
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        return "";
    }

    public static JsonNode Context(JsonNode state, string instance, string gitRoot)
    {
        var active = StateView.ActiveTurn(state);
        var reason = state["ephemeral"]?.GetValue<bool>() == true ? "临时会话没有已保存历史，无法 Fork。"
            : active.Length > 0 || state.Array("requests").Count > 0 ? "请等待当前任务结束并处理待办，再 Fork；不会自动中断原任务。"
            : state.S("cwd").Length == 0 ? "原会话没有已确认的工作目录，请在电脑打开后重试。" : "";
        return J.Node(new { threadId = state.S("id"), instanceId = instance, cwd = state.S("cwd"), gitRoot,
            latestTurnId = StateView.Turns(state).LastOrDefault()?.S("turnId") ?? "", activeTurnId = active,
            canFork = reason.Length == 0, canWorktree = reason.Length == 0 && gitRoot.Length > 0, reason });
    }

    public static JsonNode Arguments(JsonNode body, JsonNode context)
    {
        if (body.S("threadId") != context.S("threadId") || body.S("instanceId") != context.S("instanceId")) throw new ApiError(409, "Fork 来源或实例已变化，请重新确认");
        if (context["canFork"]?.GetValue<bool>() != true) throw new ApiError(409, context.S("reason", "此会话暂不能 Fork"));
        if (body.S("latestTurnId") != context.S("latestTurnId") || body.S("turnId").Length > 0) throw new ApiError(409, "原会话的轮次已变化，请刷新后重新确认 Fork");
        var environment = body.S("environment");
        if (environment is not ("same-directory" or "worktree")) throw new ApiError(400, "只支持当前工作区或新工作树");
        if (environment == "worktree" && context["canWorktree"]?.GetValue<bool>() != true) throw new ApiError(409, "当前工作目录不在已确认的 Git 仓库中，不能创建工作树");
        // The native fork owns history copying and worktree setup; no path or settings override is forwarded.
        return J.Node(new { threadId = context.S("threadId"), environment = new { type = environment } });
    }
}
