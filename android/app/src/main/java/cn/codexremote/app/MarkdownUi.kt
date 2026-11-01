package cn.codexremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*

private val MdInk = Color(0xFF1D2A23)
private val MdGreen = Color(0xFF205B44)

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { MarkdownParser.parse(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document.children().forEach { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, depth: Int = 0) {
    // Bound nested layout depth so unusual model output cannot exhaust the UI stack.
    if (depth > 12) { MarkdownInline(node); return }
    when (node) {
        is Heading -> MarkdownInline(node, TextStyle(fontSize = when (node.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 16.sp }, fontWeight = FontWeight.Bold, lineHeight = 27.sp), Modifier.padding(top = 5.dp))
        is org.commonmark.node.Paragraph -> MarkdownInline(node)
        is FencedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'), node.info.substringBefore(' '))
        is IndentedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'), "代码")
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            node.children().forEachIndexed { index, item -> Row {
                Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", fontSize = 14.sp, color = MdGreen, modifier = Modifier.widthIn(min = 23.dp).padding(end = 5.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { item.children().forEach { MarkdownBlock(it, depth + 1) } }
            } }
        }
        is BlockQuote -> Row(Modifier.fillMaxWidth().background(Color(0xFFF0F3E8), RoundedCornerShape(4.dp)).padding(9.dp)) {
            Box(Modifier.width(3.dp).height(22.dp).background(Color(0xFFA4BA8C)))
            Column(Modifier.padding(start = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { node.children().forEach { MarkdownBlock(it, depth + 1) } }
        }
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color(0xFFDAE2D5))
        is TableBlock -> MarkdownTable(node)
        is HtmlBlock -> SelectionContainer { Text(node.literal, fontSize = 13.sp, color = MdInk) }
        else -> if (node.firstChild != null) node.children().forEach { MarkdownBlock(it, depth + 1) } else MarkdownInline(node)
    }
}

@Composable
private fun MarkdownInline(node: Node, style: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp), modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(node) { buildAnnotatedString { inline(node) { url -> runCatching { uriHandler.openUri(url) }; Unit } } }
    SelectionContainer { Text(annotated, modifier = modifier, style = style, color = MdInk) }
}

private fun AnnotatedString.Builder.inline(node: Node, open: (String) -> Unit) {
    fun nested() { node.children().forEach { inline(it, open) } }
    when (node) {
        is org.commonmark.node.Text -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFEAF0DF), color = MdGreen)) { append(node.literal) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { nested() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { nested() }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { nested() }
        is SoftLineBreak -> append('\n')
        is HardLineBreak -> append('\n')
        is Link -> if (safeWebLink(node.destination)) withLink(LinkAnnotation.Clickable(node.destination, TextLinkStyles(style = SpanStyle(color = MdGreen, textDecoration = TextDecoration.Underline))) { open(node.destination) }) { nested() } else nested()
        is Image -> { append("[图片："); nested(); append("]") }
        is HtmlInline -> append(node.literal)
        else -> nested()
    }
}

@Composable
private fun MarkdownCode(code: String, language: String) {
    var expanded by remember(code) { mutableStateOf(false) }
    val lines = code.lineSequence().count()
    val clipboard = LocalClipboardManager.current
    Surface(color = Color(0xFF18382F), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(language.ifBlank { "代码" }, color = Color(0xFFBFD6BA), fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(14.dp), tint = Color(0xFFD2F56D)) }
            }
            SelectionContainer { Text(if (expanded || lines <= 12) code else code.lineSequence().take(12).joinToString("\n"), color = Color(0xFFEBF1E5), fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp)) }
            if (lines > 12) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(if (expanded) "收起代码" else "展开全部 $lines 行", color = Color(0xFFD2F56D), fontSize = 11.sp) }
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = table.children().flatMap { section -> section.children() }.filterIsInstance<TableRow>()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            rows.forEachIndexed { index, row -> Row(Modifier.background(if (index == 0) Color(0xFFE8EEDB) else if (index % 2 == 0) Color(0xFFF4F6F0) else Color.White)) {
                row.children().forEach { cell -> Box(Modifier.width(170.dp).padding(9.dp)) { MarkdownInline(cell, TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = if ((cell as? TableCell)?.isHeader == true) FontWeight.Bold else FontWeight.Normal)) } }
            }; HorizontalDivider(color = Color(0xFFDAE2D5)) }
        }
    }
}
