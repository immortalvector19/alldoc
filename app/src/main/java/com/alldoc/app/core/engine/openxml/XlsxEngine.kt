package com.alldoc.app.core.engine.openxml

import org.xmlpull.v1.XmlPullParser
import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.SpreadsheetWorkbook
import com.alldoc.app.core.model.SpreadsheetWorksheet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object XlsxEngine {

    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
    private const val WORKBOOK_PATH = "xl/workbook.xml"
    private const val WORKBOOK_RELS_PATH = "xl/_rels/workbook.xml.rels"
    private const val STYLES_PATH = "xl/styles.xml"

    data class SheetReference(
        val name: String,
        val sheetId: String,
        val rId: String
    )

    fun parse(inputStream: InputStream, uriString: String): SpreadsheetDocumentModel {
        val parts = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        while (entry != null) {
            val name = entry.name.replace('\\', '/')
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            parts[name] = out.toByteArray()
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        val sharedStringsBytes = parts[SHARED_STRINGS_PATH]
        val sharedStrings = if (sharedStringsBytes != null) parseSharedStrings(sharedStringsBytes) else emptyList()

        val stylesBytes = parts[STYLES_PATH]
        val dateStyleIndices = if (stylesBytes != null) parseDateStyles(stylesBytes) else emptySet()

        // 1. Parse workbook.xml to discover all sheets and their order
        val workbookBytes = parts[WORKBOOK_PATH]
        val sheetRefs = if (workbookBytes != null) parseWorkbookSheets(workbookBytes) else emptyList()

        // 2. Parse workbook.xml.rels to resolve rId to target worksheet paths
        val relsBytes = parts[WORKBOOK_RELS_PATH]
        val rIdToPath = if (relsBytes != null) parseWorkbookRels(relsBytes) else emptyMap()

        val worksheets = mutableListOf<SpreadsheetWorksheet>()
        val sheetPartPaths = mutableMapOf<Int, String>()

        if (sheetRefs.isNotEmpty()) {
            for ((idx, ref) in sheetRefs.withIndex()) {
                var targetPath = rIdToPath[ref.rId]
                if (targetPath != null) {
                    if (!targetPath.startsWith("xl/")) {
                        targetPath = "xl/" + targetPath.removePrefix("/")
                    }
                } else {
                    targetPath = "xl/worksheets/sheet${ref.sheetId}.xml"
                }

                sheetPartPaths[idx] = targetPath

                val sheetBytes = parts[targetPath] ?: parts.entries.firstOrNull {
                    it.key.equals(targetPath, ignoreCase = true) ||
                    it.key.endsWith("sheet${ref.sheetId}.xml", ignoreCase = true)
                }?.value

                if (sheetBytes != null) {
                    val sheet = parseWorksheet(sheetBytes, ref.name, sharedStrings, dateStyleIndices)
                    worksheets.add(evaluateFormulas(sheet))
                }
            }
        }

        // Fallback: If no sheets found through workbook.xml, scan all xl/worksheets/*.xml entries
        if (worksheets.isEmpty()) {
            val worksheetEntries = parts.filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
                .toSortedMap()

            if (worksheetEntries.isNotEmpty()) {
                var idx = 0
                for ((path, bytes) in worksheetEntries) {
                    val sheetName = "Sheet${idx + 1}"
                    val sheet = parseWorksheet(bytes, sheetName, sharedStrings, dateStyleIndices)
                    worksheets.add(evaluateFormulas(sheet))
                    sheetPartPaths[idx] = path
                    idx++
                }
            } else {
                worksheets.add(SpreadsheetWorksheet(name = "Sheet1"))
                sheetPartPaths[0] = "xl/worksheets/sheet1.xml"
            }
        }

        val workbook = SpreadsheetWorkbook(
            sheets = worksheets,
            activeSheetIndex = 0
        )

        return SpreadsheetDocumentModel(
            uriString = uriString,
            workbook = workbook,
            uneditedZipParts = parts,
            sheetPartPaths = sheetPartPaths
        )
    }

    private fun parseWorkbookSheets(bytes: ByteArray): List<SheetReference> {
        val list = mutableListOf<SheetReference>()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            if (eventType == XmlPullParser.START_TAG && name == "sheet") {
                val sheetName = parser.getAttributeValue(null, "name") ?: "Sheet"
                val sheetId = parser.getAttributeValue(null, "sheetId") ?: "${list.size + 1}"
                var rId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                if (rId == null) {
                    // Try without namespace
                    rId = parser.getAttributeValue(null, "r:id") ?: parser.getAttributeValue(null, "id") ?: "rId${list.size + 1}"
                }
                list.add(SheetReference(name = sheetName, sheetId = sheetId, rId = rId))
            }
            eventType = parser.next()
        }
        return list
    }

    private fun parseWorkbookRels(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            if (eventType == XmlPullParser.START_TAG && name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) {
                    map[id] = target
                }
            }
            eventType = parser.next()
        }
        return map
    }

    private fun parseDateStyles(bytes: ByteArray): Set<Int> {
        val dateStyleIndices = mutableSetOf<Int>()
        val customDateNumFmts = mutableSetOf<Int>()

        // Standard built-in Excel date/time format IDs
        val standardDateNumFmtIds = setOf(
            14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58
        )

        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        var inCellXfs = false
        var xfIndex = 0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "numFmt") {
                        val numFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        val formatCode = parser.getAttributeValue(null, "formatCode")?.lowercase() ?: ""
                        if (numFmtId != null && isDateFormatCode(formatCode)) {
                            customDateNumFmts.add(numFmtId)
                        }
                    } else if (name == "cellXfs") {
                        inCellXfs = true
                        xfIndex = 0
                    } else if (inCellXfs && name == "xf") {
                        val numFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        if (numFmtId != null) {
                            if (numFmtId in standardDateNumFmtIds || numFmtId in customDateNumFmts) {
                                dateStyleIndices.add(xfIndex)
                            }
                        }
                        xfIndex++
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "cellXfs") {
                        inCellXfs = false
                    }
                }
            }
            eventType = parser.next()
        }
        return dateStyleIndices
    }

    private fun isDateFormatCode(code: String): Boolean {
        // Exclude formats that only represent numbers with characters
        if (code.isBlank()) return false
        val containsDateTokens = code.contains("yy") || code.contains("yyyy") ||
                code.contains("dd") || code.contains("mm") || code.contains("hh") || code.contains("ss")
        return containsDateTokens && !code.contains("red") && !code.contains("general")
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

    private fun parseWorksheet(
        bytes: ByteArray,
        sheetName: String,
        sharedStrings: List<String>,
        dateStyleIndices: Set<Int>
    ): SpreadsheetWorksheet {
        val cells = mutableMapOf<CellCoordinate, CellData>()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        var currentCoord: CellCoordinate? = null
        var cellType: String? = null
        var cellStyleIndex: Int? = null
        var cellValue: String? = null
        var cellFormula: String? = null
        var inlineString: StringBuilder? = null
        var inInlineStr = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "c") {
                        val ref = parser.getAttributeValue(null, "r")
                        currentCoord = if (ref != null) CellCoordinate.parseAddress(ref) else null
                        cellType = parser.getAttributeValue(null, "t")
                        cellStyleIndex = parser.getAttributeValue(null, "s")?.toIntOrNull()
                        cellValue = null
                        cellFormula = null
                        inlineString = null
                        inInlineStr = false
                    } else if (name == "f") {
                        cellFormula = parser.nextText()
                    } else if (name == "v") {
                        cellValue = parser.nextText()
                    } else if (name == "is") {
                        inInlineStr = true
                        inlineString = StringBuilder()
                    } else if (inInlineStr && name == "t") {
                        inlineString?.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "is") {
                        inInlineStr = false
                    } else if (name == "c" && currentCoord != null) {
                        val finalRawValue = when (cellType) {
                            "s" -> {
                                val index = cellValue?.toIntOrNull()
                                if (index != null && index in sharedStrings.indices) sharedStrings[index] else (cellValue ?: "")
                            }
                            "inlineStr" -> {
                                inlineString?.toString() ?: (cellValue ?: "")
                            }
                            "b" -> if (cellValue == "1") "TRUE" else "FALSE"
                            else -> {
                                val raw = cellValue ?: ""
                                val num = raw.toDoubleOrNull()
                                if (num != null && cellStyleIndex != null && cellStyleIndex in dateStyleIndices) {
                                    formatExcelDate(num)
                                } else {
                                    raw
                                }
                            }
                        }

                        cells[currentCoord] = CellData(
                            rawValue = finalRawValue,
                            formula = if (!cellFormula.isNullOrBlank()) "=$cellFormula" else null,
                            originalRawValue = finalRawValue,
                            originalFormula = if (!cellFormula.isNullOrBlank()) "=$cellFormula" else null
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
            name = sheetName,
            cells = cells,
            rowCount = maxOf(maxRow, 30),
            colCount = maxOf(maxCol, 15)
        )
    }

    /**
     * Formats an Excel serial date number (days since 1899-12-30) into a clean ISO-8601 string.
     */
    fun formatExcelDate(serial: Double): String {
        return try {
            val wholeDays = serial.toLong()
            val fraction = serial - wholeDays
            // Lotus 1-2-3 / Excel 1900 leap year bug: day 60 is Feb 29, 1900
            val adjustedDays = if (wholeDays > 59) wholeDays - 1 else wholeDays
            // 1899-12-30 UTC in milliseconds: -2209075200000L
            val baseMillis = -2209075200000L
            val totalMillis = baseMillis + (adjustedDays * 86400000L) + (fraction * 86400000L).toLong()

            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = totalMillis
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val hasTime = fraction > 0.0001

            if (hasTime) {
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val min = calendar.get(Calendar.MINUTE)
                String.format(Locale.US, "%04d-%02d-%02d %02d:%02d", year, month, day, hour, min)
            } else {
                String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            }
        } catch (_: Exception) {
            serial.toString()
        }
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
                "SUM" -> String.format(Locale.US, "%.2f", values.sum()).removeSuffix(".00")
                "AVERAGE" -> String.format(Locale.US, "%.2f", values.average()).removeSuffix(".00")
                "COUNT" -> values.size.toString()
                "MAX" -> String.format(Locale.US, "%.2f", values.maxOrNull() ?: 0.0).removeSuffix(".00")
                "MIN" -> String.format(Locale.US, "%.2f", values.minOrNull() ?: 0.0).removeSuffix(".00")
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
            return String.format(Locale.US, "%.2f", result).removeSuffix(".00")
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

    fun save(model: SpreadsheetDocumentModel, originalInput: InputStream? = null, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)

        val partsMap: Map<String, ByteArray> = if (model.uneditedZipParts.isNotEmpty()) {
            model.uneditedZipParts
        } else if (originalInput != null) {
            val loaded = mutableMapOf<String, ByteArray>()
            val zipIn = ZipInputStream(originalInput)
            var entry = zipIn.nextEntry
            while (entry != null) {
                val out = ByteArrayOutputStream()
                zipIn.copyTo(out)
                loaded[entry.name] = out.toByteArray()
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            loaded
        } else {
            emptyMap()
        }

        if (partsMap.isNotEmpty()) {
            val mutableParts = partsMap.toMutableMap()

            val anyCellEdited = model.workbook.sheets.any { sheet ->
                sheet.cells.values.any { it.isEdited }
            }

            if (anyCellEdited) {
                // 1. Update xl/workbook.xml to add fullCalcOnLoad="1"
                mutableParts["xl/workbook.xml"]?.let { wbBytes ->
                    mutableParts["xl/workbook.xml"] = patchWorkbookCalcPr(wbBytes)
                }

                // 2. Remove xl/calcChain.xml
                mutableParts.remove("xl/calcChain.xml")

                // 3. Remove calcChain relationship from xl/_rels/workbook.xml.rels
                mutableParts["xl/_rels/workbook.xml.rels"]?.let { relsBytes ->
                    mutableParts["xl/_rels/workbook.xml.rels"] = removeRelationshipTarget(relsBytes, "calcChain.xml")
                }

                // 4. Remove calcChain override from [Content_Types].xml
                mutableParts["[Content_Types].xml"]?.let { ctBytes ->
                    mutableParts["[Content_Types].xml"] = removeContentTypeOverride(ctBytes, "/xl/calcChain.xml")
                }
            }

            // Map sheet zip paths to sheet indices
            val pathToSheetIdx = mutableMapOf<String, Int>()
            for (idx in model.workbook.sheets.indices) {
                val path = model.sheetPartPaths[idx] ?: "xl/worksheets/sheet${idx + 1}.xml"
                pathToSheetIdx[path] = idx
            }

            for ((name, bytes) in mutableParts) {
                val sheetIdx = pathToSheetIdx[name]
                if (sheetIdx != null && sheetIdx in model.workbook.sheets.indices) {
                    val updatedSheet = model.workbook.sheets[sheetIdx]
                    val patchedXml = patchOrGenerateWorksheetXml(bytes, updatedSheet)
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(patchedXml)
                    zipOut.closeEntry()
                } else {
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
        } else {
            writeMinimalPackageStructure(zipOut, model.workbook)
        }

        zipOut.finish()
    }

    private fun patchOrGenerateWorksheetXml(originalBytes: ByteArray?, sheet: SpreadsheetWorksheet): ByteArray {
        if (originalBytes == null) {
            return generateWorksheetXml(sheet)
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(originalBytes))

            val sheetDataList = doc.getElementsByTagNameNS("*", "sheetData")
            val sheetData = if (sheetDataList.length > 0) {
                sheetDataList.item(0) as org.w3c.dom.Element
            } else {
                val elem = doc.createElementNS(doc.documentElement.namespaceURI, "sheetData")
                doc.documentElement.appendChild(elem)
                elem
            }

            val ns = doc.documentElement.namespaceURI ?: "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

            // Index existing rows and cells without clearing
            val existingRows = mutableMapOf<Int, org.w3c.dom.Element>()
            val existingCells = mutableMapOf<String, org.w3c.dom.Element>()

            val rowNodes = sheetData.childNodes
            for (i in 0 until rowNodes.length) {
                val rNode = rowNodes.item(i)
                if (rNode is org.w3c.dom.Element && (rNode.localName == "row" || rNode.nodeName.endsWith(":row") || rNode.nodeName == "row")) {
                    val rIndex = rNode.getAttribute("r").toIntOrNull()
                    if (rIndex != null) {
                        existingRows[rIndex] = rNode
                        val cellNodes = rNode.childNodes
                        for (j in 0 until cellNodes.length) {
                            val cNode = cellNodes.item(j)
                            if (cNode is org.w3c.dom.Element && (cNode.localName == "c" || cNode.nodeName.endsWith(":c") || cNode.nodeName == "c")) {
                                val addr = cNode.getAttribute("r")
                                if (addr.isNotEmpty()) {
                                    existingCells[addr] = cNode
                                }
                            }
                        }
                    }
                }
            }

            // Patch ONLY the cells that were edited by the user
            for ((coord, cell) in sheet.cells) {
                if (!cell.isEdited) continue

                val addr = coord.address
                val formula = cell.formula?.removePrefix("=")
                val raw = cell.rawValue
                val existingCellElem = existingCells[addr]

                if (existingCellElem != null) {
                    // Update existing cell in-place, keeping all original attributes (especially s="styleId")!
                    val num = raw.toDoubleOrNull()
                    if (num != null) {
                        if (existingCellElem.getAttribute("t") == "inlineStr" || existingCellElem.getAttribute("t") == "s") {
                            existingCellElem.removeAttribute("t")
                        }
                        removeChildrenByLocalName(existingCellElem, "is")

                        if (!formula.isNullOrBlank()) {
                            setOrCreateChildText(doc, ns, existingCellElem, "f", formula)
                        } else {
                            removeChildrenByLocalName(existingCellElem, "f")
                        }

                        setOrCreateChildText(doc, ns, existingCellElem, "v", raw)
                    } else {
                        existingCellElem.setAttribute("t", "inlineStr")
                        removeChildrenByLocalName(existingCellElem, "v")

                        if (!formula.isNullOrBlank()) {
                            setOrCreateChildText(doc, ns, existingCellElem, "f", formula)
                        } else {
                            removeChildrenByLocalName(existingCellElem, "f")
                        }

                        removeChildrenByLocalName(existingCellElem, "is")
                        val isElem = doc.createElementNS(ns, "is")
                        val tElem = doc.createElementNS(ns, "t")
                        tElem.textContent = raw
                        isElem.appendChild(tElem)
                        existingCellElem.appendChild(isElem)
                    }
                } else {
                    // Newly added cell
                    val rowIndex1Based = coord.row + 1
                    val rowElem = existingRows[rowIndex1Based] ?: run {
                        val newRow = doc.createElementNS(ns, "row")
                        newRow.setAttribute("r", rowIndex1Based.toString())
                        insertRowSorted(sheetData, newRow, rowIndex1Based)
                        existingRows[rowIndex1Based] = newRow
                        newRow
                    }

                    val newCellElem = doc.createElementNS(ns, "c")
                    newCellElem.setAttribute("r", addr)

                    val num = raw.toDoubleOrNull()
                    if (num != null) {
                        if (!formula.isNullOrBlank()) {
                            val fElem = doc.createElementNS(ns, "f")
                            fElem.textContent = formula
                            newCellElem.appendChild(fElem)
                        }
                        val vElem = doc.createElementNS(ns, "v")
                        vElem.textContent = raw
                        newCellElem.appendChild(vElem)
                    } else {
                        newCellElem.setAttribute("t", "inlineStr")
                        if (!formula.isNullOrBlank()) {
                            val fElem = doc.createElementNS(ns, "f")
                            fElem.textContent = formula
                            newCellElem.appendChild(fElem)
                        }
                        val isElem = doc.createElementNS(ns, "is")
                        val tElem = doc.createElementNS(ns, "t")
                        tElem.textContent = raw
                        isElem.appendChild(tElem)
                        newCellElem.appendChild(isElem)
                    }

                    insertCellSorted(rowElem, newCellElem, coord.col)
                    existingCells[addr] = newCellElem
                }
            }

            val transformer = TransformerFactory.newInstance().newTransformer()
            val out = ByteArrayOutputStream()
            transformer.transform(DOMSource(doc), StreamResult(out))
            out.toByteArray()
        } catch (_: Exception) {
            generateWorksheetXml(sheet)
        }
    }

    private fun removeChildrenByLocalName(parent: org.w3c.dom.Element, localName: String) {
        val list = parent.childNodes
        val toRemove = mutableListOf<org.w3c.dom.Node>()
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is org.w3c.dom.Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == localName) {
                    toRemove.add(child)
                }
            }
        }
        for (node in toRemove) {
            parent.removeChild(node)
        }
    }

    private fun setOrCreateChildText(
        doc: org.w3c.dom.Document,
        ns: String,
        parent: org.w3c.dom.Element,
        childName: String,
        text: String
    ) {
        var found: org.w3c.dom.Element? = null
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is org.w3c.dom.Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == childName) {
                    found = child
                    break
                }
            }
        }
        if (found != null) {
            found.textContent = text
        } else {
            val created = doc.createElementNS(ns, childName)
            created.textContent = text
            parent.appendChild(created)
        }
    }

    private fun insertRowSorted(sheetData: org.w3c.dom.Element, newRow: org.w3c.dom.Element, newRowIdx: Int) {
        val list = sheetData.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is org.w3c.dom.Element) {
                val idx = child.getAttribute("r").toIntOrNull()
                if (idx != null && idx > newRowIdx) {
                    sheetData.insertBefore(newRow, child)
                    return
                }
            }
        }
        sheetData.appendChild(newRow)
    }

    private fun insertCellSorted(row: org.w3c.dom.Element, newCell: org.w3c.dom.Element, newColIdx: Int) {
        val list = row.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is org.w3c.dom.Element) {
                val addr = child.getAttribute("r")
                val coord = CellCoordinate.parseAddress(addr)
                if (coord != null && coord.col > newColIdx) {
                    row.insertBefore(newCell, child)
                    return
                }
            }
        }
        row.appendChild(newCell)
    }

    private fun patchWorkbookCalcPr(xmlBytes: ByteArray): ByteArray {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
            val calcPrNodes = doc.getElementsByTagNameNS("*", "calcPr")
            if (calcPrNodes.length > 0) {
                (calcPrNodes.item(0) as org.w3c.dom.Element).setAttribute("fullCalcOnLoad", "1")
            } else {
                val ns = doc.documentElement.namespaceURI ?: "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                val calcPr = doc.createElementNS(ns, "calcPr")
                calcPr.setAttribute("fullCalcOnLoad", "1")
                doc.documentElement.appendChild(calcPr)
            }
            val transformer = TransformerFactory.newInstance().newTransformer()
            val out = ByteArrayOutputStream()
            transformer.transform(DOMSource(doc), StreamResult(out))
            out.toByteArray()
        } catch (_: Exception) {
            xmlBytes
        }
    }

    private fun removeRelationshipTarget(xmlBytes: ByteArray, targetSubstring: String): ByteArray {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
            val rels = doc.getElementsByTagNameNS("*", "Relationship")
            val toRemove = mutableListOf<org.w3c.dom.Node>()
            for (i in 0 until rels.length) {
                val node = rels.item(i)
                if (node is org.w3c.dom.Element) {
                    val target = node.getAttribute("Target")
                    if (target.contains(targetSubstring, ignoreCase = true)) {
                        toRemove.add(node)
                    }
                }
            }
            for (node in toRemove) {
                node.parentNode?.removeChild(node)
            }
            val transformer = TransformerFactory.newInstance().newTransformer()
            val out = ByteArrayOutputStream()
            transformer.transform(DOMSource(doc), StreamResult(out))
            out.toByteArray()
        } catch (_: Exception) {
            xmlBytes
        }
    }

    private fun removeContentTypeOverride(xmlBytes: ByteArray, partName: String): ByteArray {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
            val overrides = doc.getElementsByTagNameNS("*", "Override")
            val toRemove = mutableListOf<org.w3c.dom.Node>()
            for (i in 0 until overrides.length) {
                val node = overrides.item(i)
                if (node is org.w3c.dom.Element) {
                    val pn = node.getAttribute("PartName")
                    if (pn.equals(partName, ignoreCase = true) || pn.endsWith(partName, ignoreCase = true)) {
                        toRemove.add(node)
                    }
                }
            }
            for (node in toRemove) {
                node.parentNode?.removeChild(node)
            }
            val transformer = TransformerFactory.newInstance().newTransformer()
            val out = ByteArrayOutputStream()
            transformer.transform(DOMSource(doc), StreamResult(out))
            out.toByteArray()
        } catch (_: Exception) {
            xmlBytes
        }
    }

    private fun generateWorksheetXml(sheet: SpreadsheetWorksheet): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<sheetData>")

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

    private fun writeMinimalPackageStructure(zipOut: ZipOutputStream, workbook: SpreadsheetWorkbook = SpreadsheetWorkbook()) {
        val sheets = workbook.sheets.ifEmpty { listOf(SpreadsheetWorksheet(name = "Sheet1")) }

        val contentTypes = StringBuilder().apply {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            for (i in sheets.indices) {
                append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            append("</Types>")
        }.toString()
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

        val wbRels = StringBuilder().apply {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in sheets.indices) {
                append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            append("</Relationships>")
        }.toString()
        zipOut.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        zipOut.write(wbRels.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        val wbXml = StringBuilder().apply {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            for ((i, s) in sheets.withIndex()) {
                append("""<sheet name="${escapeXml(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("</sheets></workbook>")
        }.toString()
        zipOut.putNextEntry(ZipEntry(WORKBOOK_PATH))
        zipOut.write(wbXml.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        for ((i, s) in sheets.withIndex()) {
            zipOut.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
            zipOut.write(generateWorksheetXml(s))
            zipOut.closeEntry()
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
