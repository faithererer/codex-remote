package cn.codexremote.app

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import java.net.URI

object MarkdownParser {
    private val extensions: List<Extension> = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    fun parse(text: String): Node = parser.parse(text)
}

fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()
fun safeWebLink(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null
}.getOrDefault(false)
