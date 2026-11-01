using System.Text.Json.Nodes;

namespace CodexRemote;

static class Compatibility
{
    public static bool RouterRejectedBeforeDispatch(string error) => error is "no-client-found" or "no-handler-for-request" or "request-version-mismatch";
    public static bool HandshakeReady(string clientId, JsonNode? catalogSchema) => Guid.TryParse(clientId, out _) && Matches(catalogSchema, J.Node(new { limit = 50 }));
    public static void ValidateState(JsonNode state, string threadId)
    {
        if (state.S("id") != threadId || state.S("hostId", "local") != "local" || state["requests"] is not JsonArray)
            throw new ApiError(409, "这个会话的实时协议尚不兼容，请在电脑处理；其他会话不受影响");
        if (state["turnHistory"]?["history"] is JsonNode history)
        {
            if (history["islands"] is not JsonArray || history["entitiesByKey"] is not JsonObject)
                throw new ApiError(409, "这个会话使用了不同的历史结构，暂不能提交此操作");
            foreach (var entry in history.Array("islands").SelectMany(i => i.Array("entries")))
                if (history["entitiesByKey"]?[entry.S("value")] is not JsonObject) throw new ApiError(409, "会话历史尚未同步完整，请稍后刷新");
        }
        else if (state["turns"] is not JsonArray) throw new ApiError(409, "这个会话的轮次结构尚不兼容");
        foreach (var turn in StateView.Turns(state))
            if (turn.S("status") is not ("inProgress" or "completed" or "failed" or "interrupted") || turn["items"] is not JsonArray || turn["params"] is not JsonObject)
                throw new ApiError(409, "会话包含尚未支持的轮次状态，请在电脑处理此操作");
        if (state["threadRuntimeStatus"].S("type") == "active" && StateView.ActiveTurn(state).Length == 0)
            throw new ApiError(409, "电脑任务状态正在同步，请刷新后再操作；不会排队提交");
    }
    public static bool Matches(JsonNode? schema, JsonNode? value)
    {
        if (schema is not JsonObject) return false;
        if (schema["$ref"] != null || schema["not"] != null || schema["if"] != null || schema["patternProperties"] != null) return false;
        if (schema["allOf"] is JsonArray constraints && !constraints.All(s => Matches(s, value))) return false;
        if (schema["anyOf"] is JsonArray alternatives && !alternatives.Any(s => Matches(s, value))) return false;
        if (schema["oneOf"] is JsonArray variants && variants.Count(s => Matches(s, value)) != 1) return false;
        if (schema.AsObject().ContainsKey("const") && !JsonNode.DeepEquals(schema["const"], value)) return false;
        if (schema["enum"] is JsonArray options && !options.Any(o => JsonNode.DeepEquals(o, value))) return false;
        if (schema["type"] is JsonArray types && !types.Any(t => Matches(new JsonObject { ["type"] = t?.DeepClone() }, value))) return false;
        if (schema["type"] is JsonValue type)
        {
            var matches = type.ToString() switch
            {
                "object" => value is JsonObject, "array" => value is JsonArray, "null" => value == null,
                "string" => value is JsonValue v && v.TryGetValue<string>(out _),
                "boolean" => value is JsonValue b && b.TryGetValue<bool>(out _),
                "integer" => value is JsonValue i && long.TryParse(i.ToString(), out _),
                "number" => value is JsonValue n && double.TryParse(n.ToString(), out _), _ => false
            };
            if (!matches) return false;
        }
        if (value is JsonObject obj)
        {
            if (schema.Array("required").Any(k => !obj.ContainsKey(k!.ToString()))) return false;
            var properties = schema["properties"] as JsonObject;
            foreach (var property in obj)
            {
                if (properties?[property.Key] is JsonNode child) { if (!Matches(child, property.Value)) return false; }
                else if (schema["additionalProperties"] is JsonValue allowed && allowed.TryGetValue<bool>(out var permitted) && !permitted) return false;
            }
        }
        if (value is JsonArray array && schema["items"] is JsonNode itemSchema && array.Any(i => !Matches(itemSchema, i))) return false;
        return true;
    }
}
