using System.Buffers.Binary;
using System.Drawing.Imaging;
using System.Text.Json.Nodes;

namespace CodexRemote;

static class ImageTests
{
    public static void Run(Action<string, Action> test, Action<bool> check, Vault vault, string root)
    {
        byte[] Png(Color color) { using var bitmap = new Bitmap(2, 2); bitmap.SetPixel(0, 0, color); using var data = new MemoryStream(); bitmap.Save(data, ImageFormat.Png); return data.ToArray(); }
        var png = Png(Color.Red); var source = "data:image/png;base64," + Convert.ToBase64String(png);
        bool Refused(Action action) { try { action(); return false; } catch (ApiError) { return true; } }
        test("Image validation rejects spoofed content and oversized dimensions before decode", () => {
            check(Images.Validate(png) == "image/png");
            check(Refused(() => Images.Validate(System.Text.Encoding.UTF8.GetBytes("<html>not an image</html>"))));
            var huge = png.ToArray(); BinaryPrimitives.WriteUInt32BigEndian(huge.AsSpan(16), 100000); check(Refused(() => Images.Validate(huge)));
            check(Refused(() => Images.Validate(new byte[Images.MaxBytes + 1])));
        });
        test("Bounded image stream rejects chunked oversized bodies", () => { check(Refused(() => Images.ReadBounded(new MemoryStream(new byte[Images.MaxBytes + 1])).GetAwaiter().GetResult())); });
        test("Image-only native input does not synthesize a user prompt", () => {
            var input = Images.Input("", J.Node(new[] { new { type = "localImage", path = "C:\\test.png" } }).AsArray());
            check(input.Count == 1 && input[0].S("type") == "localImage"); check(Refused(() => Images.Input("", [])));
        });
        test("Image descriptors exclude paths data and remote URLs", () => {
            var node = J.Node(new { input = new[] { new { type = "image", url = source }, new { type = "image", url = "https://untrusted.invalid/test.png" } } });
            var found = Images.In(node, root); check(found.Count == 1 && found[0].Source == source);
            var json = Images.Describe(found).ToJsonString(); check(!json.Contains("base64") && !json.Contains(source) && !json.Contains(root));
            check(Images.Read(found[0]).GetAwaiter().GetResult().Bytes.SequenceEqual(png));
        });
        test("Conversation images reject UNC remote file URLs and alternate data streams", () => {
            foreach (var path in new[] { @"\\server\share\picture.png", "file://server/share/picture.png", "https://untrusted.invalid/a.png", @"C:\picture.png:secret" }) check(Images.In(J.Node(new { input = new[] { new { type = "localImage", path } } }), root).Count == 0);
        });
        test("Markdown images are resolved in the real conversation workspace", () => {
            var images = Images.In(new JsonObject(), root, "![test](<images/screen shot.png>) ![remote](https://untrusted.invalid/a.png)");
            check(images.Count == 1 && images[0].Source == Path.Combine(root, "images", "screen shot.png"));
        });
        test("Native imageView and imageGeneration results expose scoped image descriptors", () => {
            check(Images.In(J.Node(new { type = "imageView", path = Path.Combine(root, "screen.png") }), root).Count == 1);
            check(Images.In(J.Node(new { type = "imageGeneration", result = Convert.ToBase64String(png) }), root).Single().Source == source);
        });
        test("Image-only history appears once and remains authorized by source thread", () => {
            var echo = new { id = "echo", type = "userMessage", clientId = "client", content = new[] { new { type = "image", url = source } } };
            var turn = new { turnId = "turn", status = "completed", @params = new { input = new[] { new { type = "image", url = source } }, clientUserMessageId = "client" }, items = new[] { echo } };
            var state = J.Node(new { id = "thread-a", cwd = root, turns = new[] { turn } });
            var view = StateView.Normalize(state, "instance"); check(view.Array("messages").Count == 1 && view.Array("messages")[0].Array("images").Count == 1);
            check(Images.InState(state).Any(i => i.Id == J.Hash(source))); check(!Images.InState(J.Node(new { turns = new object[0] })).Any());
        });
        test("Tool image content is viewable without embedding base64 in the transcript", () => {
            var tool = new { id = "tool", type = "mcpToolCall", server = "test", tool = "image", result = new { content = new[] { new { type = "image", mimeType = "image/png", data = Convert.ToBase64String(png) } } } };
            var state = J.Node(new { id = "thread", cwd = root, turns = new[] { new { turnId = "turn", status = "completed", @params = new { input = new object[0] }, items = new[] { tool } } } });
            var message = StateView.Normalize(state, "instance").Array("messages")[0]; check(message.Array("images").Count == 1 && !message.S("text").Contains(Convert.ToBase64String(png)));
        });
        var upload = Guid.NewGuid().ToString(); var thread = Guid.NewGuid().ToString(); var instance = Guid.NewGuid().ToString(); var device = Guid.NewGuid().ToString();
        JsonNode Body() => J.Node(new { threadId = thread, instanceId = instance, imageIds = new[] { upload } });
        test("Uploaded image staging is idempotent and survives bridge restart", () => {
            var a = new ImageUploads(vault).Save(upload, thread, instance, device, png); var b = new ImageUploads(vault).Save(upload, thread, instance, device, png);
            check(JsonNode.DeepEquals(a, b)); var input = new ImageUploads(vault).Claim(Body(), device); check(input.Count == 1 && File.Exists(input[0].S("path")));
        });
        test("Image upload IDs cannot overwrite a different payload", () => { check(Refused(() => new ImageUploads(vault).Save(upload, thread, instance, device, Png(Color.Blue)))); });
        test("Attachments cannot cross devices threads instances or independent hosts", () => {
            var images = new ImageUploads(vault);
            check(Refused(() => images.Claim(Body(), "other-device")));
            var foreign = Body(); foreign["threadId"] = Guid.NewGuid().ToString(); check(Refused(() => images.Claim(foreign, device)));
            var stale = Body(); stale["instanceId"] = Guid.NewGuid().ToString(); check(Refused(() => images.Claim(stale, device)));
            check(Refused(() => new ImageUploads(new Vault(Path.Combine(root, "other-image-host"))).Claim(Body(), device)));
        });
        test("Duplicate or excessive attachment IDs fail before dispatch", () => {
            var duplicate = Body(); duplicate["imageIds"] = J.Node(new[] { upload, upload }); check(Refused(() => new ImageUploads(vault).Claim(duplicate, device)));
            duplicate["imageIds"] = J.Node(Enumerable.Range(0, 6).Select(_ => Guid.NewGuid().ToString())); check(Refused(() => new ImageUploads(vault).Claim(duplicate, device)));
        });
        test("Tampered staged images are rejected before native dispatch", () => {
            var images = new ImageUploads(vault); var input = images.Claim(Body(), device); File.WriteAllBytes(input[0].S("path"), Png(Color.Blue)); check(Refused(() => images.Claim(Body(), device)));
        });
    }
}
