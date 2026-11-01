using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Collections.Concurrent;
using System.Threading.Channels;

namespace CodexRemote;

static class J
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web) { WriteIndented = false };
    public static JsonNode Node(object value) => JsonSerializer.SerializeToNode(value, Options)!;
    public static string S(this JsonNode? n, string key, string fallback = "") => n?[key]?.ToString() ?? fallback;
    public static JsonArray Array(this JsonNode? n, string key) => n?[key] as JsonArray ?? [];
    public static JsonNode Copy(this JsonNode n) => n.DeepClone();
    public static string Hash(string input) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(input))).ToLowerInvariant();
    public static string Secret() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
    public static bool Equal(string a, string b) => CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}

sealed class ApiError(int status, string message, bool beforeDispatch = false) : Exception(message) { public int Status { get; } = status; public bool BeforeDispatch { get; } = beforeDispatch; }

sealed class Vault
{
    public string Root { get; }
    public Vault(string root) { Root = Path.GetFullPath(root); Directory.CreateDirectory(Root); }
    public T? Read<T>(string name)
    {
        var path = Path.Combine(Root, name + ".dpapi");
        if (!File.Exists(path)) return default;
        return JsonSerializer.Deserialize<T>(ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser), J.Options);
    }
    public void Write(string name, object value)
    {
        var path = Path.Combine(Root, name + ".dpapi");
        var bytes = ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(value, J.Options), null, DataProtectionScope.CurrentUser);
        using (var file = new FileStream(path + ".tmp", FileMode.Create, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough)) { file.Write(bytes); file.Flush(true); }
        File.Move(path + ".tmp", path, true);
    }
}

sealed class Identity
{
    public string HostId { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = Environment.MachineName;
    public string Certificate { get; set; } = "";
    public static Identity Load(Vault vault)
    {
        var identity = vault.Read<Identity>("identity");
        if (identity is not null) return identity;
        identity = new();
        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest("CN=CodexRemote-" + identity.HostId, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
        using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(3));
        identity.Certificate = Convert.ToBase64String(cert.Export(X509ContentType.Pfx));
        vault.Write("identity", identity);
        return identity;
    }
    // Schannel needs a temporary user key container; the durable PFX stays DPAPI-encrypted.
    public X509Certificate2 GetCertificate() => X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(Certificate), null, X509KeyStorageFlags.UserKeySet);
    public string Fingerprint { get { using var cert = GetCertificate(); return Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant(); } }
}

sealed record Device(string Id, string Name, string Hash, DateTimeOffset Created);
sealed class Pairing(Vault vault, Identity identity)
{
    readonly object sync = new();
    List<Device> devices = vault.Read<List<Device>>("devices") ?? [];
    string secret = "";
    DateTimeOffset expires;
    bool inUse;
    public Func<string, Task<bool>> Confirm { get; set; } = _ => Task.FromResult(false);
    public event Action? Changed;
    public Device[] Devices { get { lock (sync) return devices.ToArray(); } }
    public string CreateQr(string endpoint)
    {
        lock (sync) { secret = J.Secret(); expires = DateTimeOffset.UtcNow.AddMinutes(5); inUse = false; return JsonSerializer.Serialize(new { v = 1, hostId = identity.HostId, name = identity.Name, url = endpoint, fingerprint = identity.Fingerprint, secret, expiresAt = expires }, J.Options); }
    }
    public async Task<object> Pair(JsonNode body)
    {
        var supplied = body.S("secret"); var deviceName = body.S("deviceName");
        if (deviceName.Length is < 1 or > 80) throw new ApiError(400, "设备名称不正确");
        string claimed;
        lock (sync)
        {
            if (inUse || secret.Length == 0 || DateTimeOffset.UtcNow > expires || !J.Equal(secret, supplied)) throw new ApiError(403, "配对码已过期或已使用，请在电脑上刷新");
            claimed = secret; inUse = true;
        }
        if (!await Confirm(deviceName)) { lock (sync) { if (secret == claimed) secret = ""; } throw new ApiError(403, "电脑未确认配对，请重新生成二维码"); }
        lock (sync)
        {
            if (secret != claimed || DateTimeOffset.UtcNow > expires) throw new ApiError(403, "配对码已失效");
            secret = "";
            var token = J.Secret(); var device = new Device(Guid.NewGuid().ToString(), deviceName, J.Hash(token), DateTimeOffset.UtcNow);
            devices.Add(device); vault.Write("devices", devices); Changed?.Invoke();
            return new { hostId = identity.HostId, name = identity.Name, token, deviceId = device.Id };
        }
    }
    public Device? Authenticate(string token) { lock (sync) return token.Length == 64 ? devices.FirstOrDefault(d => J.Equal(d.Hash, J.Hash(token))) : null; }
    public void Revoke(string id) { lock (sync) { devices.RemoveAll(d => d.Id == id); vault.Write("devices", devices); } Changed?.Invoke(); }
}

sealed record OpRecord(string Fingerprint, string Status, JsonNode? Result);
sealed class Operations(Vault vault)
{
    readonly SemaphoreSlim mutex = new(1);
    Dictionary<string, OpRecord> records = vault.Read<Dictionary<string, OpRecord>>("operations") ?? [];
    public JsonNode? Lookup(string id) { lock (records) return records.TryGetValue(id, out var r) ? J.Node(r) : null; }
    public async Task<JsonNode> Run(JsonNode body, Func<Task<JsonNode>> action)
    {
        var id = body.S("operationId");
        if (!Guid.TryParse(id, out _)) throw new ApiError(400, "operationId 必须是 UUID");
        var fingerprint = J.Hash(body.ToJsonString(J.Options));
        await mutex.WaitAsync();
        try
        {
            OpRecord? previous; lock (records) records.TryGetValue(id, out previous);
            if (previous is not null)
            {
                if (previous.Fingerprint != fingerprint) throw new ApiError(409, "操作编号已用于其他内容");
                return J.Node(previous);
            }
            // Flush intent before contacting the desktop; an interrupted dispatch is never retried.
            lock (records) { records[id] = new(fingerprint, "uncertain", null); vault.Write("operations", records); }
            JsonNode result; string status;
            try { result = await action(); status = "accepted"; }
            catch (ApiError e) { result = J.Node(new { error = e.Message, statusCode = e.Status }); status = "rejected"; }
            catch { result = J.Node(new { error = "结果未确认；请先查看会话，不能自动重发。" }); status = "uncertain"; }
            var record = new OpRecord(fingerprint, status, result);
            lock (records) { records[id] = record; vault.Write("operations", records); }
            return J.Node(record);
        }
        finally { mutex.Release(); }
    }
}

sealed class EventHub
{
    readonly object sync = new();
    readonly Queue<JsonNode> replay = new();
    readonly HashSet<Channel<JsonNode>> subscribers = [];
    long sequence;
    public string InstanceId { get; private set; } = Guid.NewGuid().ToString();
    public void Reset() { lock (sync) { InstanceId = Guid.NewGuid().ToString(); replay.Clear(); sequence = 0; } Publish("reset", new { reason = "后端实例已更新，请重新读取状态" }); }
    public void Publish(string type, object data)
    {
        lock (sync)
        {
            var item = J.Node(new { sequence = ++sequence, instanceId = InstanceId, type, data });
            replay.Enqueue(item); while (replay.Count > 256) replay.Dequeue();
            foreach (var channel in subscribers.ToArray()) if (!channel.Writer.TryWrite(item)) { channel.Writer.TryComplete(); subscribers.Remove(channel); }
        }
    }
    public (Channel<JsonNode> Channel, JsonNode[] Replay) Subscribe(string instance, long after)
    {
        lock (sync)
        {
            var channel = Channel.CreateBounded<JsonNode>(new BoundedChannelOptions(64) { FullMode = BoundedChannelFullMode.Wait, SingleReader = true });
            subscribers.Add(channel);
            var valid = instance == InstanceId && after <= sequence && (replay.Count == 0 || after >= replay.Peek()["sequence"]!.GetValue<long>() - 1);
            return (channel, valid ? replay.Where(x => x["sequence"]!.GetValue<long>() > after).ToArray() : [J.Node(new { sequence, instanceId = InstanceId, type = "reset", data = new { reason = "请重新读取真实状态" } })]);
        }
    }
    public void Remove(Channel<JsonNode> channel) { lock (sync) subscribers.Remove(channel); channel.Writer.TryComplete(); }
}
