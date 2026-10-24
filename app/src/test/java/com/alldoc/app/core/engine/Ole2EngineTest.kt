package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.legacy.Ole2Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class Ole2EngineTest {

    @Test
    fun testTextExtractionFromOleStream() {
        val baos = ByteArrayOutputStream()
        // 512-byte dummy OLE2 header
        baos.write(ByteArray(512) { 0x00 })

        // Write some UTF-16LE text: "Quarterly Financial Overview\n"
        val utf16Text = "Quarterly Financial Overview\n"
        baos.write(utf16Text.toByteArray(StandardCharsets.UTF_16LE))

        // Write some binary junk
        baos.write(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        // Write some ASCII text: "Total Revenue: $500,000\n"
        val asciiText = "Total Revenue: $500,000\n"
        baos.write(asciiText.toByteArray(StandardCharsets.US_ASCII))

        val extracted = Ole2Engine.extractTextRuns(baos.toByteArray())

        assertTrue("Extracted runs should contain UTF-16LE text", extracted.any { it.contains("Quarterly Financial Overview") })
        assertTrue("Extracted runs should contain ASCII text", extracted.any { it.contains("Total Revenue: $500,000") })
    }

    @Test
    fun testParseDoc() {
        val baos = ByteArrayOutputStream()
        baos.write(ByteArray(512))
        baos.write("Meeting Minutes 2024\nAttendees: Alice, Bob\nAction items discussed\n".toByteArray(StandardCharsets.UTF_16LE))

        val model = Ole2Engine.parseDoc(ByteArrayInputStream(baos.toByteArray()), "sample.doc")
        assertTrue(model.paragraphs.isNotEmpty())
        assertTrue(model.paragraphs.any { p -> p.runs.any { it.text.contains("Meeting Minutes 2024") } })
    }

    @Test
    fun testParseXls() {
        val baos = ByteArrayOutputStream()
        baos.write(ByteArray(512))
        baos.write("Revenue\nExpenses\nProfit\n$1000\n".toByteArray(StandardCharsets.US_ASCII))

        val model = Ole2Engine.parseXls(ByteArrayInputStream(baos.toByteArray()), "sample.xls")
        val sheet = model.workbook.activeSheet
        assertTrue(sheet.cells.isNotEmpty())
    }
}
