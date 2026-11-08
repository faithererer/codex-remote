package cn.codexremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ConnectionAddressDialog(host: Host, repo: Repository, close: () -> Unit) {
    var text by remember(host.id) { mutableStateOf(host.url) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val valid = runCatching { connectionAddress(text) }.isSuccess
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("修改连接地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(host.name)
            Text("同一 Wi-Fi／局域网填写电脑桥接窗口的局域网地址，无需开启 Tailscale。外出时可改回 Tailscale 地址。", fontSize = 13.sp)
            OutlinedTextField(text, { text = it; error = "" }, label = { Text("电脑 IPv4 地址或 HTTPS 地址") }, placeholder = { Text("192.168.1.8:48761") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("省略端口时使用 48761。先核对原证书和电脑身份，成功后才保存；无需重新配对，草稿与未确认操作保留。", fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = !busy && valid, onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                try { repo.changeAddress(host.id, text); repo.message.value = "已切换 ${host.name} 的连接地址"; close() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = connectionMessage(e, runCatching { connectionAddress(text) }.getOrNull()) }
                finally { busy = false }
            }
        }
    }) { Text(if (busy) "正在核对…" else "核对并保存") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}
