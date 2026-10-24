package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class XlsxEngineLosslessMultiSheetRoundTripTest {

    @Test
    fun testExcelInPlacePackagePatchingPreservesAllSheetsAndStyles() {
        val wbXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    <sheets>
        <sheet name="Summary" sheetId="1" r:id="rId1"/>
        <sheet name="Financials" sheetId="2" r:id="rId2"/>
    </sheets>
</workbook>""".trimIndent()

        val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
</Relationships>""".trimIndent()

        val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <cols><col min="1" max="2" width="28.5"/></cols>
    <sheetData>
        <row r="1"><c r="A1"><v>1000</v></c></row>
    </sheetData>
</worksheet>""".trimIndent()

        val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <sheetData>
        <row r="1"><c r="A1"><v>5000</v></c></row>
    </sheetData>
</worksheet>""".trimIndent()

        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"/></styleSheet>""".trimIndent()

        val chartXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart/></c:chartSpace>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(wbXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(relsXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            zos.write(sheet2Xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/styles.xml"))
            zos.write(stylesXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/charts/chart1.xml"))
            zos.write(chartXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        val originalXlsxBytes = baos.toByteArray()

        // 1. Parse workbook
        val model = XlsxEngine.parse(ByteArrayInputStream(originalXlsxBytes), "workbook.xlsx")
        assertEquals(2, model.workbook.sheets.size)
        assertEquals("Summary", model.workbook.sheets[0].name)
        assertEquals("Financials", model.workbook.sheets[1].name)
        assertEquals("1000", model.workbook.sheets[0].getCell(0, 0).rawValue)
        assertEquals("5000", model.workbook.sheets[1].getCell(0, 0).rawValue)

        // 2. Modify only cell A1 on sheet 0
        val updatedSheet0 = model.workbook.sheets[0].updateCell(
            0, 0, CellData(rawValue = "9999")
        )
        val updatedWorkbook = model.workbook.copy(
            sheets = listOf(updatedSheet0, model.workbook.sheets[1])
        )
        val updatedModel = model.copy(workbook = updatedWorkbook)

        // 3. Save modified workbook
        val saveOut = ByteArrayOutputStream()
        XlsxEngine.save(updatedModel, null, saveOut)
        val savedXlsxBytes = saveOut.toByteArray()

        // 4. Inspect ZIP entries in saved file
        val savedEntries = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(ByteArrayInputStream(savedXlsxBytes))
        var entry = zipIn.nextEntry
        while (entry != null) {
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            savedEntries[entry.name] = out.toByteArray()
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // Verify chart and styles are 100% PRESERVED
        assertNotNull("Styles must be preserved", savedEntries["xl/styles.xml"])
        assertTrue(stylesXml.toByteArray(StandardCharsets.UTF_8).contentEquals(savedEntries["xl/styles.xml"]!!))

        assertNotNull("Charts must be preserved", savedEntries["xl/charts/chart1.xml"])
        assertTrue(chartXml.toByteArray(StandardCharsets.UTF_8).contentEquals(savedEntries["xl/charts/chart1.xml"]!!))

        // Verify sheet 2 was NOT deleted
        assertNotNull("Sheet 2 (Financials) must be preserved", savedEntries["xl/worksheets/sheet2.xml"])

        // Verify column widths in sheet 1 are preserved
        val savedSheet1 = String(savedEntries["xl/worksheets/sheet1.xml"]!!, StandardCharsets.UTF_8)
        assertTrue("Column widths <cols> must be preserved", savedSheet1.contains("width=\"28.5\"") || savedSheet1.contains("cols"))

        // 5. Re-parse saved file to confirm full fidelity
        val reParsed = XlsxEngine.parse(ByteArrayInputStream(savedXlsxBytes), "saved.xlsx")
        assertEquals(2, reParsed.workbook.sheets.size)
        assertEquals("Summary", reParsed.workbook.sheets[0].name)
        assertEquals("Financials", reParsed.workbook.sheets[1].name)
        assertEquals("9999", reParsed.workbook.sheets[0].getCell(0, 0).rawValue)
        assertEquals("5000", reParsed.workbook.sheets[1].getCell(0, 0).rawValue)
    }

    @Test
    fun testDateCellPreservesNumericSerialAndCalcChainPruningWithFullCalcOnLoad() {
        val wbXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    <sheets>
        <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
    </sheets>
</workbook>""".trimIndent()

        val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain" Target="calcChain.xml"/>
</Relationships>""".trimIndent()

        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
    <Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/>
</Types>""".trimIndent()

        // sheet1 with:
        // - row 1: ht="28" customHeight="1" hidden="1"
        // - cell A1: text with style s="2"
        // - cell B1: unedited DATE serial 45200 with style s="5" (must stay serial 45200 with s="5"!)
        // - cell C1: formula =SUM(A1:B1)
        // - mergeCells ref="A1:B1"
        val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <sheetData>
        <row r="1" ht="28" customHeight="1" hidden="1">
            <c r="A1" s="2"><v>100</v></c>
            <c r="B1" s="5"><v>45200</v></c>
            <c r="C1" s="3"><f>SUM(A1:B1)</f><v>45300</v></c>
        </row>
    </sheetData>
    <mergeCells count="1">
        <mergeCell ref="A1:B1"/>
    </mergeCells>
</worksheet>""".trimIndent()

        val calcChainXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<calcChain xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <c r="C1" i="1"/>
</calcChain>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(contentTypesXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(wbXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(relsXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/calcChain.xml"))
            zos.write(calcChainXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        // 1. Parse
        val model = XlsxEngine.parse(ByteArrayInputStream(baos.toByteArray()), "test.xlsx")
        val sheet = model.workbook.sheets[0]

        // 2. Edit ONLY cell A1 (changing 100 -> 200). Leave date cell B1 and formula cell C1 unedited.
        val editedSheet = sheet.updateCell(0, 0, CellData(rawValue = "200"))
        val editedModel = model.copy(workbook = model.workbook.copy(sheets = listOf(editedSheet)))

        // 3. Save
        val saveOut = ByteArrayOutputStream()
        XlsxEngine.save(editedModel, null, saveOut)

        // 4. Verify ZIP entries
        val savedEntries = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(ByteArrayInputStream(saveOut.toByteArray()))
        var entry = zipIn.nextEntry
        while (entry != null) {
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            savedEntries[entry.name] = out.toByteArray()
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // A) calcChain.xml MUST be removed from ZIP to prevent Excel corrupt chain repair error!
        assertNull("xl/calcChain.xml must be removed upon formula/cell edit", savedEntries["xl/calcChain.xml"])

        // B) Relationship for calcChain MUST be pruned from workbook.xml.rels
        val savedRels = String(savedEntries["xl/_rels/workbook.xml.rels"]!!, StandardCharsets.UTF_8)
        assertFalse("calcChain relationship must be removed", savedRels.contains("calcChain.xml"))

        // C) Content-Types override for calcChain MUST be pruned
        val savedContentTypes = String(savedEntries["[Content_Types].xml"]!!, StandardCharsets.UTF_8)
        assertFalse("calcChain override must be removed from Content_Types", savedContentTypes.contains("/xl/calcChain.xml"))

        // D) fullCalcOnLoad="1" MUST be set in workbook.xml
        val savedWorkbook = String(savedEntries["xl/workbook.xml"]!!, StandardCharsets.UTF_8)
        assertTrue("fullCalcOnLoad must be set to 1 in calcPr", savedWorkbook.contains("fullCalcOnLoad=\"1\""))

        // E) Check sheet1 XML:
        val savedSheetXml = String(savedEntries["xl/worksheets/sheet1.xml"]!!, StandardCharsets.UTF_8)

        // 1. Row attributes (ht="28" customHeight="1" hidden="1") MUST be intact:
        assertTrue("Row attributes preserved", savedSheetXml.contains("ht=\"28\"") && savedSheetXml.contains("hidden=\"1\""))

        // 2. Unedited date cell B1 MUST retain numeric serial 45200 and style s="5", NOT text!
        assertTrue(
            "Date cell B1 must keep numeric serial <v>45200</v> and style s=\"5\"",
            savedSheetXml.contains("""<c r="B1" s="5"><v>45200</v></c>""") ||
                    (savedSheetXml.contains("""r="B1"""") && savedSheetXml.contains("""s="5"""") && savedSheetXml.contains("""<v>45200</v>"""))
        )
        assertFalse("Date cell B1 must never be converted to inlineStr", savedSheetXml.contains("""<c r="B1" t="inlineStr">"""))

        // 3. Merged cells must be preserved intact:
        assertTrue("mergeCells must be preserved", savedSheetXml.contains("""<mergeCell ref="A1:B1"/>"""))

        // 4. Edited cell A1 must have new value 200 while preserving its style s="2":
        assertTrue("Edited cell A1 preserved style s=\"2\" and updated value", savedSheetXml.contains("""s="2"""") && savedSheetXml.contains("<v>200</v>"))
    }
}
