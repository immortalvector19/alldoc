package com.alldoc.app.core.engine.openxml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.SpreadsheetWorkbook
import com.alldoc.app.core.model.SpreadsheetWorksheet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object XlsxEngine {

    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
    private const val WORKBOOK_PATH = "xl/workbook.xml"
    private const val WORKSHEET_1_PATH = "xl/worksheets/sheet1.xml"

    fun parse(inputStream: InputStream, uriString: String): SpreadsheetDocumentModel {
        val parts = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        var sharedStringsBytes: ByteArray? = null
        var worksheetBytes: ByteArray? = null

        while (entry != null) {
            val name = entry.name
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            val bytes = out.toByteArray()

            if (name == SHARED_STRINGS_PATH) {
                sharedStringsBytes = bytes
            } else if (name == WORKSHEET_1_PATH) {
                worksheetBytes = bytes
            }
            parts[name] = bytes
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        val sharedStrings = if (sharedStringsBytes != null) parseSharedStrings(sharedStringsBytes) else emptyList()
        val worksheet = if (worksheetBytes != null) {
            parseWorksheet(worksheetBytes, sharedStrings)
        } else {
            SpreadsheetWorksheet(name = "Sheet1")
        }

        val evaluatedSheet = evaluateFormulas(worksheet)
        val workbook = SpreadsheetWorkbook(sheets = listOf(evaluatedSheet), activeSheetIndex = 0)

        return SpreadsheetDocumentModel(
            uriString = uriString,
            workbook = workbook
        )
    }

    fun save(model: SpreadsheetDocumentModel, originalInput: InputStream?, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)
        val activeSheet = model.workbook.activeSheet

        // Collect all strings from the active sheet to build sharedStrings.xml
        val stringList = mutableListOf<String>()
        val stringMap = mutableMapOf<String, Int>()

        for ((_, cell) in activeSheet.cells) {
            val text = cell.rawValue
            if (text.isNotEmpty() && text.toDoubleOrNull() == null && !text.startsWith("=")) {
                if (!stringMap.containsKey(text)) {
                    stringMap[text] = stringList.size
                    stringList.add(text)
                }
            }
        }

        val newSharedStringsXml = generateSharedStringsXml(stringList)
        val newSheetXml = generateWorksheetXml(activeSheet, stringMap)

        // If we have an original template, stream all non-modified files
        if (originalInput != null) {
            val zipIn = ZipInputStream(originalInput)
            var entry = zipIn.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name != SHARED_STRINGS_PATH && name != WORKSHEET_1_PATH) {
                    zipOut.putNextEntry(ZipEntry(name))
                    zipIn.copyTo(zipOut)
                    zipOut.closeEntry()
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        } else {
            // Write minimal workbook files if starting fresh
            writeMinimalPackageStructure(zipOut)
        }

        // Write shared strings and sheet1
        zipOut.putNextEntry(ZipEntry(SHARED_STRINGS_PATH))
        zipOut.write(newSharedStringsXml)
        zipOut.closeEntry()

        zipOut.putNextEntry(ZipEntry(WORKSHEET_1_PATH))
        zipOut.write(newSheetXml)
        zipOut.closeEntry()

        zipOut.finish()
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        var currentText: StringBuilder? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "si") currentText = StringBuilder()
                    else if (name == "t" && currentText != null) {
                        currentText.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "si" && currentText != null) {
                        list.add(currentText.toString())
                        currentText = null
                    }
                }
            }
            eventType = parser.next()
        }
        return list
    }

    private fun parseWorksheet(bytes: ByteArray, sharedStrings: List<String>): SpreadsheetWorksheet {
        val cells = mutableMapOf<CellCoordinate, CellData>()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        var currentCoord: CellCoordinate? = null
        var cellType: String? = null
        var cellValue: String? = null
        var cellFormula: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "c") {
                        val ref = parser.getAttributeValue(null, "r")
                        currentCoord = if (ref != null) CellCoordinate.parseAddress(ref) else null
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = null
                        cellFormula = null
                    } else if (name == "f") {
                        cellFormula = parser.nextText()
                    } else if (name == "v") {
                        cellValue = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "c" && currentCoord != null) {
                        val finalRawValue = when (cellType) {
                            "s" -> {
                                val index = cellValue?.toIntOrNull()
                                if (index != null && index in sharedStrings.indices) sharedStrings[index] else (cellValue ?: "")
                            }
                            "b" -> if (cellValue == "1") "TRUE" else "FALSE"
                            else -> cellValue ?: ""
                        }

                        cells[currentCoord] = CellData(
                            rawValue = finalRawValue,
                            formula = if (!cellFormula.isNullOrBlank()) "=$cellFormula" else null
                        )
                        currentCoord = null
                    }
                }
            }
            eventType = parser.next()
        }

        val maxRow = (cells.keys.maxOfOrNull { it.row } ?: 0) + 1
        val maxCol = (cells.keys.maxOfOrNull { it.col } ?: 0) + 1

        return SpreadsheetWorksheet(
            name = "Sheet1",
            cells = cells,
            rowCount = maxOf(maxRow, 30),
            colCount = maxOf(maxCol, 15)
        )
    }

    fun evaluateFormulas(sheet: SpreadsheetWorksheet): SpreadsheetWorksheet {
        val updatedCells = sheet.cells.toMutableMap()

        for ((coord, cell) in sheet.cells) {
            val formula = cell.formula
            if (formula != null && formula.startsWith("=")) {
                val computed = evaluateFormula(formula.removePrefix("="), updatedCells)
                updatedCells[coord] = cell.copy(computedValue = computed)
            }
        }

        return sheet.copy(cells = updatedCells)
    }

    private fun evaluateFormula(expr: String, cells: Map<CellCoordinate, CellData>): String {
        val trimmed = expr.trim().uppercase()

        // Match SUM(A1:A5), AVERAGE(A1:A5), COUNT(A1:A5), MAX(A1:A5), MIN(A1:A5)
        val funcMatch = Regex("""^(SUM|AVERAGE|COUNT|MAX|MIN)\(([A-Z0-9:]+)\)$""").find(trimmed)
        if (funcMatch != null) {
            val (func, range) = funcMatch.destructured
            val values = resolveRangeValues(range, cells)
            if (values.isEmpty()) return "0"

            return when (func) {
                "SUM" -> String.format("%.2f", values.sum()).removeSuffix(".00")
                "AVERAGE" -> String.format("%.2f", values.average()).removeSuffix(".00")
                "COUNT" -> values.size.toString()
                "MAX" -> String.format("%.2f", values.maxOrNull() ?: 0.0).removeSuffix(".00")
                "MIN" -> String.format("%.2f", values.minOrNull() ?: 0.0).removeSuffix(".00")
                else -> "#NAME?"
            }
        }

        // Direct arithmetic: A1+B1, A1-B1, A1*B1, A1/B1
        val mathMatch = Regex("""^([A-Z]+[0-9]+)\s*([\+\-\*\/])\s*([A-Z]+[0-9]+|[0-9.]+)$""").find(trimmed)
        if (mathMatch != null) {
            val (leftRef, op, rightRef) = mathMatch.destructured
            val leftCoord = CellCoordinate.parseAddress(leftRef) ?: return "#REF!"
            val leftVal = cells[leftCoord]?.displayValue?.toDoubleOrNull() ?: 0.0

            val rightVal = rightRef.toDoubleOrNull() ?: run {
                val rightCoord = CellCoordinate.parseAddress(rightRef) ?: return "#REF!"
                cells[rightCoord]?.displayValue?.toDoubleOrNull() ?: 0.0
            }

            val result = when (op) {
                "+" -> leftVal + rightVal
                "-" -> leftVal - rightVal
                "*" -> leftVal * rightVal
                "/" -> if (rightVal != 0.0) leftVal / rightVal else return "#DIV/0!"
                else -> 0.0
            }
            return String.format("%.2f", result).removeSuffix(".00")
        }

        return expr
    }

    private fun resolveRangeValues(range: String, cells: Map<CellCoordinate, CellData>): List<Double> {
        val values = mutableListOf<Double>()
        if (range.contains(":")) {
            val parts = range.split(":")
            val start = CellCoordinate.parseAddress(parts[0]) ?: return emptyList()
            val end = CellCoordinate.parseAddress(parts[1]) ?: return emptyList()

            val minRow = minOf(start.row, end.row)
            val maxRow = maxOf(start.row, end.row)
            val minCol = minOf(start.col, end.col)
            val maxCol = maxOf(start.col, end.col)

            for (r in minRow..maxRow) {
                for (c in minCol..maxCol) {
                    val cell = cells[CellCoordinate(r, c)]
                    val num = cell?.displayValue?.toDoubleOrNull()
                    if (num != null) values.add(num)
                }
            }
        } else {
            val coord = CellCoordinate.parseAddress(range)
            if (coord != null) {
                val num = cells[coord]?.displayValue?.toDoubleOrNull()
                if (num != null) values.add(num)
            }
        }
        return values
    }

    private fun generateSharedStringsXml(strings: List<String>): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
        for (str in strings) {
            sb.append("<si><t>${escapeXml(str)}</t></si>")
        }
        sb.append("</sst>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun generateWorksheetXml(sheet: SpreadsheetWorksheet, stringMap: Map<String, Int>): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<sheetData>")

        // Group cells by row
        val cellsByRow = sheet.cells.entries.groupBy { it.key.row }.toSortedMap()

        for ((rowIndex, rowCells) in cellsByRow) {
            sb.append("""<row r="${rowIndex + 1}">""")
            val sortedCells = rowCells.sortedBy { it.key.col }

            for ((coord, cell) in sortedCells) {
                val addr = coord.address
                val formula = cell.formula?.removePrefix("=")
                val raw = cell.rawValue

                val num = raw.toDoubleOrNull()
                if (num != null) {
                    sb.append("""<c r="$addr">""")
                    if (!formula.isNullOrBlank()) sb.append("<f>${escapeXml(formula)}</f>")
                    sb.append("<v>$raw</v>")
                    sb.append("</c>")
                } else if (stringMap.containsKey(raw)) {
                    val sstIdx = stringMap[raw]
                    sb.append("""<c r="$addr" t="s">""")
                    if (!formula.isNullOrBlank()) sb.append("<f>${escapeXml(formula)}</f>")
                    sb.append("<v>$sstIdx</v>")
                    sb.append("</c>")
                } else {
                    sb.append("""<c r="$addr" t="inlineStr">""")
                    if (!formula.isNullOrBlank()) sb.append("<f>${escapeXml(formula)}</f>")
                    sb.append("<is><t>${escapeXml(raw)}</t></is>")
                    sb.append("</c>")
                }
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeMinimalPackageStructure(zipOut: ZipOutputStream) {
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="xml" ContentType="application/xml"/>
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
            </Types>""".trimIndent()
        zipOut.putNextEntry(ZipEntry("[Content_Types].xml"))
        zipOut.write(contentTypes.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""".trimIndent()
        zipOut.putNextEntry(ZipEntry("_rels/.rels"))
        zipOut.write(rels.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
            </Relationships>""".trimIndent()
        zipOut.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        zipOut.write(wbRels.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>""".trimIndent()
        zipOut.putNextEntry(ZipEntry(WORKBOOK_PATH))
        zipOut.write(workbook.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
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
