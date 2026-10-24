package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTable
import com.alldoc.app.core.model.WordTableCell
import com.alldoc.app.core.model.WordTableRow
import com.alldoc.app.core.model.WordTextRun
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocxEngine {

    private const val DOCUMENT_XML_PATH = "word/document.xml"

    /**
     * Parses a .docx document from an input stream, extracting structured paragraphs and tables
     * while storing unedited ZIP parts for lossless re-saving.
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

            if (name == DOCUMENT_XML_PATH) {
                documentXmlBytes = bytes
            } else {
                parts[name] = bytes
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
     * In-place mutation: rewrites word/document.xml with the updated paragraphs and tables,
     * while streaming all other original ZIP entries byte-for-byte.
     */
    fun save(model: WordDocumentModel, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)

        // 1. Write the newly generated word/document.xml
        val newDocumentXml = generateDocumentXml(model.paragraphs, model.tables)
        zipOut.putNextEntry(ZipEntry(DOCUMENT_XML_PATH))
        zipOut.write(newDocumentXml)
        zipOut.closeEntry()

        // 2. Stream all untouched original parts
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
        val paragraphs = mutableListOf<WordParagraph>()
        val tables = mutableListOf<WordTable>()

        val parser: XmlPullParser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        var currentParagraphRuns: MutableList<WordTextRun>? = null
        var currentHeadingLevel: Int? = null
        var isCurrentListItem = false

        var isBold = false
        var isItalic = false
        var isUnderline = false
        var isStrike = false
        var colorHex: String? = null
        var fontSizePt: Float? = null

        var inTable = false
        var currentTableRows: MutableList<WordTableRow>? = null
        var currentRowCells: MutableList<WordTableCell>? = null
        var currentCellText: StringBuilder? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "tbl" -> {
                            inTable = true
                            currentTableRows = mutableListOf()
                        }
                        "tr" -> {
                            if (inTable) currentRowCells = mutableListOf()
                        }
                        "tc" -> {
                            if (inTable) currentCellText = StringBuilder()
                        }
                        "p" -> {
                            if (!inTable) {
                                currentParagraphRuns = mutableListOf()
                                currentHeadingLevel = null
                                isCurrentListItem = false
                            }
                        }
                        "pStyle" -> {
                            val styleVal = parser.getAttributeValue(null, "val") ?: ""
                            if (styleVal.contains("Heading1", ignoreCase = true) || styleVal.contains("1")) {
                                currentHeadingLevel = 1
                            } else if (styleVal.contains("Heading2", ignoreCase = true) || styleVal.contains("2")) {
                                currentHeadingLevel = 2
                            }
                        }
                        "numPr" -> {
                            isCurrentListItem = true
                        }
                        "r" -> {
                            isBold = false
                            isItalic = false
                            isUnderline = false
                            isStrike = false
                            colorHex = null
                            fontSizePt = null
                        }
                        "b" -> isBold = true
                        "i" -> isItalic = true
                        "u" -> isUnderline = true
                        "strike" -> isStrike = true
                        "color" -> {
                            colorHex = parser.getAttributeValue(null, "val")
                        }
                        "sz" -> {
                            val halfPoints = parser.getAttributeValue(null, "val")?.toIntOrNull()
                            if (halfPoints != null) {
                                fontSizePt = halfPoints / 2.0f
                            }
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (inTable && currentCellText != null) {
                                currentCellText.append(text)
                            } else if (currentParagraphRuns != null) {
                                currentParagraphRuns.add(
                                    WordTextRun(
                                        text = text,
                                        isBold = isBold,
                                        isItalic = isItalic,
                                        isUnderline = isUnderline,
                                        isStrikeThrough = isStrike,
                                        fontSizePt = fontSizePt,
                                        colorHex = colorHex
                                    )
                                )
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (name) {
                        "p" -> {
                            if (!inTable && currentParagraphRuns != null) {
                                paragraphs.add(
                                    WordParagraph(
                                        runs = currentParagraphRuns,
                                        headingLevel = currentHeadingLevel,
                                        isListItem = isCurrentListItem
                                    )
                                )
                                currentParagraphRuns = null
                            }
                        }
                        "tc" -> {
                            if (inTable && currentRowCells != null && currentCellText != null) {
                                currentRowCells.add(WordTableCell(text = currentCellText.toString().trim()))
                                currentCellText = null
                            }
                        }
                        "tr" -> {
                            if (inTable && currentTableRows != null && currentRowCells != null) {
                                currentTableRows.add(WordTableRow(cells = currentRowCells))
                                currentRowCells = null
                            }
                        }
                        "tbl" -> {
                            if (inTable && currentTableRows != null) {
                                tables.add(WordTable(rows = currentTableRows))
                                inTable = false
                                currentTableRows = null
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return Pair(paragraphs, tables)
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

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
