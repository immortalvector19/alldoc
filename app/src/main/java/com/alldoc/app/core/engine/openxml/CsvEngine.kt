package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.SpreadsheetWorkbook
import com.alldoc.app.core.model.SpreadsheetWorksheet
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

object CsvEngine {

    fun parse(inputStream: InputStream, uriString: String): SpreadsheetDocumentModel {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val rows = parseCsvRows(reader)

        val cells = mutableMapOf<CellCoordinate, CellData>()
        var maxCol = 0

        for ((r, row) in rows.withIndex()) {
            if (row.size > maxCol) maxCol = row.size
            for ((c, value) in row.withIndex()) {
                if (value.isNotEmpty()) {
                    cells[CellCoordinate(r, c)] = CellData(rawValue = value)
                }
            }
        }

        val worksheet = SpreadsheetWorksheet(
            name = "CSV",
            cells = cells,
            rowCount = maxOf(rows.size + 10, 30),
            colCount = maxOf(maxCol + 5, 10)
        )

        return SpreadsheetDocumentModel(
            uriString = uriString,
            workbook = SpreadsheetWorkbook(sheets = listOf(worksheet))
        )
    }

    fun save(model: SpreadsheetDocumentModel, outputStream: OutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        val sheet = model.workbook.activeSheet

        val maxRow = sheet.cells.keys.maxOfOrNull { it.row } ?: 0
        val maxCol = sheet.cells.keys.maxOfOrNull { it.col } ?: 0

        for (r in 0..maxRow) {
            val line = (0..maxCol).joinToString(",") { c ->
                val cell = sheet.cells[CellCoordinate(r, c)]
                escapeCsv(cell?.displayValue ?: "")
            }
            writer.write(line)
            writer.write("\r\n")
        }
        writer.flush()
    }

    private fun parseCsvRows(reader: BufferedReader): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false

        var chInt = reader.read()
        while (chInt != -1) {
            val ch = chInt.toChar()

            if (inQuotes) {
                if (ch == '"') {
                    val nextInt = reader.read()
                    if (nextInt != -1 && nextInt.toChar() == '"') {
                        currentField.append('"')
                    } else {
                        inQuotes = false
                        chInt = nextInt
                        continue
                    }
                } else {
                    currentField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        // Look ahead for \n
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(ch)
                }
            }
            chInt = reader.read()
        }

        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }

        return rows
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
