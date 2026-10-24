package com.alldoc.app.core.model

data class SpreadsheetWorkbook(
    val sheets: List<SpreadsheetWorksheet> = listOf(SpreadsheetWorksheet("Sheet1")),
    val activeSheetIndex: Int = 0
) {
    val activeSheet: SpreadsheetWorksheet
        get() = sheets.getOrElse(activeSheetIndex) { sheets.firstOrNull() ?: SpreadsheetWorksheet("Sheet1") }

    fun updateActiveSheet(updated: SpreadsheetWorksheet): SpreadsheetWorkbook {
        val newSheets = sheets.toMutableList()
        if (activeSheetIndex in newSheets.indices) {
            newSheets[activeSheetIndex] = updated
        } else {
            newSheets.add(updated)
        }
        return copy(sheets = newSheets)
    }

    fun addSheet(name: String): SpreadsheetWorkbook {
        val uniqueName = generateUniqueSheetName(name)
        val newSheet = SpreadsheetWorksheet(uniqueName, index = sheets.size)
        return copy(sheets = sheets + newSheet, activeSheetIndex = sheets.size)
    }

    private fun generateUniqueSheetName(baseName: String): String {
        var name = baseName
        var counter = 1
        while (sheets.any { it.name.equals(name, ignoreCase = true) }) {
            counter++
            name = "$baseName $counter"
        }
        return name
    }
}

data class SpreadsheetWorksheet(
    val name: String,
    val index: Int = 0,
    val cells: Map<CellCoordinate, CellData> = emptyMap(),
    val rowCount: Int = 50,
    val colCount: Int = 26
) {
    fun getCell(row: Int, col: Int): CellData {
        return cells[CellCoordinate(row, col)] ?: CellData.EMPTY
    }

    fun updateCell(row: Int, col: Int, cellData: CellData): SpreadsheetWorksheet {
        val newCells = cells.toMutableMap()
        val coord = CellCoordinate(row, col)
        val existing = cells[coord]
        val resolvedCell = if (existing != null) {
            cellData.copy(
                originalRawValue = existing.originalRawValue,
                originalFormula = existing.originalFormula
            )
        } else {
            cellData
        }

        if (resolvedCell.isEmpty) {
            newCells.remove(coord)
        } else {
            newCells[coord] = resolvedCell
        }
        val newRowCount = maxOf(rowCount, row + 1)
        val newColCount = maxOf(colCount, col + 1)
        return copy(cells = newCells, rowCount = newRowCount, colCount = newColCount)
    }
}

data class CellCoordinate(val row: Int, val col: Int) {
    val address: String
        get() = "${columnName(col)}${row + 1}"

    companion object {
        fun columnName(col: Int): String {
            var temp = col
            val sb = StringBuilder()
            while (temp >= 0) {
                sb.append(('A'.code + (temp % 26)).toChar())
                temp = (temp / 26) - 1
            }
            return sb.reverse().toString()
        }

        fun parseAddress(address: String): CellCoordinate? {
            val trimmed = address.trim().uppercase()
            val letters = trimmed.takeWhile { it.isLetter() }
            val digits = trimmed.substring(letters.length).toIntOrNull() ?: return null
            if (letters.isEmpty() || digits < 1) return null

            var col = 0
            for (ch in letters) {
                col = col * 26 + (ch.code - 'A'.code + 1)
            }
            return CellCoordinate(digits - 1, col - 1)
        }
    }
}

data class CellData(
    val rawValue: String = "",
    val formula: String? = null,
    val computedValue: String? = null,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val textColor: Long? = null,
    val backgroundColor: Long? = null,
    val originalRawValue: String = rawValue,
    val originalFormula: String? = formula
) {
    val displayValue: String
        get() = computedValue ?: rawValue

    val isEmpty: Boolean
        get() = rawValue.isEmpty() && formula.isNullOrEmpty()

    val isEdited: Boolean
        get() = (rawValue != originalRawValue) || (formula != originalFormula)

    companion object {
        val EMPTY = CellData()
    }
}
