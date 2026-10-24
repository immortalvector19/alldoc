package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.openxml.CsvEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CsvEngineTest {

    @Test
    fun testCsvParseAndSaveWithQuotes() {
        val csvInput = """Name,Role,Description
Alice,Engineer,"Likes code, coffee"
Bob,Designer,"Quotes ""here"" works"
"""
        val model = CsvEngine.parse(ByteArrayInputStream(csvInput.toByteArray(Charsets.UTF_8)), "test.csv")
        val sheet = model.workbook.activeSheet

        assertEquals("Alice", sheet.getCell(1, 0).displayValue)
        assertEquals("Engineer", sheet.getCell(1, 1).displayValue)
        assertEquals("Likes code, coffee", sheet.getCell(1, 2).displayValue)

        assertEquals("Bob", sheet.getCell(2, 0).displayValue)
        assertEquals("Designer", sheet.getCell(2, 1).displayValue)
        assertEquals("Quotes \"here\" works", sheet.getCell(2, 2).displayValue)

        val out = ByteArrayOutputStream()
        CsvEngine.save(model, out)
        val reloaded = CsvEngine.parse(ByteArrayInputStream(out.toByteArray()), "test.csv")

        assertEquals("Likes code, coffee", reloaded.workbook.activeSheet.getCell(1, 2).displayValue)
        assertEquals("Quotes \"here\" works", reloaded.workbook.activeSheet.getCell(2, 2).displayValue)
    }
}
