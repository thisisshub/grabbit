package dev.usenetstream.engine.nzb

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class NzbSegment(val number: Int, val bytes: Long, val messageId: String)

data class NzbFile(val subject: String, val groups: List<String>, val segments: List<NzbSegment>) {
    /** Poster's file name: the quoted part of the subject, e.g. `[1/9] "movie.part01.rar" yEnc (1/120)`. */
    val name: String get() = QUOTED.find(subject)?.groupValues?.get(1) ?: subject

    val encodedBytes: Long get() = segments.sumOf { it.bytes }

    private companion object {
        val QUOTED = Regex("\"([^\"]+)\"")
    }
}

/** [password] is the poster's archive password from `<head><meta type="password">`, when there is one. */
data class Nzb(val files: List<NzbFile>, val password: String? = null)

object NzbParser {
    fun parse(input: InputStream): Nzb {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        // NZBs reference an external DTD; never fetch it (offline + no XXE).
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = builder.parse(input)

        val files = doc.documentElement.childElements("file").map { file ->
            val groups = file.childElements("groups").flatMap { it.childElements("group") }
                .map { it.textContent.trim() }
            val segments = file.childElements("segments").flatMap { it.childElements("segment") }
                .map {
                    NzbSegment(
                        number = it.getAttribute("number").toInt(),
                        bytes = it.getAttribute("bytes").toLongOrNull() ?: 0L,
                        messageId = it.textContent.trim(),
                    )
                }
                .distinctBy { it.number }
                .sortedBy { it.number }
            NzbFile(file.getAttribute("subject"), groups, segments)
        }.filter { it.segments.isNotEmpty() }

        val password = doc.documentElement.childElements("head").flatMap { it.childElements("meta") }
            .firstOrNull { it.getAttribute("type").equals("password", ignoreCase = true) }
            ?.textContent?.trim()?.ifEmpty { null }
        return Nzb(files, password)
    }

    private fun Element.childElements(localName: String): List<Element> {
        val out = ArrayList<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node.localName ?: node.nodeName) == localName) {
                out += node as Element
            }
        }
        return out
    }
}
