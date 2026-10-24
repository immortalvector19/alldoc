package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.openxml.XlsxEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxEngineMultiSheetTest {

    @Test
    fun testExcelDateFormatting() {
        // Test Excel date serial 45321 (corresponds to 2024-01-30)
        val formatted = XlsxEngine.formatExcelDate(45321.0)
        assertEquals("2024-01-30", formatted)

        // Test Excel date serial 44927 (corresponds to 2023-01-01)
        val formatted2023 = XlsxEngine.formatExcelDate(44927.0)
        assertEquals("2023-01-01", formatted2023)
    }

    @Test
    fun testMultiSheetAndInlineStringParsing() {
        val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet name="Summary" sheetId="1" r:id="rId1"/>
                    <sheet name="Quarterly Details" sheetId="2" r:id="rId2"/>
                </sheets>
            </workbook>""".trimIndent()

        val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheetSummary.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheetDetails.xml"/>
            </Relationships>""".trimIndent()

        // Sheet 1 has inlineStr and a date cell
        val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>Revenue Summary</t></is></c>
                        <c r="B1" s="1"><v>45321</v></c>
                    </row>
                </sheetData>
            </worksheet>""".trimIndent()

        // Sheet 2 has cell data
        val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>Q1 Sales</t></is></c>
                        <c r="B1"><v>5000</v></c>
                    </row>
                </sheetData>
            </worksheet>""".trimIndent()

        // Styles defining style index 1 as numFmtId 14 (standard Excel date format)
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <cellXfs count="2">
                    <xf numFmtId="0"/>
                    <xf numFmtId="14"/>
                </cellXfs>
            </styleSheet>""".trimIndent()

        val entries = mapOf(
            "xl/workbook.xml" to workbookXml,
            "xl/_rels/workbook.xml.rels" to relsXml,
            "xl/styles.xml" to stylesXml,
            "xl/worksheets/sheetSummary.xml" to sheet1Xml,
            "xl/worksheets/sheetDetails.xml" to sheet2Xml
        )

        val zipBytes = createSampleZip(entries)
        val model = XlsxEngine.parse(ByteArrayInputStream(zipBytes), "test_multisheet.xlsx")

        // 1. Verify multi-sheet support
        assertEquals(2, model.workbook.sheets.size)
        assertEquals("Summary", model.workbook.sheets[0].name)
        assertEquals("Quarterly Details", model.workbook.sheets[1].name)

        // 2. Verify inlineStr parsing (<is><t>)
        val sheet1 = model.workbook.sheets[0]
        assertEquals("Revenue Summary", sheet1.getCell(0, 0).displayValue)

        // 3. Verify date cell formatted as date instead of raw serial
        assertEquals("2024-01-30", sheet1.getCell(0, 1).displayValue)

        // 4. Verify sheet 2 content
        val sheet2 = model.workbook.sheets[1]
        assertEquals("Q1 Sales", sheet2.getCell(0, 0).displayValue)
        assertEquals("5000", sheet2.getCell(0, 1).displayValue)
    }

    private fun createSampleZip(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
