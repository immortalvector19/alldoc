package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.SpreadsheetWorksheet
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxFormulaFallbackTest {

    @Test
    fun testUnsupportedFormulaFallsBackToCachedValue() {
        val sheet = SpreadsheetWorksheet(
            name = "TestSheet",
            cells = mapOf(
                CellCoordinate(0, 0) to CellData(
                    rawValue = "Cached Result 42",
                    formula = "=VLOOKUP(A2, Table1, 2, FALSE)",
                    cachedFormulaValue = "Cached Result 42"
                ),
                CellCoordinate(1, 0) to CellData(
                    rawValue = "100.00",
                    formula = "=INDEX(B1:B10, 3)",
                    cachedFormulaValue = "100.00"
                )
            )
        )

        val evaluated = XlsxEngine.evaluateFormulas(sheet)

        // Must display the cached value rather than #NAME? or the raw formula
        assertEquals("Cached Result 42", evaluated.getCell(0, 0).displayValue)
        assertEquals("100.00", evaluated.getCell(1, 0).displayValue)
    }

    @Test
    fun testSupportedFormulaEvaluatesAccurately() {
        val sheet = SpreadsheetWorksheet(
            name = "MathSheet",
            cells = mapOf(
                CellCoordinate(0, 0) to CellData(rawValue = "10"),
                CellCoordinate(1, 0) to CellData(rawValue = "20"),
                CellCoordinate(2, 0) to CellData(
                    rawValue = "0",
                    formula = "=SUM(A1:A2)"
                )
            )
        )

        val evaluated = XlsxEngine.evaluateFormulas(sheet)
        assertEquals("30", evaluated.getCell(2, 0).displayValue)
    }
}
