using System.Net;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace CodexRemote;

static class ApiTests
{
    public static async Task Run(string? output)
    {
        var results = new List<object>();
        async Task Test(string name, Func<Task> action) { try { await action(); results.Add(new { name, passed = true }); } catch (Exception e) { results.Add(new { name, passed = false, error = e.ToString() }); } }
        static void Check(bool value, string message = "Assertion failed") { if (!value) throw new Exception(message); }
        var ip = Program.TailscaleAddress();
        if (ip is null) { File.WriteAllText(output!, J.Node(new { skipped = "No local Tailscale IPv4", realTwoPcTested = false }).ToJsonString()); return; }
        var root = Path.Combine(Path.GetTempPath(), "CodexRemote-api-test-" + Guid.NewGuid());
        var va = new Vault(Path.Combine(root, "a")); var vb = new Vault(Path.Combine(root, "b"));
        var ia = Identity.Load(va); var ib = Identity.Load(vb);
        var pa = new Pairing(va, ia) { Confirm = _ => Task.FromResult(true) }; var pb = new Pairing(vb, ib) { Confirm = _ => Task.FromResult(true) };
        var ea = new EventHub(); var eb = new EventHub(); await using var da = new Desktop(ea); await using var db = new Desktop(eb);
        using var cancel = new CancellationTokenSource();
        var sa = Program.StartServer(ip, ia, pa, ea, da, va, cancel.Token, 48762); var sb = Program.StartServer(ip, ib, pb, eb, db, vb, cancel.Token, 48763);
        HttpClient Client(Identity id, int port, bool wrongPin = false) => new(new HttpClientHandler { UseProxy = false, ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert != null && Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant() == (wrongPin ? new string('0', 64) : id.Fingerprint) }) { BaseAddress = new Uri($"https://{ip}:{port}"), Timeout = TimeSpan.FromSeconds(10) };
        using var a = Client(ia, 48762); using var b = Client(ib, 48763);
        static async Task<HttpResponseMessage> Post(HttpClient client, string path, JsonNode body) => await client.PostAsync(path, new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
        JsonNode Qr(Pairing pairing, Identity identity, int port) => JsonNode.Parse(pairing.CreateQr($"https://{ip}:{port}"))!;
        JsonNode Body(JsonNode code, Identity identity) => J.Node(new { hostId = identity.HostId, secret = code.S("secret"), deviceName = "Isolated protocol test" });
        string ta = "", tb = "", deviceA = "";
        try
        {
            await Task.Delay(500);
            await Test("Unauthenticated HTTPS denied", async () => { using var response = await a.GetAsync("/api/v1/status"); Check(response.StatusCode == HttpStatusCode.Unauthorized); });
            await Test("Pinned HTTPS certificate mismatch rejected", async () => { using var bad = Client(ia, 48762, true); try { await bad.GetAsync("/api/v1/status"); throw new Exception("Certificate mismatch accepted"); } catch (HttpRequestException) { } });
            var qa = Qr(pa, ia, 48762); var qb = Qr(pb, ib, 48763);
            await Test("Each independent host pairs over pinned HTTPS", async () =>
            {
                using var ra = await Post(a, "/api/v1/pair", Body(qa, ia)); using var rb = await Post(b, "/api/v1/pair", Body(qb, ib));
                Check(ra.IsSuccessStatusCode && rb.IsSuccessStatusCode);
                var ja = JsonNode.Parse(await ra.Content.ReadAsStringAsync())!; var jb = JsonNode.Parse(await rb.Content.ReadAsStringAsync())!;
                ta = ja.S("token"); tb = jb.S("token"); deviceA = ja.S("deviceId");
                Check(ta != tb && ja.S("hostId") != jb.S("hostId"));
                a.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", ta); b.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", tb);
            });
            await Test("Used QR cannot pair twice", async () => { using var response = await Post(a, "/api/v1/pair", Body(qa, ia)); Check(response.StatusCode == HttpStatusCode.Forbidden); });
            await Test("Host A credentials rejected by host B", async () => { using var other = Client(ib, 48763); other.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", ta); using var response = await other.GetAsync("/api/v1/status"); Check(response.StatusCode == HttpStatusCode.Unauthorized); });
            await Test("Identical thread IDs cannot route operation to other host", async () => { using var response = await Post(b, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = eb.InstanceId, threadId = "11111111-1111-4111-8111-111111111111", operationId = Guid.NewGuid(), type = "interrupt", turnId = "test-never-dispatched" })); Check(response.StatusCode == HttpStatusCode.Conflict); });
            await Test("Old backend instance cannot dispatch", async () => { var old = ea.InstanceId; ea.Reset(); using var response = await Post(a, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = old, threadId = Guid.NewGuid(), operationId = Guid.NewGuid(), type = "send", text = "MUST NEVER DISPATCH" })); Check(response.StatusCode == HttpStatusCode.Conflict); });
            await Test("Create cannot target another host or stale instance", async () => {
                using var foreign = await Post(b, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = eb.InstanceId, threadId = "", operationId = Guid.NewGuid(), type = "create", location = "default", text = "MUST NEVER CREATE" }));
                using var stale = await Post(a, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = "stale", threadId = "", operationId = Guid.NewGuid(), type = "create", location = "default", text = "MUST NEVER CREATE" }));
                Check(foreign.StatusCode == HttpStatusCode.Conflict && stale.StatusCode == HttpStatusCode.Conflict);
            });
            await Test("Create cannot be submitted against an existing conversation", async () => { using var response = await Post(a, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = ea.InstanceId, threadId = Guid.NewGuid(), operationId = Guid.NewGuid(), type = "create", location = "default", text = "MUST NEVER CREATE" })); Check(response.StatusCode == HttpStatusCode.BadRequest); });
            await Test("Fork cannot cross hosts or reuse a stale backend", async () => {
                using var foreign = await Post(b, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = eb.InstanceId, threadId = Guid.NewGuid(), operationId = Guid.NewGuid(), type = "fork", environment = "same-directory" }));
                using var stale = await Post(a, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = "stale", threadId = Guid.NewGuid(), operationId = Guid.NewGuid(), type = "fork", environment = "worktree" }));
                Check(foreign.StatusCode == HttpStatusCode.Conflict && stale.StatusCode == HttpStatusCode.Conflict);
            });
            await Test("Fork requires a real source thread identity", async () => { using var response = await Post(a, "/api/v1/operations", J.Node(new { hostId = ia.HostId, instanceId = ea.InstanceId, threadId = "", operationId = Guid.NewGuid(), type = "fork", environment = "same-directory" })); Check(response.StatusCode == HttpStatusCode.BadRequest); });
            await Test("Fork context cannot be read without pairing", async () => { using var stranger = Client(ia, 48762); using var response = await stranger.GetAsync("/api/v1/threads/11111111-1111-4111-8111-111111111111/fork-context"); Check(response.StatusCode == HttpStatusCode.Unauthorized); });
            await Test("WSS subscription multiplexes host events", async () =>
            {
                using var socket = new ClientWebSocket(); socket.Options.Proxy = null;
                socket.Options.SetRequestHeader("Authorization", "Bearer " + ta);
                socket.Options.RemoteCertificateValidationCallback = (_, cert, _, _) => cert != null && Convert.ToHexString(SHA256.HashData(cert.GetRawCertData())).ToLowerInvariant() == ia.Fingerprint;
                await socket.ConnectAsync(new Uri($"wss://{ip}:48762/api/v1/events?instance={ea.InstanceId}&after=0"), CancellationToken.None);
                ea.Publish("threadUpdated", new { threadId = "same-id", status = "active" });
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5)); var buffer = new byte[4096];
                JsonNode eventData;
                do { var received = await socket.ReceiveAsync(buffer, timeout.Token); eventData = JsonNode.Parse(buffer.AsSpan(0, received.Count))!; } while (eventData.S("type") == "reset");
                Check(eventData.S("type") == "threadUpdated" && eventData.S("instanceId") == ea.InstanceId);
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "test done", timeout.Token);
            });
            await Test("Revoking A leaves B authorized", async () => { pa.Revoke(deviceA); using var ra = await a.GetAsync("/api/v1/status"); using var rb = await b.GetAsync("/api/v1/status"); Check(ra.StatusCode == HttpStatusCode.Unauthorized && rb.IsSuccessStatusCode); });
        }
        finally { cancel.Cancel(); try { await Task.WhenAll(sa, sb); } catch (OperationCanceledException) { } }
        File.WriteAllText(output ?? Path.Combine(root, "results.json"), J.Node(new { results, testTopology = "Two isolated bridge identities on one real PC, two private HTTPS ports; not two physical PCs", liveCommandsSent = 0, realTwoPcTested = false, realAndroidTested = false }).ToJsonString(new() { WriteIndented = true }));
    }
}
