using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace CodexRemote;

sealed record ChatImage(string Source, string Name)
{
    public string Id => J.Hash(Source);
    public JsonNode Describe() => J.Node(new { id = Id, name = Name });
}

static class Images
{
    public const int MaxBytes = 10 * 1024 * 1024;
    public const int MaxCount = 5;
    public static string Validate(byte[] bytes)
    {
        if (bytes.Length is < 12 or > MaxBytes) throw new ApiError(413, "图片需小于 10 MB");
        string mime = bytes.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }) ? "image/png"
            : bytes[0] == 255 && bytes[1] == 216 && bytes[2] == 255 ? "image/jpeg"
            : System.Text.Encoding.ASCII.GetString(bytes, 0, 6) is "GIF87a" or "GIF89a" ? "image/gif"
            : System.Text.Encoding.ASCII.GetString(bytes, 0, 4) == "RIFF" && System.Text.Encoding.ASCII.GetString(bytes, 8, 4) == "WEBP" ? "image/webp" : "";
        if (mime.Length == 0) throw new ApiError(415, "支持 PNG、JPEG、GIF、WebP 图片；手机选图会自动转换格式");
        // Read dimensions before a full decode; do not allocate a bitmap for unbounded input.
        long width = 0, height = 0;
        if (mime == "image/png" && bytes.Length >= 24) { width = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(16)); height = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(20)); }
        else if (mime == "image/gif") { width = BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(6)); height = BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(8)); }
        else if (mime == "image/jpeg")
        {
            for (int i = 2; i + 8 < bytes.Length;)
            {
                if (bytes[i++] != 255) break;
                while (i < bytes.Length && bytes[i] == 255) i++;
                if (i >= bytes.Length) break;
                var marker = bytes[i++];
                if (marker is 0xd9 or 0xda) break;
                if (marker is 0x01 or >= 0xd0 and <= 0xd7) continue;
                if (i + 2 > bytes.Length) break;
                int length = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(i));
                if (length < 2 || i + length > bytes.Length) break;
                if (marker is >= 0xc0 and <= 0xcf && marker is not (0xc4 or 0xc8 or 0xcc) && length >= 8) { height = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(i + 3)); width = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(i + 5)); break; }
                i += length;
            }
        }
        else if (mime == "image/webp")
        {
            if (BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(4)) != bytes.Length - 8) throw new ApiError(415, "WebP 图片不完整");
            for (int i = 12; i + 8 < bytes.Length;)
            {
                uint size = BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(i + 4));
                if (size > bytes.Length - i - 8) break;
                var kind = System.Text.Encoding.ASCII.GetString(bytes, i, 4); int start = i + 8;
                if (kind == "VP8X" && size >= 10) { width = 1 + bytes[start + 4] + (bytes[start + 5] << 8) + (bytes[start + 6] << 16); height = 1 + bytes[start + 7] + (bytes[start + 8] << 8) + (bytes[start + 9] << 16); break; }
                if (kind == "VP8 " && size >= 10 && bytes.AsSpan(start + 3, 3).SequenceEqual(new byte[] { 157, 1, 42 })) { width = BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(start + 6)) & 0x3fff; height = BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(start + 8)) & 0x3fff; break; }
                if (kind == "VP8L" && size >= 5 && bytes[start] == 47) { uint bits = BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(start + 1)); width = 1 + (bits & 0x3fff); height = 1 + ((bits >> 14) & 0x3fff); break; }
                i += 8 + (int)size + (int)(size % 2);
            }
        }
        if (width < 1 || height < 1 || width > 16000 || height > 16000 || width * height > 32000000) throw new ApiError(413, "图片尺寸过大或格式损坏（上限 3200 万像素）");
        // GDI+ has no WebP codec. Its container/dimensions are checked here; Android decodes it.
        try { if (mime != "image/webp") { using var input = new MemoryStream(bytes); using var image = Image.FromStream(input, false, true); if (image.Width != width || image.Height != height) throw new Exception(); } }
        catch { throw new ApiError(415, "图片内容无法读取，请重新选择"); }
        return mime;
    }
    public static async Task<byte[]> ReadBounded(Stream stream, CancellationToken cancellation = default)
    {
        using var data = new MemoryStream(); var buffer = new byte[32768]; int read;
        while ((read = await stream.ReadAsync(buffer, cancellation)) > 0) { if (data.Length + read > MaxBytes) throw new ApiError(413, "单张图片上限 10 MB"); await data.WriteAsync(buffer.AsMemory(0, read), cancellation); }
        return data.ToArray();
    }
    static string? LocalPath(string source, string cwd)
    {
        if (source.StartsWith("data:image/", StringComparison.Ordinal)) return source.Length <= MaxBytes * 4 / 3 + 128 ? source : null;
        if (source.StartsWith("file:", StringComparison.OrdinalIgnoreCase)) { if (!Uri.TryCreate(source, UriKind.Absolute, out var uri) || uri.IsUnc || !uri.IsFile) return null; source = uri.LocalPath; }
        if (source.Contains('\0') || source.StartsWith("\\") || source.StartsWith("//") || Regex.IsMatch(source, @"^[a-zA-Z][\w+.-]*://")) return null;
        try
        {
            var path = Path.GetFullPath(source, cwd);
            if (!Regex.IsMatch(path, @"^[a-zA-Z]:[\\/]") || path[2..].Contains(':')) return null;
            return path;
        }
        catch { return null; }
    }
    public static IReadOnlyList<ChatImage> In(JsonNode? node, string cwd, string text = "")
    {
        var result = new List<ChatImage>(); var seen = new HashSet<string>();
        void Add(string source)
        {
            if (result.Count >= 20 || string.IsNullOrWhiteSpace(source)) return;
            var path = LocalPath(source, cwd); if (path == null || !seen.Add(path)) return;
            result.Add(new(path, path.StartsWith("data:") ? "会话图片" : Path.GetFileName(path)));
        }
        void Content(JsonArray content)
        {
            foreach (var c in content.OfType<JsonNode>())
                if (c.S("type") == "localImage") Add(c.S("path"));
                else if (c.S("type") == "image") { if (c.S("url").Length > 0) Add(c.S("url")); else if (c.S("data").Length > 0) Add("data:" + c.S("mimeType", "image/png") + ";base64," + c.S("data")); }
        }
        Content(node.Array("input")); Content(node.Array("content")); if (node?["result"] is JsonObject toolResult) Content(toolResult.Array("content"));
        foreach (var c in node?["restoreMessage"]?["context"].Array("imageAttachments") ?? []) Add(c.S("localPath", c.S("src")));
        if (node.S("type") == "imageView") Add(node.S("path", node.S("url")));
        if (node.S("type") == "imageGeneration") { Add(node.S("savedPath", node.S("src"))); if (result.Count == 0 && node.S("result").Length > 0) Add(node.S("result").StartsWith("data:") ? node.S("result") : "data:image/png;base64," + node.S("result")); }
        // Only image destinations explicitly present in this conversation are eligible; never fetch a URL.
        foreach (Match match in Regex.Matches(text, @"!\[[^\]\r\n]*\]\(\s*(?:<(?<p>[^>\r\n]+)>|(?<p>[^)\r\n]+))\s*\)", RegexOptions.None, TimeSpan.FromMilliseconds(100)))
        {
            var path = match.Groups["p"].Value.Trim();
            if (Regex.IsMatch(path, @"\.(png|jpe?g|gif|webp)$", RegexOptions.IgnoreCase)) Add(path);
        }
        return result;
    }
    public static JsonArray Describe(IEnumerable<ChatImage> images) => new(images.Select(i => i.Describe()).ToArray());
    public static JsonArray Input(string text, JsonArray images)
    {
        if (text.Length > 32000 || text.Length == 0 && images.Count == 0) throw new ApiError(400, "请输入文字或选择图片（文字上限 32000 字符）");
        var input = new JsonArray();
        if (text.Length > 0) input.Add(J.Node(new { type = "text", text, text_elements = new object[0] }));
        foreach (var image in images) input.Add(image!.Copy());
        return input;
    }
    public static IEnumerable<ChatImage> InState(JsonNode state) => StateView.Turns(state).SelectMany(t => In(t["params"], state.S("cwd", t["params"].S("cwd"))).Concat(t.Array("items").SelectMany(i => In(i, state.S("cwd", t["params"].S("cwd")), i.S("text")))));
    public static async Task<(byte[] Bytes, string Mime)> Read(ChatImage image)
    {
        byte[] bytes;
        if (image.Source.StartsWith("data:image/"))
        {
            var at = image.Source.IndexOf(";base64,", StringComparison.Ordinal);
            if (at < 0) throw new ApiError(415, "不支持此图片编码");
            try { bytes = Convert.FromBase64String(image.Source[(at + 8)..]); } catch { throw new ApiError(415, "图片编码无效"); }
        }
        else
        {
            if (new DriveInfo(Path.GetPathRoot(image.Source)!).DriveType == DriveType.Network) throw new ApiError(403, "不能读取网络共享中的图片");
            for (var entry = new FileInfo(image.Source) as FileSystemInfo; entry != null; entry = entry is FileInfo f ? f.Directory : (entry as DirectoryInfo)?.Parent)
                if ((entry.Attributes & FileAttributes.ReparsePoint) != 0) throw new ApiError(403, "不能读取重解析路径中的图片");
            using var input = new FileStream(image.Source, FileMode.Open, FileAccess.Read, FileShare.Read); bytes = await ReadBounded(input);
        }
        return (bytes, Validate(bytes));
    }
}

sealed record UploadedImage(string Id, string ThreadId, string InstanceId, string DeviceId, string Hash, string Path, DateTimeOffset Created, bool Claimed);
sealed class ImageUploads(Vault vault)
{
    readonly object sync = new();
    List<UploadedImage> records = vault.Read<List<UploadedImage>>("image-uploads") ?? [];
    public JsonNode Save(string id, string thread, string instance, string device, byte[] bytes)
    {
        if (!Guid.TryParseExact(id, "D", out _)) throw new ApiError(400, "图片标识无效");
        var mime = Images.Validate(bytes); var hash = Convert.ToHexString(SHA256.HashData(bytes));
        lock (sync)
        {
            var old = records.FirstOrDefault(r => r.Id == id);
            if (old != null) { if (old.ThreadId != thread || old.InstanceId != instance || old.DeviceId != device || old.Hash != hash) throw new ApiError(409, "图片标识已经用于其他内容"); return J.Node(new { id, mime }); }
            var root = Path.Combine(vault.Root, "images"); Directory.CreateDirectory(root);
            foreach (var expired in records.Where(r => !File.Exists(r.Path) || !r.Claimed && DateTimeOffset.UtcNow - r.Created > TimeSpan.FromDays(1)).ToArray()) { if (!expired.Claimed) File.Delete(expired.Path); records.Remove(expired); }
            if (records.Where(r => !r.Claimed && r.DeviceId == device).Sum(r => new FileInfo(r.Path).Length) + bytes.Length > 50L * 1024 * 1024 || records.Sum(r => new FileInfo(r.Path).Length) + bytes.Length > 1024L * 1024 * 1024) throw new ApiError(413, "电脑图片附件空间已满，请清理后再上传");
            var path = Path.Combine(root, id + (mime == "image/png" ? ".png" : mime == "image/jpeg" ? ".jpg" : mime == "image/webp" ? ".webp" : ".gif"));
            File.WriteAllBytes(path, bytes); records.Add(new(id, thread, instance, device, hash, path, DateTimeOffset.UtcNow, false)); vault.Write("image-uploads", records);
            return J.Node(new { id, mime });
        }
    }
    public JsonArray Claim(JsonNode body, string device)
    {
        if (body["imageIds"] != null && body["imageIds"] is not JsonArray) throw new ApiError(400, "图片列表格式无效");
        var ids = body.Array("imageIds").Select(i => i?.ToString() ?? "").ToArray();
        if (ids.Length > Images.MaxCount || ids.Distinct().Count() != ids.Length) throw new ApiError(400, "每条消息最多 5 张不同图片");
        lock (sync)
        {
            var selected = ids.Select(id => records.FirstOrDefault(r => r.Id == id && r.ThreadId == body.S("threadId") && r.InstanceId == body.S("instanceId") && r.DeviceId == device) ?? throw new ApiError(409, "图片不属于这台电脑、会话、实例或手机，请重新选择图片")).ToArray();
            foreach (var image in selected) if (!File.Exists(image.Path) || new FileInfo(image.Path).Length > Images.MaxBytes || Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(image.Path))) != image.Hash) throw new ApiError(409, "图片已丢失或改变，请重新选择");
            foreach (var image in selected) records[records.IndexOf(image)] = image with { Claimed = true };
            if (selected.Length > 0) vault.Write("image-uploads", records);
            return new JsonArray(selected.Select(i => J.Node(new { type = "localImage", path = i.Path })).ToArray());
        }
    }
}
