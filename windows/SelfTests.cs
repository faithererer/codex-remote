using System.Net;
using System.Text.Json.Nodes;

namespace CodexRemote;

static class SelfTests
{
    public static void Run(string? output)
    {
        var results = new List<object>();
        void Test(string name, Action action) { try { action(); results.Add(new { name, passed = true }); } catch (Exception e) { results.Add(new { name, passed = false, error = e.Message }); } }
        void Check(bool value) { if (!value) throw new Exception("Assertion failed"); }
        var root = Path.Combine(Path.GetTempPath(), "CodexRemote-test-" + Guid.NewGuid()); var vault = new Vault(root);
        ImageTests.Run(Test, Check, vault, root);
        Test("Host identity survives restart", () => { Check(Identity.Load(vault).HostId == Identity.Load(vault).HostId); });
        Test("DPAPI roundtrip and no plaintext", () => { vault.Write("test", new { secret = "test-value-do-not-export" }); Check(vault.Read<JsonNode>("test").S("secret") == "test-value-do-not-export"); Check(!System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(root, "test.dpapi"))).Contains("test-value")); });
        Test("Only Tailscale CGNAT range", () => { Check(Program.Tailnet(IPAddress.Parse("100.64.0.1"))); Check(Program.Tailnet(IPAddress.Parse("100.127.255.254"))); foreach (var ip in new[] { "100.63.255.255", "100.128.0.1", "192.168.1.1", "127.0.0.1", "8.8.8.8", "::1" }) Check(!Program.Tailnet(IPAddress.Parse(ip))); });
        Test("LAN accepts RFC1918 boundaries and excludes public link-local multicast and loopback", () => {
            foreach (var ip in new[] { "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.254" }) Check(PrivateNetwork.IsLan(IPAddress.Parse(ip)));
            foreach (var ip in new[] { "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "100.64.1.1", "8.8.8.8", "fd00::1" }) Check(!PrivateNetwork.IsLan(IPAddress.Parse(ip)));
        });
        Test("Source policy follows the concrete listener without trusting forwarding headers", () => {
            Check(PrivateNetwork.Allows(IPAddress.Parse("192.168.1.8"), IPAddress.Parse("192.168.1.9")));
            Check(PrivateNetwork.Allows(IPAddress.Parse("100.64.0.8"), IPAddress.Parse("100.127.1.9")));
            Check(!PrivateNetwork.Allows(IPAddress.Parse("8.8.8.8"), IPAddress.Parse("192.168.1.9")));
            Check(!PrivateNetwork.Allows(IPAddress.Parse("100.64.0.8"), IPAddress.Parse("192.168.1.9")));
            Check(!PrivateNetwork.Allows(IPAddress.Parse("192.168.1.8"), IPAddress.Parse("100.64.0.9")));
            Check(!PrivateNetwork.Allows(IPAddress.Loopback, IPAddress.Loopback)); Check(!PrivateNetwork.Allows(null, IPAddress.Any));
        });
        Test("Listeners reject wildcard public loopback and mapped bind addresses", () => {
            Check(PrivateNetwork.IsListener(IPAddress.Parse("192.168.1.8"))); Check(PrivateNetwork.IsListener(IPAddress.Parse("100.64.1.8")));
            foreach (var ip in new[] { "0.0.0.0", "::", "127.0.0.1", "8.8.8.8", "::ffff:192.168.1.8" }) Check(!PrivateNetwork.IsListener(IPAddress.Parse(ip)));
            Check(PrivateNetwork.Allows(IPAddress.Parse("::ffff:192.168.1.8"), IPAddress.Parse("192.168.1.9")));
        });
        Test("Snapshot and native Immer patches", () => { var state = JsonNode.Parse("{\"a\":[1,2],\"b\":{\"c\":1}}")!; StateView.Apply(state, JsonNode.Parse("[{\"op\":\"add\",\"path\":[\"a\",1],\"value\":3},{\"op\":\"replace\",\"path\":[\"b\",\"c\"],\"value\":4},{\"op\":\"remove\",\"path\":[\"a\",0]}]")!.AsArray()); Check(state["a"]!.ToJsonString() == "[3,2]" && state["b"]!["c"]!.GetValue<int>() == 4); });
        Test("Operation dedupe survives restart", () => { var count = 0; var body = J.Node(new { operationId = Guid.NewGuid().ToString(), hostId = "host-A", threadId = "same" }); Func<Task<JsonNode>> action = () => { count++; return Task.FromResult(J.Node(new { ok = true })); }; new Operations(vault).Run(body, action).GetAwaiter().GetResult(); var result = new Operations(vault).Run(body, action).GetAwaiter().GetResult(); Check(count == 1 && result.S("status") == "accepted"); });
        Test("Same operation id different host rejected", () => { var op = new Operations(vault); var body = J.Node(new { operationId = Guid.NewGuid().ToString(), hostId = "host-A", threadId = "same" }); op.Run(body, () => Task.FromResult(J.Node(new { ok = true }))).GetAwaiter().GetResult(); body["hostId"] = "host-B"; try { op.Run(body, () => throw new Exception()).GetAwaiter().GetResult(); throw new Exception("Expected conflict"); } catch (ApiError e) { Check(e.Status == 409); } });
        Test("Uncertain dispatch never automatically retried", () => { var op = new Operations(vault); var count = 0; var body = J.Node(new { operationId = Guid.NewGuid().ToString() }); Func<Task<JsonNode>> action = () => { count++; throw new IOException(); }; op.Run(body, action).GetAwaiter().GetResult(); var result = new Operations(vault).Run(body, action).GetAwaiter().GetResult(); Check(count == 1 && result.S("status") == "uncertain"); });
        Test("Event resume and instance invalidation", () => { var events = new EventHub(); var instance = events.InstanceId; events.Publish("one", new { }); events.Publish("two", new { }); var subscription = events.Subscribe(instance, 1); Check(subscription.Replay.Length == 1 && subscription.Replay[0].S("type") == "two"); events.Remove(subscription.Channel); events.Reset(); var reset = events.Subscribe(instance, 2); Check(events.InstanceId != instance && reset.Replay[0].S("type") == "reset"); events.Remove(reset.Channel); });
        Test("Bounded event overflow requires resync", () => { var events = new EventHub(); var old = events.InstanceId; for (int i = 0; i < 300; i++) events.Publish("event", new { }); var sub = events.Subscribe(old, 1); Check(sub.Replay[0].S("type") == "reset"); events.Remove(sub.Channel); });
        Test("Pairing one-use and revocation", () => { var pairing = new Pairing(vault, Identity.Load(vault)) { Confirm = _ => Task.FromResult(true) }; var qr = JsonNode.Parse(pairing.CreateQr("https://100.64.1.1:48761"))!; var request = J.Node(new { secret = qr.S("secret"), deviceName = "test" }); var paired = J.Node(pairing.Pair(request).GetAwaiter().GetResult()); Check(pairing.Authenticate(paired.S("token")) != null); try { pairing.Pair(request).GetAwaiter().GetResult(); throw new Exception("Replay accepted"); } catch (ApiError) { } pairing.Revoke(paired.S("deviceId")); Check(pairing.Authenticate(paired.S("token")) == null); });
        Test("Unknown pairing rejected", () => { var pairing = new Pairing(vault, Identity.Load(vault)); try { pairing.Pair(J.Node(new { deviceName = "test", secret = new string('0', 64) })).GetAwaiter().GetResult(); throw new Exception("Unauthenticated device accepted"); } catch (ApiError e) { Check(e.Status == 403); } });
        Test("Runtime handshake depends on protocol not desktop version or archive hash", () => {
            var schema = JsonNode.Parse("""{"type":"object","properties":{"limit":{"type":"integer"}},"additionalProperties":false}""")!;
            Check(Compatibility.HandshakeReady(Guid.NewGuid().ToString(), schema));
            Check(!Compatibility.HandshakeReady("invalid-handshake", schema));
            Check(!Compatibility.HandshakeReady(Guid.NewGuid().ToString(), JsonNode.Parse("""{"type":"object","required":["unsupportedNewArgument"]}""")));
        });
        Test("Only explicit pre-dispatch router rejections are safe to reject without uncertainty", () => {
            foreach (var error in new[] { "no-client-found", "no-handler-for-request", "request-version-mismatch" }) Check(Compatibility.RouterRejectedBeforeDispatch(error));
            foreach (var error in new[] { "timeout", "server-closed", "no-client-found: owner became unavailable", "rollback failed" }) Check(!Compatibility.RouterRejectedBeforeDispatch(error));
        });
        Test("Native schema drift disables only arguments that do not match", () => {
            var schema = JsonNode.Parse("""{"type":"object","required":["environment"],"properties":{"environment":{"anyOf":[{"type":"object","required":["type"],"properties":{"type":{"const":"same-directory"}},"additionalProperties":false},{"type":"object","required":["type"],"properties":{"type":{"const":"worktree"}},"additionalProperties":false}]}},"additionalProperties":false}""")!;
            Check(Compatibility.Matches(schema, J.Node(new { environment = new { type = "same-directory" } })));
            Check(!Compatibility.Matches(schema, J.Node(new { environment = new { type = "new-unsupported-mode" } })));
            Check(!Compatibility.Matches(schema, J.Node(new { environment = new { type = "worktree" }, permissions = "forbidden" })));
            Check(!Compatibility.Matches(JsonNode.Parse("""{"$ref":"unknown-contract"}"""), J.Node(new { })));
        });
        Test("Live state validation rejects unknown turn or history schemas per operation", () => {
            var state = JsonNode.Parse("""{"id":"t","hostId":"local","requests":[],"turns":[{"turnId":"a","status":"completed","params":{},"items":[]}]}""")!;
            Compatibility.ValidateState(state, "t"); state["turns"]![0]!["status"] = "new-unknown-status";
            try { Compatibility.ValidateState(state, "t"); throw new Exception("Unknown running state accepted"); } catch (ApiError) { }
            state["turns"]![0]!["status"] = "completed"; state["threadRuntimeStatus"] = J.Node(new { type = "active" });
            try { Compatibility.ValidateState(state, "t"); throw new Exception("Unloaded active turn accepted"); } catch (ApiError) { }
            state["threadRuntimeStatus"] = null; state["hostId"] = "remote";
            try { Compatibility.ValidateState(state, "t"); throw new Exception("Foreign state accepted"); } catch (ApiError) { }
        });
        JsonNode EditState() => JsonNode.Parse("""{"id":"thread-a","latestModel":"a","modelProvider":"provider-a","latestReasoningEffort":"high","requests":[],"turns":[{"turnId":"turn-a","status":"completed","params":{"clientUserMessageId":"c-a","useAppServerPermissionDefault":true,"input":[{"type":"text","text":"original"}]},"items":[{"type":"userMessage","clientId":"c-a","text":"original"},{"type":"agentMessage","phase":"final_answer","text":"done"}]}]}""")!;
        Test("Native editing targets only the latest message and never overrides permissions", () => {
            var state = EditState(); var target = ThreadSettings.EditTarget(state);
            var args = ThreadSettings.EditArguments(state, J.Node(new { editTurnId = "turn-a", editFingerprint = target.S("fingerprint"), text = "edited" }));
            Check(args.S("turnId") == "turn-a" && args.S("message") == "edited" && args["allowEarlierMessages"]!.GetValue<bool>() == false && args["shouldSendPermissionOverrides"]!.GetValue<bool>() == false && args["sandboxPolicy"] == null);
        });
        Test("Edit rejects stale message fingerprint and latest turn", () => {
            var state = EditState(); var target = ThreadSettings.EditTarget(state);
            foreach (var body in new[] { J.Node(new { editTurnId = "turn-a", editFingerprint = "stale", text = "edited" }), J.Node(new { editTurnId = "turn-b", editFingerprint = target.S("fingerprint"), text = "edited" }) })
                try { ThreadSettings.EditArguments(state, body); throw new Exception("Stale edit accepted"); } catch (ApiError e) { Check(e.Status == 409); }
        });
        Test("Running turns pending approvals and later steering block editing", () => {
            var state = EditState(); state["turns"]![0]!["status"] = "inProgress"; Check(ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>() == false);
            state["turns"]![0]!["status"] = "completed"; state["requests"]!.AsArray().Add(J.Node(new { id = "approval" })); Check(!ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>());
            state["requests"]!.AsArray().Clear(); state["turns"]![0]!["items"]!.AsArray().Add(J.Node(new { type = "steeringUserMessage", text = "later" })); Check(!ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>());
        });
        Test("Editing refuses attachments and structured desktop context", () => {
            var state = EditState(); state["turns"]![0]!["params"]!["input"]!.AsArray().Add(J.Node(new { type = "localImage", path = "test" })); Check(!ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>());
            state = EditState(); state["turns"]![0]!["params"]!["input"]![0]!["text"] = "## My request:\noriginal"; Check(!ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>());
        });
        Test("Editing custom permissions stays on the desktop", () => {
            var state = EditState(); state["turns"]![0]!["params"]!["useAppServerPermissionDefault"] = false;
            Check(!ThreadSettings.EditTarget(state)["allowed"]!.GetValue<bool>() && ThreadSettings.EditTarget(state).S("reason").Contains("权限"));
        });
        Test("Only configured provider catalog reaches model picker", () => {
            var state = EditState(); var config = JsonNode.Parse("""{"models":[{"slug":"b","display_name":"B","visibility":"list","supported_reasoning_levels":[{"effort":"low"}]},{"slug":"hidden","visibility":"hide"}]}""")!;
            var choices = ThreadSettings.Catalog(state, config, true); Check(choices.Count == 2 && choices[0].S("id") == "b");
            Check(ThreadSettings.Catalog(state, config, false).Count == 1 && ThreadSettings.Catalog(state, config, false)[0].S("id") == "a");
        });
        Test("Model update uses conditional native settings without touching permissions", () => {
            var state = EditState(); var choices = new JsonArray(J.Node(new { id = "b", efforts = new[] { "low" } }));
            var args = ThreadSettings.ModelArguments(state, J.Node(new { model = "b", effort = "low", expectedModel = "a", expectedEffort = "high", expectedProvider = "provider-a" }), choices);
            Check(args["threadSettings"]!.AsObject().Count == 2 && args["threadSettings"].S("model") == "b" && args["condition"].S("ifModelEquals") == "a" && args["activeTurnId"] == null);
        });
        Test("Model update rejects foreign models stale providers and unsupported effort", () => {
            var state = EditState(); var choices = ThreadSettings.Catalog(state, null, false);
            var body = J.Node(new { model = "a", effort = "high", expectedModel = "a", expectedEffort = "high", expectedProvider = "provider-a" });
            foreach (var pair in new[] { ("model", "foreign"), ("effort", "ultra"), ("expectedProvider", "provider-b"), ("expectedModel", "stale") }) {
                var bad = body.Copy(); bad[pair.Item1] = pair.Item2;
                try { ThreadSettings.ModelArguments(state, bad, choices); throw new Exception("Invalid model update accepted"); } catch (ApiError) { }
            }
        });
        Test("Completed response includes phase and real turn status", () => {
            var state = EditState(); var view = StateView.Normalize(state, "instance"); var answer = view.Array("messages").First(i => i.S("kind") == "agentMessage");
            Check(answer.S("phase") == "final_answer" && answer.S("turnStatus") == "completed" && view.S("latestTurnId") == "turn-a" && view.S("status") == "completed");
        });
        Test("Catalog discovery handles TOML profile and rejects mismatched provider or scoped config", () => {
            var home = Path.Combine(root, "catalog-test"); Directory.CreateDirectory(home);
            var config = Path.Combine(home, "config.toml"); File.WriteAllText(config, "model_provider = \"wrong\"\nprofile = \"phone\"\n[profiles.phone]\nmodel_provider = \"provider-a\"\nmodel_catalog_json = \"models.json\"\n");
            File.WriteAllText(Path.Combine(home, "models.json"), "{\"models\":[]}");
            var old = Environment.GetEnvironmentVariable("CODEX_HOME");
            try {
                Environment.SetEnvironmentVariable("CODEX_HOME", home);
                var state = EditState(); state["cwd"] = home;
                Check(ThreadSettings.ReadCatalog(state).Matches);
                state["modelProvider"] = "other"; Check(!ThreadSettings.ReadCatalog(state).Matches);
                state["modelProvider"] = "provider-a"; Directory.CreateDirectory(Path.Combine(home, ".codex")); File.WriteAllText(Path.Combine(home, ".codex", "config.toml"), "model = \"scoped\"");
                Check(!ThreadSettings.ReadCatalog(state).Matches);
            } finally { Environment.SetEnvironmentVariable("CODEX_HOME", old); }
        });
        Test("Native initial echo replaces fallback with stable message identity", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"t","params":{"clientUserMessageId":"client-1","input":[{"type":"text","text":"same request"}]},"items":[]}]}""")!;
            var fallback = StateView.Normalize(state, "i").Array("messages")[0]!;
            state["turns"]![0]!["items"]!.AsArray().Add(JsonNode.Parse("""{"id":"native-1","type":"userMessage","clientId":"client-1","content":[{"type":"text","text":"same request"}]}"""));
            var messages = StateView.Normalize(state, "i").Array("messages");
            Check(messages.Count == 1 && messages[0].S("id") == fallback.S("id") && messages[0].S("nativeId") == "native-1" && messages[0].S("text") == "same request");
        });
        Test("Identical text in separate turns or different client messages is not deduplicated", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"a","params":{"clientUserMessageId":"c1","input":[{"type":"text","text":"repeat"}]},"items":[{"id":"n1","type":"userMessage","clientId":"c1","text":"repeat"},{"id":"n2","type":"userMessage","clientId":"c2","text":"repeat"},{"id":"s1","type":"steeringUserMessage","input":[{"type":"text","text":"repeat"}]}]},{"turnId":"b","params":{"clientUserMessageId":"c3","input":[{"type":"text","text":"repeat"}]},"items":[{"id":"n3","type":"userMessage","clientId":"c3","text":"repeat"}]}]}""")!;
            var messages = StateView.Normalize(state, "i").Array("messages");
            Check(messages.Count == 4 && messages.Select(m => m.S("id")).Distinct().Count() == 4 && messages.All(m => m.S("text") == "repeat"));
        });
        Test("Legacy echo match is scoped to initial item only", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"a","params":{"input":[{"type":"text","text":"repeat"}]},"items":[{"id":"n1","type":"userMessage","text":"repeat"},{"id":"n2","type":"userMessage","text":"repeat"}]},{"turnId":"b","params":{"input":[{"type":"text","text":"repeat"}]},"items":[{"id":"a1","type":"agentMessage","text":"reply"},{"id":"n3","type":"userMessage","text":"repeat"}]}]}""")!;
            var messages = StateView.Normalize(state, "i").Array("messages");
            Check(messages.Count == 5 && messages[0].S("nativeId") == "n1" && messages.Any(m => m.S("id") == "n2") && messages.Any(m => m.S("id") == "n3"));
        });
        Test("Different native client identity never collapses by matching text", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"t","params":{"clientUserMessageId":"initial","input":[{"type":"text","text":"repeat"}]},"items":[{"id":"other","type":"userMessage","clientId":"another","text":"repeat"}]}]}""")!;
            Check(StateView.Normalize(state, "i").Array("messages").Count == 2);
        });
        if (Environment.GetEnvironmentVariable("CODEX_TEST_SNAPSHOT") is string snapshotPath && snapshotPath.Length > 0)
        Test("Saved native snapshot has one canonical row per identified initial input", () => {
            var snapshot = JsonNode.Parse(File.ReadAllText(snapshotPath))!;
            var state = snapshot["conversationState"] ?? snapshot;
            var messages = StateView.Normalize(state, "test-only").Array("messages");
            int matched = 0;
            foreach (var turn in StateView.Turns(state))
            {
                var clientId = turn["params"].S("clientUserMessageId");
                var native = turn.Array("items").OfType<JsonNode>().FirstOrDefault(i => i.S("type") == "userMessage" && clientId.Length > 0 && i.S("clientId") == clientId);
                if (native == null || !messages.Any(m => m.S("turnId") == turn.S("turnId"))) continue;
                Check(messages.Count(m => m.S("turnId") == turn.S("turnId") && m.S("clientId") == clientId) == 1);
                Check(!messages.Any(m => m.S("id") == native.S("id")));
                matched++;
            }
            Check(matched > 0);
        });
        Test("Only latest summary of current turn is presented", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"old","status":"completed","items":[{"id":"r0","type":"reasoning","summary":["previous turn"]}]},{"turnId":"current","status":"inProgress","items":[{"id":"r1","type":"reasoning","summary":["first"]},{"id":"r2","type":"reasoning","summary":["older section","latest section",""]}]}]}""")!;
            var view = StateView.Normalize(state, "instance");
            Check(view["latestReasoning"].S("text") == "latest section" && view.Array("messages").Count(m => m.S("kind") == "reasoning") == 1);
        });
        Test("New turn never inherits old thinking status", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"old","status":"completed","items":[{"type":"reasoning","summary":["stale"]}]},{"turnId":"new","status":"inProgress","items":[]}]}""")!;
            Check(StateView.Normalize(state, "instance")["latestReasoning"] == null);
        });
        Test("Multiline command and output have independent structured fields", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"current","items":[{"id":"c","type":"commandExecution","command":"first\nsecond","aggregatedOutput":"result\nthird","exitCode":3,"status":"completed"}]}]}""")!;
            var command = StateView.Normalize(state, "instance").Array("messages")[0]!;
            Check(command.S("command") == "first\nsecond" && command.S("output") == "result\nthird" && command["exitCode"]!.GetValue<int>() == 3 && command.S("turnId") == "current");
        });
        Test("File changes preserve paths kinds and string or object diffs", () => {
            var state = JsonNode.Parse("""{"turns":[{"turnId":"current","items":[{"id":"f","type":"fileChange","status":"completed","changes":[{"path":"C:\\repo\\a.kt","kind":{"type":"update","move_path":"C:\\repo\\b.kt"},"diff":{"text":"@@ -1 +1 @@\n-old\n+new"}},{"path":"new.txt","kind":"add","diff":"@@ -0,0 +1 @@\n+added"}]}]}]}""")!;
            var message = StateView.Normalize(state, "instance").Array("messages")[0]!;
            Check(message.S("kind") == "fileChange" && message.Array("changes").Count == 2);
            Check(message.Array("changes")[0].S("diff").Contains("+new") && message.Array("changes")[0].S("movePath") == @"C:\repo\b.kt");
            Check(message.Array("changes")[1].S("kind") == "add" && message.Array("changes")[1].S("diff").Contains("+added"));
        });
        Test("File diff display is bounded and truncation is explicit", () => {
            var item = J.Node(new { changes = Enumerable.Range(0, 105).Select(i => new { path = "file" + i, kind = "update", diff = new string('+', 100000) }) });
            var changes = StateView.Changes(item);
            Check(changes.Count == 100 && changes[0]!["truncated"]!.GetValue<bool>() && changes.Sum(c => c.S("diff").Length) <= 320000);
        });
        Test("Project catalog accepts only native local project identity", () => {
            var projects = Creation.LocalProjects(JsonNode.Parse("""{"projects":[{"projectId":"local-project","projectKind":"local","label":"Repo","path":"C:\\repo","hostId":"local","isGitRepository":true},{"projectId":"remote-project","projectKind":"remote","hostId":"ssh-host"},{"projectId":"cloud","projectKind":"chatgpt","hostId":null},{"projectId":"spoof","projectKind":"local","hostId":"other-pc"}]}""")!);
            Check(projects.Count == 1 && projects[0].S("id") == "local-project" && projects[0].S("name") == "Repo");
        });
        Test("Default creation inherits desktop configuration with no arbitrary target", () => {
            var body = J.Node(new { location = "default", text = "Do work", title = "My task", model = "untrusted", cwd = @"C:\other" });
            var args = Creation.Arguments(body, []);
            Check(args["target"].S("type") == "projectless" && args.S("prompt") == "Do work" && args.S("title") == "My task");
            Check(args["model"] == null && args["cwd"] == null && args["sandbox"] == null);
        });
        Test("Project creation rejects unknown IDs and uses local environment only", () => {
            var projects = JsonNode.Parse("""[{"id":"safe"}]""")!.AsArray();
            var args = Creation.Arguments(J.Node(new { location = "project", projectId = "safe", text = "Task", environment = "worktree" }), projects);
            Check(args["target"]?["environment"].S("type") == "local");
            foreach (var body in new[] { J.Node(new { location = "project", projectId = "other", text = "Task" }), J.Node(new { location = "default", projectId = "safe", text = "Task" }), J.Node(new { location = "cloud", text = "Task" }), J.Node(new { location = "default", text = " " }) })
                try { Creation.Arguments(body, projects); throw new Exception("Expected rejection"); } catch (ApiError) { }
        });
        Test("Create operation replay returns original thread without creating again", () => {
            int calls = 0; var body = J.Node(new { type = "create", operationId = Guid.NewGuid().ToString(), hostId = "host-A", instanceId = "one", threadId = "", location = "default", text = "Task" });
            Func<Task<JsonNode>> create = () => { calls++; return Task.FromResult(J.Node(new { threadId = "created-thread" })); };
            new Operations(vault).Run(body, create).GetAwaiter().GetResult();
            var repeat = new Operations(vault).Run(body, create).GetAwaiter().GetResult();
            Check(calls == 1 && repeat["result"].S("threadId") == "created-thread");
        });
        Test("Fork arguments explicitly target the source and never forward settings or paths", () => {
            var state = JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","turns":[{"turnId":"latest","status":"completed"}]}""")!;
            var context = Forking.Context(state, "i", @"C:\repo");
            var args = Forking.Arguments(J.Node(new { threadId = "source", instanceId = "i", latestTurnId = "latest", turnId = "", environment = "same-directory", cwd = "C:/injected", model = "injected" }), context);
            Check(args.S("threadId") == "source" && args["environment"].S("type") == "same-directory" && args.AsObject().Count == 2);
        });
        Test("Fork rejects wrong source stale instance and changed turn", () => {
            var context = Forking.Context(JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","turns":[{"turnId":"latest","status":"completed"}]}""")!, "i", @"C:\repo");
            foreach (var field in new[] { "threadId", "instanceId", "latestTurnId", "turnId" }) {
                var body = J.Node(new { threadId = "source", instanceId = "i", latestTurnId = "latest", turnId = "", environment = "same-directory" }); body[field] = "changed";
                try { Forking.Arguments(body, context); throw new Exception("Stale fork accepted"); } catch (ApiError e) { Check(e.Status == 409); }
            }
        });
        Test("Fork never interrupts active or ephemeral source", () => {
            foreach (var state in new[] { JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","turns":[{"turnId":"active","status":"inProgress"}]}""")!, JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","ephemeral":true}""")!, JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","requests":[{"id":"pending"}]}""")! }) {
                var context = Forking.Context(state, "i", @"C:\repo"); Check(context["canFork"]!.GetValue<bool>() == false && context.S("reason").Length > 0);
                try { Forking.Arguments(J.Node(new { threadId = "source", instanceId = "i", environment = "same-directory" }), context); throw new Exception("Unsafe fork accepted"); } catch (ApiError) { }
            }
        });
        Test("Worktree fork requires a Git root and exact environment choice", () => {
            var state = JsonNode.Parse("""{"id":"source","cwd":"C:\\repo","turns":[]}""")!;
            var body = J.Node(new { threadId = "source", instanceId = "i", latestTurnId = "", turnId = "", environment = "worktree" });
            Check(Forking.Arguments(body, Forking.Context(state, "i", @"C:\repo"))["environment"].S("type") == "worktree");
            try { Forking.Arguments(body, Forking.Context(state, "i", "")); throw new Exception("Non-Git worktree accepted"); } catch (ApiError) { }
            body["environment"] = "remote";
            try { Forking.Arguments(body, Forking.Context(state, "i", @"C:\repo")); throw new Exception("Unknown environment accepted"); } catch (ApiError) { }
        });
        Test("Git root detection understands a linked worktree dot-git file", () => {
            var repo = Path.Combine(root, "repo"); Directory.CreateDirectory(Path.Combine(repo, "nested")); File.WriteAllText(Path.Combine(repo, ".git"), "gitdir: unused-test-path");
            Check(Forking.GitRoot(Path.Combine(repo, "nested")) == repo && Forking.GitRoot("relative") == "" && Forking.GitRoot(@"\\server\share") == "");
        });
        Test("Fork accepted or queued operation survives replay without another fork", () => {
            int calls = 0; var body = J.Node(new { type = "fork", hostId = "A", instanceId = "i", threadId = "same-source", operationId = Guid.NewGuid(), environment = "worktree" });
            Func<Task<JsonNode>> fork = () => { calls++; return Task.FromResult(J.Node(new { status = "queued", clientThreadId = "pending-not-real-thread", threadId = (string?)null })); };
            new Operations(vault).Run(body, fork).GetAwaiter().GetResult();
            var repeat = new Operations(vault).Run(body, fork).GetAwaiter().GetResult();
            Check(calls == 1 && repeat["result"].S("clientThreadId") == "pending-not-real-thread" && repeat["result"]?["threadId"] == null);
        });
        File.WriteAllText(output ?? Path.Combine(root, "test-results.json"), J.Node(new { results, realTwoPcTested = false, realAndroidTested = false }).ToJsonString(new() { WriteIndented = true }));
        // Keep the unique DPAPI test directory outside deliverables; it contains no production credentials.
    }
}
