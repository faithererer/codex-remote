package cn.codexremote.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Host(val id: String, val name: String, val url: String, val fingerprint: String, val token: String, val deviceId: String, val followed: Boolean = true) {
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("fingerprint", fingerprint).put("token", token).put("deviceId", deviceId).put("followed", followed)
    companion object { fun from(j: JSONObject) = Host(j.getString("id"), j.getString("name"), j.getString("url"), j.getString("fingerprint"), j.optString("token"), j.optString("deviceId"), j.optBoolean("followed", true)) }
}
data class PairCode(val host: Host, val secret: String) {
    companion object {
        fun parse(text: String, now: Long = System.currentTimeMillis()): PairCode {
            val j = JSONObject(text.trim())
            require(j.getInt("v") == 1) { "不支持的配对码版本" }
            val id = j.getString("hostId"); UUID.fromString(id)
            val url = bridgeUrl(j.getString("url"))
            val pin = j.getString("fingerprint").lowercase(); val secret = j.getString("secret")
            require(pin.matches(Regex("[0-9a-f]{64}")) && secret.matches(Regex("[0-9a-f]{64}"))) { "配对码不完整" }
            val expiry = java.time.Instant.parse(j.getString("expiresAt")).toEpochMilli()
            require(expiry > now && expiry <= now + 6 * 60 * 1000) { "二维码已过期，请在电脑上刷新" }
            return PairCode(Host(id, j.optString("name", "电脑"), url, pin, "", ""), secret)
        }
    }
}

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("private-hosts", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("codex-hosts-v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("codex-hosts-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    @Synchronized fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "无法安全保存数据" }
    }
    @Synchronized fun get(name: String, fallback: String): String {
        val encrypted = prefs.getString(name, null) ?: return fallback
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun hosts(): List<Host> = JSONArray(get("hosts", "[]")).objects().map(Host::from)
    fun save(hosts: List<Host>) = put("hosts", JSONArray(hosts.map(Host::json)).toString())
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun connectionMessage(error: Throwable, url: String? = null): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    if (causes.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException }) return "电脑证书校验失败，连接已拒绝。请在电脑核对证书后重新配对。"
    if (error is java.io.IOException) return when (url?.let { runCatching { connectionKind(it) }.getOrNull() }) {
        "局域网" -> "局域网无法连接电脑。请确认手机与电脑在同一网络、地址未改变、桥接正在运行；检查防火墙、VPN 绕过局域网及 Wi-Fi 客户端隔离。"
        "Tailscale" -> "无法连接电脑的 Tailscale 地址。请确认两端 Tailscale 已连接；同一局域网可在“我的电脑 → 修改连接地址”切换直连。"
        else -> "无法连接电脑。局域网请确认同一网络、电脑地址和防火墙；外出使用 Tailscale 时确认两端已连接。"
    }
    return error.message ?: "连接失败，请检查当前网络和电脑桥接。"
}

class HostApi(val host: Host) {
    companion object {
        private val closingExecutor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task, "codex-connection-close").apply { isDaemon = true } }
    }
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    val client: OkHttpClient
    init {
        require(bridgeUrl(host.url) == host.url) { "保存的电脑地址无效，请在电脑管理中修改连接地址" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates not supported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("证书缺失")
                chain[0].checkValidity()
                val expected = host.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                if (!MessageDigest.isEqual(expected, actual)) throw java.security.cert.CertificateException("电脑证书指纹不匹配，请不要继续连接")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            .proxy(java.net.Proxy.NO_PROXY)
            // The QR pins the complete leaf certificate; hostname DNS is not a trust source.
            .hostnameVerifier { hostname, _ -> hostname == URI(host.url).host }
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }
    fun request(path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject {
        require(path.startsWith("/api/v1/"))
        val builder = Request.Builder().url(host.url + path)
        if (authenticated) builder.header("Authorization", "Bearer ${host.token}")
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val value = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) throw IllegalStateException(parsed.optString("error", "连接失败 (${response.code})"))
            return parsed
        }
    }
    fun events(instance: String, after: Long, listener: WebSocketListener): WebSocket {
        val url = (host.url + "/api/v1/events?instance=$instance&after=$after").replaceFirst("https://", "wss://")
        return client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${host.token}").build(), listener)
    }
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        // TLS shutdown can write to the socket, including when onStop cancels foreground connections.
        closingExecutor.execute { try { client.dispatcher.cancelAll(); client.connectionPool.evictAll() } finally { client.dispatcher.executorService.shutdown() } }
    }
}
