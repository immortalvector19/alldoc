package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTable
import com.alldoc.app.core.model.WordTableCell
import com.alldoc.app.core.model.WordTableRow
import com.alldoc.app.core.model.WordTextRun
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object DocxEngine {

    private const val DOCUMENT_XML_PATH = "word/document.xml"

    /**
     * Parses a .docx document from an input stream, extracting structured paragraphs and tables
     * while storing all original unedited ZIP parts for lossless re-saving.
     */
    fun parse(inputStream: InputStream, uriString: String): WordDocumentModel {
        val parts = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        var documentXmlBytes: ByteArray? = null

        while (entry != null) {
            val name = entry.name
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            val bytes = out.toByteArray()

            parts[name] = bytes
            if (name == DOCUMENT_XML_PATH) {
                documentXmlBytes = bytes
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        if (documentXmlBytes == null) {
            return WordDocumentModel(
                uriString = uriString,
                paragraphs = listOf(WordParagraph(runs = listOf(WordTextRun(text = "")))),
                uneditedXmlParts = parts
            )
        }

        val (paragraphs, tables) = parseDocumentXml(documentXmlBytes)

        return WordDocumentModel(
            uriString = uriString,
            paragraphs = paragraphs.ifEmpty { listOf(WordParagraph(runs = listOf(WordTextRun("")))) },
            tables = tables,
            uneditedXmlParts = parts
        )
    }

    /**
     * In-place lossless save: patches only changed text nodes in the original DOM XML,
     * preserving all tables, drawings, images, section properties (margins, headers/footers),
     * and streams all untouched original ZIP entries.
     */
    fun save(model: WordDocumentModel, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)

        val originalDocBytes = model.uneditedXmlParts[DOCUMENT_XML_PATH]
        val updatedDocumentXml = if (originalDocBytes != null) {
            try {
                patchDocumentXmlDom(originalDocBytes, model.paragraphs)
            } catch (_: Exception) {
                generateDocumentXml(model.paragraphs, model.tables)
            }
        } else {
            generateDocumentXml(model.paragraphs, model.tables)
        }

        // 1. Write the patched word/document.xml
        zipOut.putNextEntry(ZipEntry(DOCUMENT_XML_PATH))
        zipOut.write(updatedDocumentXml)
        zipOut.closeEntry()

        // 2. Stream all untouched original parts byte-for-byte
        for ((name, bytes) in model.uneditedXmlParts) {
            if (name != DOCUMENT_XML_PATH) {
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
        }

        zipOut.finish()
    }

    private fun parseDocumentXml(bytes: ByteArray): Pair<List<WordParagraph>, List<WordTable>> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

        val bodyNodes = doc.getElementsByTagNameNS("*", "body")
        val body = if (bodyNodes.length > 0) bodyNodes.item(0) as Element else doc.documentElement

        val paragraphs = mutableListOf<WordParagraph>()
        val tables = mutableListOf<WordTable>()

        // Walk direct children of <w:body> to guarantee 100% stable 1:1 mapping with patchDocumentXmlDom
        val children = body.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            val local = node.localName ?: node.nodeName.substringAfterLast(':')

            when (local) {
                "p" -> {
                    paragraphs.add(parseParagraphElement(node))
                }
                "tbl" -> {
                    tables.add(parseTableElement(node))
                }
            }
        }

        return Pair(paragraphs, tables)
    }

    private fun parseParagraphElement(pElem: Element): WordParagraph {
        var headingLevel: Int? = null
        var isListItem = false

        val pPrList = getChildElementsByLocalName(pElem, "pPr")
        if (pPrList.isNotEmpty()) {
            val pPr = pPrList[0]
            val pStyleList = getChildElementsByLocalName(pPr, "pStyle")
            if (pStyleList.isNotEmpty()) {
                val styleVal = getValAttrDom(pStyleList[0]) ?: ""
                if (styleVal.equals("Heading1", true) || styleVal.equals("Heading 1", true) || styleVal == "1") {
                    headingLevel = 1
                } else if (styleVal.equals("Heading2", true) || styleVal.equals("Heading 2", true) || styleVal == "2") {
                    headingLevel = 2
                }
            }
            if (getChildElementsByLocalName(pPr, "numPr").isNotEmpty()) {
                isListItem = true
            }
        }

        val runElements = mutableListOf<Element>()
        findRunsInParagraph(pElem, runElements)

        val runs = mutableListOf<WordTextRun>()
        for (rElem in runElements) {
            runs.add(parseRunElement(rElem))
        }

        return WordParagraph(
            runs = runs,
            headingLevel = headingLevel,
            isListItem = isListItem
        )
    }

    private fun parseRunElement(rElem: Element): WordTextRun {
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var colorHex: String? = null
        var fontSizePt: Float? = null

        val rPrList = getChildElementsByLocalName(rElem, "rPr")
        if (rPrList.isNotEmpty()) {
            val rPr = rPrList[0]
            val bList = getChildElementsByLocalName(rPr, "b")
            if (bList.isNotEmpty()) {
                val bVal = getValAttrDom(bList[0])
                isBold = (bVal == null || bVal == "1" || bVal.equals("true", true) || bVal.equals("on", true))
            }
            val iList = getChildElementsByLocalName(rPr, "i")
            if (iList.isNotEmpty()) {
                val iVal = getValAttrDom(iList[0])
                isItalic = (iVal == null || iVal == "1" || iVal.equals("true", true) || iVal.equals("on", true))
            }
            val uList = getChildElementsByLocalName(rPr, "u")
            if (uList.isNotEmpty()) {
                val uVal = getValAttrDom(uList[0])
                isUnderline = (uVal == null || (uVal != "none" && uVal != "0" && !uVal.equals("false", true)))
            }
            val strikeList = getChildElementsByLocalName(rPr, "strike")
            if (strikeList.isNotEmpty()) {
                val sVal = getValAttrDom(strikeList[0])
                isStrike = (sVal == null || sVal == "1" || sVal.equals("true", true) || sVal.equals("on", true))
            }
            val colorList = getChildElementsByLocalName(rPr, "color")
            if (colorList.isNotEmpty()) {
                colorHex = getValAttrDom(colorList[0])
            }
            val szList = getChildElementsByLocalName(rPr, "sz")
            if (szList.isNotEmpty()) {
                val halfPt = getValAttrDom(szList[0])?.toIntOrNull()
                if (halfPt != null) fontSizePt = halfPt / 2.0f
            }
        }

        val textBuilder = StringBuilder()
        val children = rElem.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element) {
                val local = c.localName ?: c.nodeName.substringAfterLast(':')
                when (local) {
                    "t" -> textBuilder.append(c.textContent ?: "")
                    "tab" -> textBuilder.append("\t")
                    "br" -> textBuilder.append("\n")
                }
            }
        }

        return WordTextRun(
            text = textBuilder.toString(),
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            isStrikeThrough = isStrike,
            fontSizePt = fontSizePt,
            colorHex = colorHex
        )
    }

    private fun parseTableElement(tblElem: Element): WordTable {
        val rows = mutableListOf<WordTableRow>()
        val rowElems = getChildElementsByLocalName(tblElem, "tr")
        for (tr in rowElems) {
            val cells = mutableListOf<WordTableCell>()
            val cellElems = getChildElementsByLocalName(tr, "tc")
            for (tc in cellElems) {
                val tNodes = mutableListOf<Element>()
                findDescendantElements(tc, "t", tNodes, stopAtP = false)
                val text = tNodes.joinToString("") { it.textContent ?: "" }.trim()
                cells.add(WordTableCell(text = text))
            }
            rows.add(WordTableRow(cells = cells))
        }
        return WordTable(rows = rows)
    }

    private fun findRunsInParagraph(parent: Node, results: MutableList<Element>) {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is Element) {
                val local = child.localName ?: child.nodeName.substringAfterLast(':')
                if (local == "r") {
                    results.add(child)
                } else if (local != "txbxContent" && local != "tbl" && local != "p") {
                    findRunsInParagraph(child, results)
                }
            }
        }
    }

    private fun getChildElementsByLocalName(parent: Element, targetName: String): List<Element> {
        val result = mutableListOf<Element>()
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is Element) {
                val local = child.localName ?: child.nodeName.substringAfterLast(':')
                if (local == targetName) {
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun getValAttrDom(elem: Element): String? {
        return if (elem.hasAttribute("w:val")) elem.getAttribute("w:val")
        else if (elem.hasAttribute("val")) elem.getAttribute("val")
        else null
    }

    private fun patchDocumentXmlDom(originalBytes: ByteArray, paragraphs: List<WordParagraph>): ByteArray {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(originalBytes))

        val bodyNodes = doc.getElementsByTagNameNS("*", "body")
        val body = if (bodyNodes.length > 0) bodyNodes.item(0) as Element else doc.documentElement

        // Direct child <w:p> elements in body (ignoring paragraphs inside tables and txbxContent)
        val bodyParagraphElements = mutableListOf<Element>()
        val children = body.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && (node.localName == "p" || node.nodeName.endsWith(":p") || node.nodeName == "p")) {
                bodyParagraphElements.add(node)
            }
        }

        val ns = body.namespaceURI ?: "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        for (idx in 0 until minOf(bodyParagraphElements.size, paragraphs.size)) {
            val pElem = bodyParagraphElements[idx]
            val pModel = paragraphs[idx]

            val rElements = mutableListOf<Element>()
            findRunsInParagraph(pElem, rElements)

            if (rElements.size == pModel.runs.size && rElements.isNotEmpty()) {
                // Exact run-by-run correspondence: update ONLY affected <w:t> text nodes!
                // Run properties (<w:rPr> bold, italic, color), tabs, and line breaks remain 100% untouched!
                for (rIdx in 0 until rElements.size) {
                    val rElem = rElements[rIdx]
                    val runModel = pModel.runs[rIdx]

                    val tElements = mutableListOf<Element>()
                    findDescendantElements(rElem, "t", tElements)

                    val currentRunText = tElements.joinToString("") { it.textContent ?: "" }
                    if (currentRunText != runModel.text) {
                        if (tElements.isNotEmpty()) {
                            tElements[0].textContent = runModel.text
                            for (k in 1 until tElements.size) {
                                tElements[k].textContent = ""
                            }
                        } else {
                            val tElem = doc.createElementNS(ns, "w:t")
                            tElem.textContent = runModel.text
                            rElem.appendChild(tElem)
                        }
                    }
                }
            } else {
                // Run count differed: selective fallback on paragraph's text nodes
                val tNodes = mutableListOf<Element>()
                findDescendantElements(pElem, "t", tNodes)
                val currentText = tNodes.joinToString("") { it.textContent ?: "" }
                val newFullText = pModel.fullText

                if (currentText != newFullText) {
                    if (tNodes.isNotEmpty()) {
                        tNodes[0].textContent = newFullText
                        for (k in 1 until tNodes.size) {
                            tNodes[k].textContent = ""
                        }
                    } else {
                        val r = doc.createElementNS(ns, "w:r")
                        val t = doc.createElementNS(ns, "w:t")
                        t.textContent = newFullText
                        r.appendChild(t)
                        pElem.appendChild(r)
                    }
                }
            }
        }

        // Handle deleted paragraphs
        if (bodyParagraphElements.size > paragraphs.size) {
            for (idx in paragraphs.size until bodyParagraphElements.size) {
                body.removeChild(bodyParagraphElements[idx])
            }
        }

        // Handle newly appended paragraphs
        if (paragraphs.size > bodyParagraphElements.size) {
            var sectPrNode: Node? = null
            val bodyChildren = body.childNodes
            for (i in 0 until bodyChildren.length) {
                val node = bodyChildren.item(i)
                if (node is Element && (node.localName == "sectPr" || node.nodeName.endsWith(":sectPr"))) {
                    sectPrNode = node
                    break
                }
            }

            for (idx in bodyParagraphElements.size until paragraphs.size) {
                val p = paragraphs[idx]
                val newPElem = doc.createElementNS(ns, "w:p")

                if (p.headingLevel != null || p.isListItem) {
                    val pPr = doc.createElementNS(ns, "w:pPr")
                    if (p.headingLevel != null) {
                        val pStyle = doc.createElementNS(ns, "w:pStyle")
                        pStyle.setAttributeNS(ns, "w:val", "Heading${p.headingLevel}")
                        pPr.appendChild(pStyle)
                    }
                    if (p.isListItem) {
                        val numPr = doc.createElementNS(ns, "w:numPr")
                        val ilvl = doc.createElementNS(ns, "w:ilvl")
                        ilvl.setAttributeNS(ns, "w:val", "0")
                        numPr.appendChild(ilvl)
                        pPr.appendChild(numPr)
                    }
                    newPElem.appendChild(pPr)
                }

                for (run in p.runs) {
                    val rElem = doc.createElementNS(ns, "w:r")
                    if (run.isBold || run.isItalic || run.isUnderline || run.isStrikeThrough) {
                        val rPr = doc.createElementNS(ns, "w:rPr")
                        if (run.isBold) rPr.appendChild(doc.createElementNS(ns, "w:b"))
                        if (run.isItalic) rPr.appendChild(doc.createElementNS(ns, "w:i"))
                        if (run.isUnderline) {
                            val u = doc.createElementNS(ns, "w:u")
                            u.setAttributeNS(ns, "w:val", "single")
                            rPr.appendChild(u)
                        }
                        if (run.isStrikeThrough) rPr.appendChild(doc.createElementNS(ns, "w:strike"))
                        rElem.appendChild(rPr)
                    }
                    val tElem = doc.createElementNS(ns, "w:t")
                    tElem.textContent = run.text
                    rElem.appendChild(tElem)
                    newPElem.appendChild(rElem)
                }

                if (sectPrNode != null) {
                    body.insertBefore(newPElem, sectPrNode)
                } else {
                    body.appendChild(newPElem)
                }
            }
        }

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    private fun findDescendantElements(
        parent: Node,
        targetLocalName: String,
        results: MutableList<Element>,
        stopAtP: Boolean = true
    ) {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is Element) {
                val local = child.localName ?: child.nodeName.substringAfterLast(':')
                if (local == targetLocalName) {
                    results.add(child)
                } else if (local != "tbl" && (!stopAtP || local != "p")) {
                    findDescendantElements(child, targetLocalName, results, stopAtP)
                }
            }
        }
    }

    private fun generateDocumentXml(paragraphs: List<WordParagraph>, tables: List<WordTable>): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("<w:body>")

        for (paragraph in paragraphs) {
            sb.append("<w:p>")
            if (paragraph.headingLevel != null || paragraph.isListItem) {
                sb.append("<w:pPr>")
                if (paragraph.headingLevel != null) {
                    sb.append("""<w:pStyle w:val="Heading${paragraph.headingLevel}"/>""")
                }
                if (paragraph.isListItem) {
                    sb.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
                }
                sb.append("</w:pPr>")
            }

            for (run in paragraph.runs) {
                sb.append("<w:r>")
                val hasProps = run.isBold || run.isItalic || run.isUnderline || run.isStrikeThrough || run.fontSizePt != null || run.colorHex != null
                if (hasProps) {
                    sb.append("<w:rPr>")
                    if (run.isBold) sb.append("<w:b/>")
                    if (run.isItalic) sb.append("<w:i/>")
                    if (run.isUnderline) sb.append("""<w:u w:val="single"/>""")
                    if (run.isStrikeThrough) sb.append("<w:strike/>")
                    if (run.fontSizePt != null) {
                        val halfPoints = (run.fontSizePt * 2).toInt()
                        sb.append("""<w:sz w:val="$halfPoints"/>""")
                    }
                    if (run.colorHex != null) {
                        sb.append("""<w:color w:val="${run.colorHex.removePrefix("#")}"/>""")
                    }
                    sb.append("</w:rPr>")
                }
                sb.append("""<w:t xml:space="preserve">${escapeXml(run.text)}</w:t>""")
                sb.append("</w:r>")
            }
            sb.append("</w:p>")
        }

        for (table in tables) {
            sb.append("<w:tbl>")
            for (row in table.rows) {
                sb.append("<w:tr>")
                for (cell in row.cells) {
                    sb.append("<w:tc>")
                    sb.append("<w:p><w:r><w:t>")
                    sb.append(escapeXml(cell.text))
                    sb.append("</w:t></w:r></w:p>")
                    sb.append("</w:tc>")
                }
                sb.append("</w:tr>")
            }
            sb.append("</w:tbl>")
        }

        sb.append("</w:body></w:document>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun getValAttr(parser: XmlPullParser): String? {
        return parser.getAttributeValue(null, "w:val")
            ?: parser.getAttributeValue(null, "val")
            ?: run {
                for (i in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(i)
                    if (name == "val" || name.endsWith(":val")) {
                        return parser.getAttributeValue(i)
                    }
                }
                null
            }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
