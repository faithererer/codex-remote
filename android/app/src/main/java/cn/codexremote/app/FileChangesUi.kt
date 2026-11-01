package cn.codexremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

private val ChangeGreen = Color(0xFF173D31)

@Composable
fun FileChangeCard(message: JSONObject) {
    val files = remember(message.toString()) { changedFiles(message) }
    var open by remember { mutableStateOf(false) }
    Surface(color = Color(0xFFEDF1E7), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Difference, null, Modifier.size(17.dp), tint = ChangeGreen)
                Text((if (message.optString("kind") == "diff") "本轮汇总差异" else "文件修改") + " · ${files.size} 个文件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChangeGreen, modifier = Modifier.weight(1f).padding(start = 7.dp))
                Text(if (message.optString("status") == "inProgress") "修改中" else if (message.optString("status") == "failed") "失败" else "查看 diff", fontSize = 11.sp, color = ChangeGreen)
            }
            files.take(3).forEach { file -> Row { Text(file.name, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); ChangeCounts(file) } }
            if (files.size > 3) Text("另 ${files.size - 3} 个文件", fontSize = 10.sp)
            if (files.isEmpty()) Text("桌面尚未提供文件差异", fontSize = 11.sp)
            if (message.optBoolean("changesTruncated")) Text("文件过多，仅列出前 100 项；其余请在电脑查看", fontSize = 10.sp)
        }
    }
    if (open) FileDiffDialog(files, "文件修改") { open = false }
}

@Composable
fun LatestFilesButton(view: JSONObject?) {
    val files = remember(view) { latestChangedFiles(view) }
    var open by remember { mutableStateOf(false) }
    if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Difference, null, Modifier.size(15.dp), tint = ChangeGreen)
        Text("本轮修改 · ${files.size} 个文件", modifier = Modifier.weight(1f).padding(start = 6.dp), fontSize = 11.sp, color = ChangeGreen)
        Text("查看 diff", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ChangeGreen)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ChangeGreen)
    }
    if (open) FileDiffDialog(files, "本轮文件修改") { open = false }
}

@Composable
private fun ChangeCounts(file: ChangedFile) {
    Text("${file.label}  ", fontSize = 10.sp, color = Color(0xFF657063))
    if (file.diff.isNotBlank()) { Text("+${file.additions} ", fontSize = 10.sp, color = Color(0xFF20663B)); Text("−${file.deletions}", fontSize = 10.sp, color = Color(0xFFAF3A2C)) }
}

@Composable
fun FileDiffDialog(files: List<ChangedFile>, title: String, close: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val file = files.getOrNull(selected)
    val lines = remember(file) { diffLines(file?.diff.orEmpty()) }
    val horizontal = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 20.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFFFAFCF6)) {
            Column {
                Row(Modifier.padding(start = 14.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$title · ${files.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { file?.let { clipboard.setText(AnnotatedString(it.diff)) } }, enabled = file != null) { Icon(Icons.Outlined.ContentCopy, "复制差异", Modifier.size(19.dp)) }
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭") }
                }
                if (files.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEachIndexed { index, f -> FilterChip(selected = index == selected, onClick = { selected = index }, label = { Text(f.name, maxLines = 1, fontSize = 11.sp) }) }
                }
                file?.let { current ->
                    SelectionContainer { Text(current.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)) }
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 3.dp)) { ChangeCounts(current) }
                    if (current.movePath.isNotEmpty()) Text("移动路径：${current.movePath}", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 13.dp))
                    Text("绿色 + 新增 · 红色 − 删除 · 左右滑动看长行", color = Color(0xFF657063), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp))
                    if (lines.any { it.text.length > 4000 }) Text("超长行只展示前 4000 字符，可用右上角复制完整差异。", color = Color(0xFF9A4F17), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 3.dp))
                    HorizontalDivider()
                    if (current.diff.isBlank()) Text("Codex 未提供该文件的文本差异（可能是二进制文件或尚在处理中）。不会从电脑额外读取文件。", fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    else key(current.path, current.diff.hashCode()) {
                        val lineWidth = maxOf(360, (lines.maxOfOrNull { it.text.length } ?: 0).coerceAtMost(4050) * 7 + 100).dp
                        Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal)) {
                            LazyColumn(Modifier.width(lineWidth).fillMaxHeight()) {
                                items(lines) { line -> SelectionContainer { DiffRow(line) } }
                                if (current.truncated) item { Text("差异过长，已截断。完整内容请在电脑查看。", color = Color(0xFF9A4F17), modifier = Modifier.padding(12.dp), fontSize = 12.sp) }
                            }
                        }
                    }
                } ?: Text("暂无文件差异", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val background = when (line.kind) { DiffKind.ADDED -> Color(0xFFE0F2DF); DiffKind.REMOVED -> Color(0xFFFBE3DF); DiffKind.HUNK -> Color(0xFFE4EBF3); else -> Color.Transparent }
    val color = when (line.kind) { DiffKind.ADDED -> Color(0xFF205B2D); DiffKind.REMOVED -> Color(0xFF9C2D24); DiffKind.HUNK -> Color(0xFF3D5D82); else -> Color(0xFF435245) }
    Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(line.old?.toString().orEmpty().padStart(4) + " " + line.new?.toString().orEmpty().padStart(4), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF819084), modifier = Modifier.padding(end = 9.dp))
        Text((if (line.text.length > 4000) line.text.take(4000) + " [超长行已省略，复制查看全文]" else line.text).ifEmpty { " " }, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = color, softWrap = false)
    }
}
