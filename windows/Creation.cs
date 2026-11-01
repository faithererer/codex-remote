using System.Text.Json.Nodes;

namespace CodexRemote;

static class Creation
{
    public static JsonArray LocalProjects(JsonNode result) => new(result.Array("projects").OfType<JsonNode>()
        .Where(p => p.S("projectKind") == "local" && p.S("hostId") == "local" && p.S("projectId").Length > 0)
        .Select(p => J.Node(new { id = p.S("projectId"), name = p.S("label", p.S("projectId")), path = p.S("path"), isGitRepository = p["isGitRepository"]?.GetValue<bool>() ?? false })).ToArray());

    public static JsonObject Arguments(JsonNode body, JsonArray projects)
    {
        var prompt = body.S("text").Trim();
        if (prompt.Length is < 1 or > 32000) throw new ApiError(400, "首条指令必须为 1 至 32000 字符");
        var title = body.S("title").Trim();
        if (title.Length > 120) throw new ApiError(400, "标题最多 120 字符");
        JsonNode target;
        var projectId = body.S("projectId");
        if (body.S("location") == "default" && projectId.Length == 0) target = J.Node(new { type = "projectless" });
        else if (body.S("location") == "project" && projects.OfType<JsonNode>().Any(p => p.S("id") == projectId && projectId.Length > 0))
            target = J.Node(new { type = "project", projectId, environment = new { type = "local" } });
        else throw new ApiError(409, "项目不在这台电脑的本地项目列表中，请刷新后重新选择");
        // Model, sandbox, providers and approvals remain the desktop's own defaults.
        var arguments = new JsonObject { ["prompt"] = prompt, ["target"] = target };
        if (title.Length > 0) arguments["title"] = title;
        return arguments;
    }
}
