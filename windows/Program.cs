using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Http.Features;
using QRCoder;

namespace CodexRemote;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        var data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CodexRemote");
        if (args.Contains("--self-test")) { SelfTests.Run(args.LastOrDefault(a => a.EndsWith(".json"))); return; }
        if (args.Contains("--api-test")) { ApiTests.Run(args.LastOrDefault(a => a.EndsWith(".json"))).GetAwaiter().GetResult(); return; }
        if (args.Contains("--phone-test")) { PhoneTest(args.Last(a => a.EndsWith(".json"))).GetAwaiter().GetResult(); return; }
        if (args.Contains("--smoke"))
        {
            var result = Smoke().GetAwaiter().GetResult();
            var path = args.LastOrDefault(a => a.EndsWith(".json")) ?? Path.Combine(Path.GetTempPath(), "codex-remote-smoke.json");
            File.WriteAllText(path, result.ToJsonString(new() { WriteIndented = true })); return;
        }
        using var mutex = new Mutex(true, @"Local\CodexRemote-" + Environment.UserName, out var first);
        if (!first) { MessageBox.Show("桥接程序已在托盘运行，请双击托盘图标。", "Codex 远程控制"); return; }
        ApplicationConfiguration.Initialize();
        try
        {
            var vault = new Vault(data); var identity = Identity.Load(vault); var pairing = new Pairing(vault, identity); var events = new EventHub(); var desktop = new Desktop(events, vault);
            using var cancel = new CancellationTokenSource();
            using var window = new MainWindow(identity, pairing, desktop, vault);
            pairing.Confirm = name => window.ConfirmPair(name);
            _ = window.Handle;
            var server = Task.Run(() => ListenNetworks(identity, pairing, events, desktop, vault, window, cancel.Token));
            _ = Task.Run(() => desktop.Monitor(cancel.Token));
            _ = server.ContinueWith(t => { if (t.IsFaulted) window.SetError("服务无法启动（端口或防火墙）：" + t.Exception?.GetBaseException().Message); });
            Application.Run(window);
            cancel.Cancel(); desktop.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }
        catch (Exception e) { MessageBox.Show(e.Message, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }
    public static bool Tailnet(IPAddress? address) => PrivateNetwork.IsTailscale(address);
    static async Task ListenNetworks(Identity identity, Pairing pairing, EventHub events, Desktop desktop, Vault vault, MainWindow window, CancellationToken cancellation)
    {
        var operations = new Operations(vault);
        Task? listener = null; CancellationTokenSource? connection = null; string current = "";
        try
        {
            while (!cancellation.IsCancellationRequested)
            {
                var endpoints = PrivateNetwork.Endpoints();
                var key = string.Join("|", endpoints.Select(e => e.Address));
                if (key != current || listener?.IsCompleted == true)
                {
                    if (connection != null) { connection.Cancel(); try { if (listener != null) await listener; } catch { } connection.Dispose(); connection = null; }
                    current = key; listener = null;
                    window.SetNetworks([]);
                    if (endpoints.Length > 0)
                    {
                        connection = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                        // Both transports share one operation journal and lock, including across rebinds.
                        listener = StartServer(endpoints.Select(e => e.Address).ToArray(), identity, pairing, events, desktop, vault, connection.Token, operations: operations, started: () => window.SetNetworks(endpoints));
                        _ = listener.ContinueWith(t => { if (t.IsFaulted) window.SetError("网络监听无法启动：请检查端口占用或本机地址，5 秒后重试。"); }, TaskScheduler.Default);
                    }
                }
                await Task.Delay(5000, cancellation);
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        finally { if (connection != null) { connection.Cancel(); try { if (listener != null) await listener; } catch { } connection.Dispose(); } }
    }

    internal static Task StartServer(IPAddress ip, Identity identity, Pairing pairing, EventHub events, Desktop desktop, Vault vault, CancellationToken cancellation, int port = 48761)
        => StartServer([ip], identity, pairing, events, desktop, vault, cancellation, port);
    internal static async Task StartServer(IReadOnlyList<IPAddress> addresses, Identity identity, Pairing pairing, EventHub events, Desktop desktop, Vault vault, CancellationToken cancellation, int port = 48761, Operations? operations = null, Action? started = null)
    {
        if (addresses.Count == 0 || addresses.Any(ip => !PrivateNetwork.IsListener(ip))) throw new InvalidOperationException("只允许监听局域网或 Tailscale 的具体 IPv4 地址");
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders(); // Avoid logging bearer credentials, prompts, or pairing secrets.
        builder.WebHost.ConfigureKestrel(options => { options.Limits.MaxRequestBodySize = 192 * 1024; options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10); foreach (var ip in addresses.Distinct()) options.Listen(ip, port, endpoint => { endpoint.Protocols = HttpProtocols.Http1AndHttp2; endpoint.UseHttps(identity.GetCertificate()); }); });
        await using var app = builder.Build(); operations ??= new Operations(vault); var pairAttempts = new System.Collections.Concurrent.ConcurrentDictionary<string, (DateTimeOffset Window, int Count)>();
        if (started != null) app.Lifetime.ApplicationStarted.Register(started);
        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            try
            {
                if (!PrivateNetwork.Allows(context.Connection.RemoteIpAddress, context.Connection.LocalIpAddress)) throw new ApiError(403, "仅接受当前局域网或 Tailscale 私网的对应连接");
                if (context.Request.Path != "/api/v1/pair")
                {
                    var header = context.Request.Headers.Authorization.ToString();
                    var device = header.StartsWith("Bearer ", StringComparison.Ordinal) ? pairing.Authenticate(header[7..]) : null;
                    if (device is null) throw new ApiError(401, "请重新配对，此设备未授权或已撤销");
                    context.Items["device"] = device;
                }
                await next(context);
            }
            catch (ApiError e) { if (!context.Response.HasStarted) { context.Response.StatusCode = e.Status; await context.Response.WriteAsJsonAsync(new { error = e.Message }); } }
            catch (Exception) { if (!context.Response.HasStarted) { context.Response.StatusCode = 503; await context.Response.WriteAsJsonAsync(new { error = "电脑暂时无法完成请求；请刷新状态，不要自动重发指令。" }); } }
        });
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
        app.MapPost("/api/v1/pair", async (HttpContext context, JsonNode body) =>
        {
            var remote = context.Connection.RemoteIpAddress!.ToString(); var now = DateTimeOffset.UtcNow;
            var attempt = pairAttempts.AddOrUpdate(remote, (now, 1), (_, old) => now - old.Window > TimeSpan.FromMinutes(1) ? (now, 1) : (old.Window, old.Count + 1));
            if (attempt.Count > 4) throw new ApiError(429, "配对尝试过多，请一分钟后再试");
            if (body.S("hostId") != identity.HostId) throw new ApiError(409, "电脑身份不匹配");
            return Results.Json(await pairing.Pair(body));
        });
        app.MapGet("/api/v1/status", () => new { hostId = identity.HostId, instanceId = events.InstanceId, name = identity.Name, desktopConnected = desktop.Connected, compatible = desktop.Compatible, desktopVersion = desktop.Version, knownBuild = desktop.KnownBuild, compatibilityMode = "runtime-protocol", compatibilityNote = desktop.CompatibilityNote, error = desktop.LastError, protocolVersion = 1 });
        app.MapGet("/api/v1/threads", async () => new { hostId = identity.HostId, instanceId = events.InstanceId, threads = await desktop.Catalog() });
        app.MapGet("/api/v1/projects", async () => new { hostId = identity.HostId, instanceId = events.InstanceId, projects = await desktop.Projects(), defaultLocation = "Codex 默认目录（由电脑创建独立工作目录）" });
        app.MapGet("/api/v1/threads/{id}", async (string id) => await desktop.History(id));
        app.MapGet("/api/v1/threads/{id}/fork-context", async (string id) => { var context = await desktop.ForkContext(id); context["hostId"] = identity.HostId; return context; });
        app.MapGet("/api/v1/threads/{id}/models", async (string id) => { var models = await desktop.Models(id); models["hostId"] = identity.HostId; return models; });
        app.MapPost("/api/v1/threads/{id}/images/{imageId}", async (string id, string imageId, HttpContext context) =>
        {
            if (context.Request.Query["hostId"] != identity.HostId || context.Request.Query["instanceId"] != events.InstanceId) throw new ApiError(409, "电脑或实例已变化，请刷新");
            if (!Guid.TryParseExact(imageId, "D", out _) || !Guid.TryParse(id, out _)) throw new ApiError(400, "图片或会话标识无效");
            var limit = context.Features.Get<IHttpMaxRequestBodySizeFeature>(); if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = Images.MaxBytes;
            if (context.Request.ContentLength > Images.MaxBytes) throw new ApiError(413, "单张图片上限 10 MB");
            var bytes = await Images.ReadBounded(context.Request.Body, context.RequestAborted);
            return await desktop.UploadImage(id, context.Request.Query["instanceId"].ToString(), ((Device)context.Items["device"]!).Id, imageId, bytes);
        });
        app.MapGet("/api/v1/threads/{id}/images/{imageId}", async (string id, string imageId, HttpContext context) =>
        {
            if (context.Request.Query["hostId"] != identity.HostId) throw new ApiError(409, "电脑身份不匹配");
            var image = await desktop.ReadImage(id, context.Request.Query["instanceId"].ToString(), imageId);
            context.Response.Headers.XContentTypeOptions = "nosniff";
            return Results.Bytes(image.Bytes, image.Mime);
        });
        app.MapGet("/api/v1/operations/{id}", (string id) => operations.Lookup(id) is JsonNode value ? Results.Json(value) : Results.NotFound(new { status = "notFound", warning = "未记录并不代表可以自动重发；请核对会话" }));
        app.MapPost("/api/v1/operations", async (JsonNode body, HttpContext context) =>
        {
            if (body.S("hostId") != identity.HostId || body.S("instanceId") != events.InstanceId) throw new ApiError(409, "电脑或实例已变化，请刷新后重新确认操作");
            if (body.S("type") == "create") { if (body.S("threadId").Length > 0) throw new ApiError(400, "新建操作不能指定已有会话"); }
            else if (!Guid.TryParse(body.S("threadId"), out _)) throw new ApiError(400, "会话标识无效");
            if (body["imageIds"] != null && body.S("type") is not ("send" or "steer")) throw new ApiError(400, "只有发送或追加消息可以附带图片");
            return await operations.Run(body, async () => { if (body.S("instanceId") != events.InstanceId) throw new ApiError(409, "实例已变化"); return await desktop.Control(body, ((Device)context.Items["device"]!).Id); });
        });
        app.Map("/api/v1/events", async context =>
        {
            if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
            var device = (Device)context.Items["device"]!;
            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            _ = long.TryParse(context.Request.Query["after"], out var after);
            var subscription = events.Subscribe(context.Request.Query["instance"].ToString(), after);
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted, cancellation);
            async Task Send(JsonNode node) => await socket.SendAsync(Encoding.UTF8.GetBytes(node.ToJsonString()), WebSocketMessageType.Text, true, linked.Token);
            try
            {
                foreach (var item in subscription.Replay) await Send(item);
                var incoming = Task.Run(async () =>
                {
                    var bytes = new byte[1024];
                    try
                    {
                        while (!linked.IsCancellationRequested)
                        {
                            var read = await socket.ReceiveAsync(bytes, linked.Token);
                            if (read.MessageType == WebSocketMessageType.Close) { await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "closed", CancellationToken.None); break; }
                            if (read.Count > 0) { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "Events are read-only", CancellationToken.None); break; }
                        }
                    }
                    catch { }
                    finally { linked.Cancel(); }
                });
                while (!linked.IsCancellationRequested)
                {
                    if (pairing.Devices.All(d => d.Id != device.Id)) { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "配对已撤销", CancellationToken.None); break; }
                    using var heartbeat = CancellationTokenSource.CreateLinkedTokenSource(linked.Token); heartbeat.CancelAfter(TimeSpan.FromSeconds(15));
                    try { var item = await subscription.Channel.Reader.ReadAsync(heartbeat.Token); await Send(item); }
                    catch (OperationCanceledException) when (!linked.IsCancellationRequested) { await Send(J.Node(new { type = "heartbeat", instanceId = events.InstanceId })); }
                }
            }
            catch { }
            finally { linked.Cancel(); events.Remove(subscription.Channel); }
        });
        await app.RunAsync(cancellation);
    }
    static async Task<JsonNode> Smoke()
    {
        var hub = new EventHub(); await using var desktop = new Desktop(hub); var stopwatch = Stopwatch.StartNew();
        try
        {
            var catalog = await desktop.Catalog();
            var active = catalog.OfType<JsonNode>().FirstOrDefault(n => n.S("status") == "active") ?? catalog.FirstOrDefault();
            var state = active == null ? null : await desktop.Snapshot(active.S("id"), true);
            if (state != null) Compatibility.ValidateState(state, active!.S("id"));
            var models = active == null ? null : await desktop.Models(active.S("id"));
            return J.Node(new { success = true, desktop.Compatible, desktop.Version, desktop.KnownBuild, compatibilityMode = "runtime-protocol", threadCount = catalog.Count, originalDesktopStateRead = state != null, nativeStateSchemaValidated = state != null, turnCount = state == null ? 0 : StateView.Turns(state).Count(), desktopRequestsReadable = state?["requests"] is JsonArray, modelPreserved = state?.S("latestModel").Length > 0, permissionSettingsPresent = state?["currentPermissions"] != null, modelChoiceCount = models.Array("models").Count, modelSettingsReadOnly = true, elapsedMs = stopwatch.ElapsedMilliseconds, commandsSent = 0, twoPcAccepted = false, phoneAccepted = false });
        }
        catch (Exception e) { return J.Node(new { success = false, error = e.Message }); }
    }
    static async Task PhoneTest(string output)
    {
        var ip = PrivateNetwork.Endpoints().FirstOrDefault()?.Address ?? throw new Exception("No private IPv4 address");
        var root = Path.Combine(Path.GetTempPath(), "CodexRemote-phone-test-" + Guid.NewGuid());
        var va = new Vault(Path.Combine(root, "a")); var vb = new Vault(Path.Combine(root, "b")); var ia = Identity.Load(va); var ib = Identity.Load(vb);
        ia.Name = "TEST PC A"; ib.Name = "TEST PC B";
        var pa = new Pairing(va, ia) { Confirm = _ => Task.FromResult(true) }; var pb = new Pairing(vb, ib) { Confirm = _ => Task.FromResult(true) };
        var ea = new EventHub(); var eb = new EventHub(); await using var da = new Desktop(ea); await using var db = new Desktop(eb);
        using var cancel = new CancellationTokenSource(TimeSpan.FromMinutes(5));
        var sa = StartServer(ip, ia, pa, ea, da, va, cancel.Token, 48762); var sb = StartServer(ip, ib, pb, eb, db, vb, cancel.Token, 48763);
        var monitors = Task.WhenAll(da.Monitor(cancel.Token), db.Monitor(cancel.Token));
        File.WriteAllText(output, new JsonArray(JsonNode.Parse(pa.CreateQr($"https://{ip}:48762")), JsonNode.Parse(pb.CreateQr($"https://{ip}:48763"))).ToJsonString());
        try { await Task.WhenAll(sa, sb, monitors); } catch (OperationCanceledException) { }
    }
}

sealed class MainWindow : Form
{
    readonly Identity identity; readonly Pairing pairing; readonly Desktop desktop; readonly Vault vault;
    readonly PictureBox qr = new() { Width = 320, Height = 320, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.White };
    readonly Label status = new() { AutoSize = false, Height = 76, Dock = DockStyle.Top };
    readonly Label address = new() { AutoSize = false, Height = 42, Dock = DockStyle.Top };
    readonly ComboBox networks = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 550, DisplayMember = "Label" };
    readonly ListBox devices = new() { Height = 90, Dock = DockStyle.Top, DisplayMember = "Name" };
    readonly NotifyIcon tray;
    readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    string code = "", failure = "", preferredAddress = ""; DateTimeOffset expires;
    bool exiting;
    string Endpoint => (networks.SelectedItem as NetworkEndpoint)?.Url ?? "";
    public MainWindow(Identity identity, Pairing pairing, Desktop desktop, Vault vault)
    {
        this.identity = identity; this.pairing = pairing; this.desktop = desktop; this.vault = vault;
        Text = "Codex 远程控制 · 电脑桥接"; Width = 620; Height = 900; MinimumSize = new Size(530, 800); Font = new Font("Microsoft YaHei UI", 10); BackColor = Color.FromArgb(243, 246, 242);
        var root = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(22), AutoScroll = true };
        Controls.Add(root);
        root.Controls.Add(new Label { Text = "把这台电脑带到手机上", Font = new Font(Font.FontFamily, 20, FontStyle.Bold), Width = 550, Height = 46 });
        var name = new TextBox { Text = identity.Name, Width = 360 };
        var rename = new Button { Text = "保存电脑名", AutoSize = true }; rename.Click += (_, _) => { var value = name.Text.Trim(); if (value.Length is < 1 or > 60) return; identity.Name = value; vault.Write("identity", identity); RefreshPair(); };
        var nameRow = new FlowLayoutPanel { Width = 550, Height = 40 }; nameRow.Controls.Add(name); nameRow.Controls.Add(rename); root.Controls.Add(nameRow);
        status.Width = 550; root.Controls.Add(status); address.Width = 550; root.Controls.Add(address);
        root.Controls.Add(new Label { Text = "选择配对网络：同一局域网无需 Tailscale，外出时选 Tailscale", AutoSize = true });
        root.Controls.Add(networks); networks.SelectedIndexChanged += (_, _) => { if (Endpoint.Length > 0) preferredAddress = Endpoint; RefreshPair(); };
        root.Controls.Add(qr);
        var buttons = new FlowLayoutPanel { Width = 550, Height = 46 };
        var refresh = new Button { Text = "刷新配对码", AutoSize = true }; refresh.Click += (_, _) => RefreshPair(); buttons.Controls.Add(refresh);
        var copy = new Button { Text = "复制配对码", AutoSize = true }; copy.Click += (_, _) => { if (DateTimeOffset.UtcNow < expires && code.Length > 0) Clipboard.SetText(code); }; buttons.Controls.Add(copy);
        var copyAddress = new Button { Text = "复制连接地址", AutoSize = true }; copyAddress.Click += (_, _) => { if (Endpoint.Length > 0) Clipboard.SetText(Endpoint); }; buttons.Controls.Add(copyAddress);
        var launch = new Button { Text = "打开 Codex", AutoSize = true }; launch.Click += (_, _) => Process.Start(new ProcessStartInfo("explorer.exe", @"shell:AppsFolder\OpenAI.Codex_2p2nqsd0c76g0!App") { UseShellExecute = true }); buttons.Controls.Add(launch); root.Controls.Add(buttons);
        root.Controls.Add(new Label { Text = "已配对设备（撤销立即断开，不影响其他电脑）", AutoSize = true });
        devices.Width = 550; root.Controls.Add(devices);
        var revoke = new Button { Text = "撤销选中设备", AutoSize = true }; revoke.Click += (_, _) => { if (devices.SelectedItem is Device device && MessageBox.Show(this, "撤销「" + device.Name + "」的手机权限？", "确认", MessageBoxButtons.YesNo) == DialogResult.Yes) pairing.Revoke(device.Id); }; root.Controls.Add(revoke);
        root.Controls.Add(new Label { Text = "手机与电脑在同一局域网即可连接；Tailscale 用于外出访问。\n只监听私网，仍需扫码和电脑确认。关闭窗口后在托盘运行。", Width = 550, Height = 60 });
        tray = new NotifyIcon { Icon = SystemIcons.Application, Text = "Codex 远程控制", Visible = true };
        var menu = new ContextMenuStrip(); menu.Items.Add("显示配对窗口", null, (_, _) => ShowWindow()); menu.Items.Add("退出桥接", null, (_, _) => { exiting = true; Close(); }); tray.ContextMenuStrip = menu; tray.DoubleClick += (_, _) => ShowWindow();
        Shown += (_, _) => { RefreshPair(); RefreshDevices(); }; FormClosing += (_, e) => { if (!exiting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };
        pairing.Changed += () => { if (IsHandleCreated) BeginInvoke(RefreshDevices); };
        timer.Tick += (_, _) => { address.Text = Endpoint.Length == 0 ? "尚无可用私网地址：连接 Wi-Fi／网线或 Tailscale 后会自动检测。" : Endpoint + " · 配对码剩余 " + Math.Max(0, (int)(expires - DateTimeOffset.UtcNow).TotalSeconds) + " 秒"; status.Text = failure.Length > 0 ? failure : desktop.Connected ? (desktop.Compatible ? "已接入原 Codex 桌面实例 · " + desktop.CompatibilityNote + "\n" : "桌面已连接，正在检查原生协议\n") + desktop.LastError : desktop.LastError; if (DateTimeOffset.UtcNow >= expires && qr.Image != null) { qr.Image.Dispose(); qr.Image = null; code = ""; } }; timer.Start();
    }
    void ShowWindow() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    public void SetError(string value) { if (IsHandleCreated) BeginInvoke(() => { failure = value; }); }
    public void SetNetworks(NetworkEndpoint[] endpoints)
    {
        if (!IsHandleCreated) return;
        BeginInvoke(() => {
            var previous = Endpoint.Length > 0 ? Endpoint : preferredAddress; networks.Items.Clear(); networks.Items.AddRange(endpoints);
            var index = Array.FindIndex(endpoints, e => e.Url == previous);
            networks.SelectedIndex = index >= 0 ? index : endpoints.Length > 0 ? 0 : -1;
            failure = ""; RefreshPair();
        });
    }
    void RefreshDevices() { devices.Items.Clear(); devices.Items.AddRange(pairing.Devices); }
    void RefreshPair()
    {
        qr.Image?.Dispose(); qr.Image = null;
        code = "";
        if (Endpoint.Length == 0) return;
        code = pairing.CreateQr(Endpoint); expires = DateTimeOffset.UtcNow.AddMinutes(5);
        using var generator = new QRCodeGenerator(); using var data = generator.CreateQrCode(code, QRCodeGenerator.ECCLevel.M); using var png = new PngByteQRCode(data);
        using var memory = new MemoryStream(png.GetGraphic(5)); using var image = Image.FromStream(memory); qr.Image = new Bitmap(image);
    }
    public Task<bool> ConfirmPair(string deviceName)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        BeginInvoke(() => { ShowWindow(); completion.TrySetResult(MessageBox.Show(this, "允许「" + deviceName + "」查看并操作这台电脑上的 Codex？\n请核对这是你刚才扫码的手机。", "手机请求配对", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes); });
        return completion.Task.WaitAsync(TimeSpan.FromMinutes(2)).ContinueWith(t => t.Status == TaskStatus.RanToCompletion && t.Result);
    }
    protected override void Dispose(bool disposing) { if (disposing) { timer.Dispose(); tray?.Dispose(); qr.Image?.Dispose(); } base.Dispose(disposing); }
}
