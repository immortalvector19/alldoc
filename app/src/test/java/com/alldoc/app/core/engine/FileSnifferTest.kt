package com.alldoc.app.core.engine

import com.alldoc.app.core.model.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileSnifferTest {

    @Test
    fun testPdfMagicSniffing() {
        val pdfData = "%PDF-1.7\n1 0 obj\n<<>>\nendobj".toByteArray(StandardCharsets.US_ASCII)
        val format = FileSniffer.sniffFormat(ByteArrayInputStream(pdfData), "sample.pdf")
        assertEquals(DocumentFormat.PDF, format)

        // Sniff with misreported MIME and extension
        val formatMisreported = FileSniffer.sniffFormat(
            ByteArrayInputStream(pdfData),
            "document.bin",
            "application/octet-stream"
        )
        assertEquals(DocumentFormat.PDF, formatMisreported)
    }

    @Test
    fun testOoxmlDocxSniffing() {
        val zipBytes = createSampleZip(mapOf("word/document.xml" to "<w:document/>"))
        val format = FileSniffer.sniffFormat(ByteArrayInputStream(zipBytes), "report.docx")
        assertEquals(DocumentFormat.DOCX, format)

        // Sniff even if misnamed as .doc
        val formatMisnamed = FileSniffer.sniffFormat(ByteArrayInputStream(zipBytes), "report.doc")
        assertEquals(DocumentFormat.DOCX, formatMisnamed)
    }

    @Test
    fun testOoxmlXlsxSniffing() {
        val zipBytes = createSampleZip(mapOf("xl/workbook.xml" to "<workbook/>"))
        val format = FileSniffer.sniffFormat(ByteArrayInputStream(zipBytes), "sheet.xlsx")
        assertEquals(DocumentFormat.XLSX, format)
    }

    @Test
    fun testOoxmlPptxSniffing() {
        val zipBytes = createSampleZip(mapOf("ppt/presentation.xml" to "<presentation/>"))
        val format = FileSniffer.sniffFormat(ByteArrayInputStream(zipBytes), "slides.pptx")
        assertEquals(DocumentFormat.PPTX, format)
    }

    @Test
    fun testOle2LegacySniffing() {
        // OLE2 magic header: D0 CF 11 E0 A1 B1 1A E1
        val oleHeader = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
        )
        val wordDocBytes = oleHeader + "WordDocument stream test content".toByteArray(StandardCharsets.ISO_8859_1)
        val formatDoc = FileSniffer.sniffFormat(ByteArrayInputStream(wordDocBytes), "old_report.doc")
        assertEquals(DocumentFormat.DOC, formatDoc)
        assertFalse("Legacy DOC must not be editable", formatDoc.isEditable)

        val xlsBytes = oleHeader + "Workbook stream test content".toByteArray(StandardCharsets.ISO_8859_1)
        val formatXls = FileSniffer.sniffFormat(ByteArrayInputStream(xlsBytes), "old_sheet.xls")
        assertEquals(DocumentFormat.XLS, formatXls)
        assertFalse("Legacy XLS must not be editable", formatXls.isEditable)

        val pptBytes = oleHeader + "PowerPoint Document stream".toByteArray(StandardCharsets.ISO_8859_1)
        val formatPpt = FileSniffer.sniffFormat(ByteArrayInputStream(pptBytes), "old_slides.ppt")
        assertEquals(DocumentFormat.PPT, formatPpt)
        assertFalse("Legacy PPT must not be editable", formatPpt.isEditable)
    }

    @Test
    fun testImageSniffing() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(DocumentFormat.IMAGE_JPEG, FileSniffer.sniffFormat(ByteArrayInputStream(jpegHeader), "photo.jpg"))

        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        assertEquals(DocumentFormat.IMAGE_PNG, FileSniffer.sniffFormat(ByteArrayInputStream(pngHeader), "icon.png"))
    }

    @Test
    fun testRtfAndCsvSniffing() {
        val rtfHeader = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\nHello}".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(DocumentFormat.RTF, FileSniffer.sniffFormat(ByteArrayInputStream(rtfHeader), "doc.rtf"))

        val csvData = "Name,Age,City\nAlice,30,New York\nBob,25,San Francisco\n".toByteArray(StandardCharsets.UTF_8)
        assertEquals(DocumentFormat.CSV, FileSniffer.sniffFormat(ByteArrayInputStream(csvData), "data.csv"))
    }

    @Test
    fun testUnknownBinaryDoesNotFallBackToText() {
        // Binary garbage with null bytes
        val binaryData = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x00, 0x14, 0x7E, 0x00)
        val format = FileSniffer.sniffFormat(ByteArrayInputStream(binaryData), "unknown.dat")
        assertEquals(DocumentFormat.UNKNOWN, format)
    }

    @Test
    fun testDocumentFormatBugsFixed() {
        // A5: fromExtension maps "m4a" to TXT bug fixed
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.fromExtension("m4a"))

        // A5: fromMimeType maps "msword" to DOCX bug fixed (must map to legacy DOC)
        assertEquals(DocumentFormat.DOC, DocumentFormat.fromMimeType("application/msword"))

        // A5: fromMimeType maps "excel" to XLSX bug fixed (must map to legacy XLS)
        assertEquals(DocumentFormat.XLS, DocumentFormat.fromMimeType("application/vnd.ms-excel"))
        assertEquals(DocumentFormat.XLSX, DocumentFormat.fromMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

        // Legacy formats are not editable
        assertFalse(DocumentFormat.DOC.isEditable)
        assertFalse(DocumentFormat.XLS.isEditable)
        assertFalse(DocumentFormat.PPT.isEditable)
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
