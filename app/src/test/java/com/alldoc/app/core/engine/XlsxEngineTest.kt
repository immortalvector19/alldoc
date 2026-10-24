package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.SpreadsheetWorksheet
import org.junit.Assert.assertEquals
import org.junit.Test

class XlsxEngineTest {

    @Test
    fun testFormulaEvaluation() {
        var sheet = SpreadsheetWorksheet(name = "Test")
        // Put numbers in A1=10, A2=20, A3=30
        sheet = sheet.updateCell(0, 0, CellData(rawValue = "10"))
        sheet = sheet.updateCell(1, 0, CellData(rawValue = "20"))
        sheet = sheet.updateCell(2, 0, CellData(rawValue = "30"))

        // Put formulas:
        // B1 = SUM(A1:A3)
        sheet = sheet.updateCell(0, 1, CellData(rawValue = "", formula = "=SUM(A1:A3)"))
        // B2 = AVERAGE(A1:A3)
        sheet = sheet.updateCell(1, 1, CellData(rawValue = "", formula = "=AVERAGE(A1:A3)"))
        // B3 = MAX(A1:A3)
        sheet = sheet.updateCell(2, 1, CellData(rawValue = "", formula = "=MAX(A1:A3)"))
        // B4 = A1+A2
        sheet = sheet.updateCell(3, 1, CellData(rawValue = "", formula = "=A1+A2"))

        val evaluated = XlsxEngine.evaluateFormulas(sheet)

        assertEquals("60", evaluated.getCell(0, 1).displayValue)
        assertEquals("20", evaluated.getCell(1, 1).displayValue)
        assertEquals("30", evaluated.getCell(2, 1).displayValue)
        assertEquals("30", evaluated.getCell(3, 1).displayValue)
    }

    @Test
    fun testCellAddressParsing() {
        val a1 = CellCoordinate.parseAddress("A1")
        assertEquals(0, a1?.row)
        assertEquals(0, a1?.col)
        assertEquals("A1", a1?.address)

        val b5 = CellCoordinate.parseAddress("B5")
        assertEquals(4, b5?.row)
        assertEquals(1, b5?.col)
        assertEquals("B5", b5?.address)

        val z10 = CellCoordinate.parseAddress("Z10")
        assertEquals(9, z10?.row)
        assertEquals(25, z10?.col)
        assertEquals("Z10", z10?.address)
    }
}
