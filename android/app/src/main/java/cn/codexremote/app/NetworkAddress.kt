package cn.codexremote.app

import org.json.JSONObject
import java.net.URI

fun bridgeUrl(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/") && uri.port in 1..65535) { "请使用带端口的 HTTPS 私网地址" }
    val parts = uri.host?.split('.').orEmpty()
    require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "只接受局域网或 Tailscale 的 IPv4 地址" }
    val ip = parts.map { it.toInt() }
    val lan = ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168
    val tailscale = ip[0] == 100 && ip[1] in 64..127
    require(lan || tailscale) { "只接受局域网私有地址（10.x／172.16–31.x／192.168.x）或 Tailscale 地址" }
    return "https://${parts.joinToString(".")}:${uri.port}"
}

fun connectionAddress(value: String): String {
    val uri = URI(value.trim().let { if (it.contains("://")) it else "https://$it" })
    require(uri.host != null) { "请输入电脑的 IPv4 地址" }
    return bridgeUrl(URI(uri.scheme, uri.userInfo, uri.host, if (uri.port == -1) 48761 else uri.port, uri.path, uri.query, uri.fragment).toString())
}

fun connectionKind(url: String): String = if (URI(bridgeUrl(url)).host.startsWith("100.")) "Tailscale" else "局域网"

// Called only after the pinned HTTPS check; changing an address never establishes new trust.
fun hostAtVerifiedAddress(host: Host, url: String, status: JSONObject): Host {
    require(status.optString("hostId") == host.id && status.optInt("protocolVersion") == 1 && status.optString("instanceId").isNotEmpty()) { "这个地址不是原来配对的电脑，未修改连接" }
    return host.copy(url = connectionAddress(url))
}
