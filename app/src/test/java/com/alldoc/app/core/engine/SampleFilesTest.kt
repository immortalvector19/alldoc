package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.legacy.Ole2Engine
import com.alldoc.app.core.engine.openxml.CsvEngine
import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.engine.openxml.PptxEngine
import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.engine.text.TextEngine
import com.alldoc.app.core.model.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real-world format test matrix covering all document formats,
 * including special-character filenames, password PDFs, multi-sheet XLSX,
 * inline strings, and legacy OLE2 formats.
 */
class SampleFilesTest {

    companion object {
        private val samplesDir = File("build/test_sample_files").apply { mkdirs() }
        private val rootSampleDocsDir = run {
            val root = if (File("sample_documents").exists() || File("gradlew").exists() || File("gradlew.bat").exists()) {
                File("sample_documents")
            } else {
                File("../sample_documents")
            }
            root.apply { mkdirs() }
        }

        @BeforeClass
        @JvmStatic
        fun setupSampleFiles() {
            for (dir in listOf(samplesDir, rootSampleDocsDir)) {
                createValidPdf(File(dir, "sample.pdf"))
                createValidPdf(File(dir, "100% report+final #2.pdf"))
                createPasswordPdf(File(dir, "sample_password.pdf"))
                createSampleDocx(File(dir, "sample.docx"))
                createSampleDocx(File(dir, "sample_with_images.docx"))
                createSampleOle2(File(dir, "sample.doc"), "WordDocument", "Executive Briefing 2024\nAllDoc Project Specification\n")
                createSampleOle2(File(dir, "sample.xls"), "Workbook", "Quarterly Budget\n$50,000\n$75,000\n")
                createSampleOle2(File(dir, "sample.ppt"), "PowerPoint Document", "Roadmap Presentation\nQ1 Targets\nQ2 Launch\n")
                createSampleMultisheetXlsx(File(dir, "sample_multisheet.xlsx"))
                createSampleInlineStrXlsx(File(dir, "sample_inlinestr.xlsx"))
                createSamplePptx(File(dir, "sample.pptx"))
                createSampleCsv(File(dir, "sample.csv"))
                createSampleMarkdown(File(dir, "sample.md"))
                createSampleRtf(File(dir, "sample.rtf"))
                createSampleTxt(File(dir, "sample.txt"))
                createSampleJpeg(File(dir, "sample.jpg"))
                createSamplePng(File(dir, "sample.png"))
                createSampleWebp(File(dir, "sample.webp"))
                createCorruptedOle2(File(dir, "corrupt_truncated.ole2"))
            }
        }

        private fun createValidPdf(file: File) {
            val content = """%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
endobj
xref
0 4
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
trailer
<< /Size 4 /Root 1 0 R >>
startxref
190
%%EOF""".trimIndent()
            file.writeBytes(content.toByteArray(StandardCharsets.US_ASCII))
        }

        private fun createPasswordPdf(file: File) {
            // PDF with Encrypt dictionary indicating password protection
            val content = """%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
endobj
4 0 obj
<< /Filter /Standard /V 2 /R 3 /O (encrypted_owner_key) /U (encrypted_user_key) /P -4 >>
endobj
xref
0 5
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000190 00000 n 
trailer
<< /Size 5 /Root 1 0 R /Encrypt 4 0 R >>
startxref
290
%%EOF""".trimIndent()
            file.writeBytes(content.toByteArray(StandardCharsets.US_ASCII))
        }

        private fun createSampleDocx(file: File) {
            val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>
                        <w:p><w:r><w:t>Hello from AllDoc DOCX with Images</w:t></w:r></w:p>
                    </w:body>
                </w:document>""".trimIndent()

            val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
                </Relationships>""".trimIndent()

            val zipBytes = createZipBytes(mapOf(
                "word/document.xml" to documentXml.toByteArray(StandardCharsets.UTF_8),
                "word/_rels/document.xml.rels" to relsXml.toByteArray(StandardCharsets.UTF_8),
                "word/media/image1.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ))
            file.writeBytes(zipBytes)
        }

        private fun createSampleOle2(file: File, streamName: String, textContent: String) {
            val baos = ByteArrayOutputStream()
            // 8-byte OLE2 compound document magic header: D0 CF 11 E0 A1 B1 1A E1
            baos.write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()))
            // Pad to 512-byte header
            baos.write(ByteArray(504) { 0x00 })
            // Write stream identifier
            baos.write(streamName.toByteArray(StandardCharsets.ISO_8859_1))
            baos.write(byteArrayOf(0x00, 0x00))
            // Write text content in UTF-16LE
            baos.write(textContent.toByteArray(StandardCharsets.UTF_16LE))
            file.writeBytes(baos.toByteArray())
        }

        private fun createSampleMultisheetXlsx(file: File) {
            val wbXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets>
                        <sheet name="Summary" sheetId="1" r:id="rId1"/>
                        <sheet name="Quarterly" sheetId="2" r:id="rId2"/>
                    </sheets>
                </workbook>""".trimIndent()

            val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>""".trimIndent()

            val sheet1 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetData>
                        <row r="1"><c r="A1"><v>100</v></c></row>
                    </sheetData>
                </worksheet>""".trimIndent()

            val sheet2 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetData>
                        <row r="1"><c r="A1"><v>200</v></c></row>
                    </sheetData>
                </worksheet>""".trimIndent()

            val zipBytes = createZipBytes(mapOf(
                "xl/workbook.xml" to wbXml.toByteArray(StandardCharsets.UTF_8),
                "xl/_rels/workbook.xml.rels" to relsXml.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet1.xml" to sheet1.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet2.xml" to sheet2.toByteArray(StandardCharsets.UTF_8)
            ))
            file.writeBytes(zipBytes)
        }

        private fun createSampleInlineStrXlsx(file: File) {
            val wbXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="InlineSheet" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".trimIndent()

            val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""".trimIndent()

            val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetData>
                        <row r="1"><c r="A1" t="inlineStr"><is><t>Hello Inline String</t></is></c></row>
                    </sheetData>
                </worksheet>""".trimIndent()

            val zipBytes = createZipBytes(mapOf(
                "xl/workbook.xml" to wbXml.toByteArray(StandardCharsets.UTF_8),
                "xl/_rels/workbook.xml.rels" to relsXml.toByteArray(StandardCharsets.UTF_8),
                "xl/worksheets/sheet1.xml" to sheet.toByteArray(StandardCharsets.UTF_8)
            ))
            file.writeBytes(zipBytes)
        }

        private fun createSamplePptx(file: File) {
            val presXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                    <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
                </p:presentation>""".trimIndent()

            val slideXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                    <p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Quarterly Review</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>""".trimIndent()

            val zipBytes = createZipBytes(mapOf(
                "ppt/presentation.xml" to presXml.toByteArray(StandardCharsets.UTF_8),
                "ppt/slides/slide1.xml" to slideXml.toByteArray(StandardCharsets.UTF_8)
            ))
            file.writeBytes(zipBytes)
        }

        private fun createSampleCsv(file: File) {
            file.writeText("Year,Revenue,Growth\n2022,100M,15%\n2023,125M,25%\n2024,160M,28%\n")
        }

        private fun createSampleMarkdown(file: File) {
            file.writeText("# AllDoc Engineering\n\n- Real Engineering Workflow\n- Zero Vibe Coding\n")
        }

        private fun createSampleRtf(file: File) {
            file.writeText("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\fs24 Rich text document content}")
        }

        private fun createSampleTxt(file: File) {
            file.writeText("Plain text report data for AllDoc test suite.")
        }

        private fun createSampleJpeg(file: File) {
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()))
        }

        private fun createSamplePng(file: File) {
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77.toByte(), 0x53.toByte(), 0xDE.toByte()))
        }

        private fun createSampleWebp(file: File) {
            // RIFF....WEBPVP8
            val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20)
            file.writeBytes(webp)
        }

        private fun createCorruptedOle2(file: File) {
            // Truncated OLE2 with only 64 bytes instead of 512
            val bytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()) + ByteArray(32) { 0xFF.toByte() }
            file.writeBytes(bytes)
        }

        private fun createZipBytes(entries: Map<String, ByteArray>): ByteArray {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                for ((name, data) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            return baos.toByteArray()
        }
    }

    @Test
    fun testEveryFormatSniffing() {
        val formats = mapOf(
            "sample.pdf" to DocumentFormat.PDF,
            "100% report+final #2.pdf" to DocumentFormat.PDF,
            "sample_password.pdf" to DocumentFormat.PDF,
            "sample.docx" to DocumentFormat.DOCX,
            "sample_with_images.docx" to DocumentFormat.DOCX,
            "sample.doc" to DocumentFormat.DOC,
            "sample.xls" to DocumentFormat.XLS,
            "sample.ppt" to DocumentFormat.PPT,
            "sample_multisheet.xlsx" to DocumentFormat.XLSX,
            "sample_inlinestr.xlsx" to DocumentFormat.XLSX,
            "sample.pptx" to DocumentFormat.PPTX,
            "sample.csv" to DocumentFormat.CSV,
            "sample.md" to DocumentFormat.MARKDOWN,
            "sample.rtf" to DocumentFormat.RTF,
            "sample.txt" to DocumentFormat.TXT,
            "sample.jpg" to DocumentFormat.IMAGE_JPEG,
            "sample.png" to DocumentFormat.IMAGE_PNG,
            "sample.webp" to DocumentFormat.IMAGE_WEBP
        )

        for ((filename, expected) in formats) {
            val file = File(samplesDir, filename)
            assertTrue("File $filename must exist", file.exists())
            val format = file.inputStream().use { stream ->
                FileSniffer.sniffFormat(stream, filename)
            }
            assertEquals("Format for $filename must match", expected, format)
        }
    }

    @Test
    fun testEveryFormatParsing() {
        // 1. DOCX
        val docxModel = File(samplesDir, "sample.docx").inputStream().use {
            DocxEngine.parse(it, "sample.docx")
        }
        assertTrue(docxModel.paragraphs.isNotEmpty())

        // 2. Legacy DOC
        val docModel = File(samplesDir, "sample.doc").inputStream().use {
            Ole2Engine.parseDoc(it, "sample.doc")
        }
        assertTrue(docModel.paragraphs.any { p -> p.runs.any { it.text.contains("Executive Briefing") } })

        // 3. Multi-sheet XLSX
        val multiXlsx = File(samplesDir, "sample_multisheet.xlsx").inputStream().use {
            XlsxEngine.parse(it, "sample_multisheet.xlsx")
        }
        assertEquals(2, multiXlsx.workbook.sheets.size)
        assertEquals("Summary", multiXlsx.workbook.sheets[0].name)
        assertEquals("Quarterly", multiXlsx.workbook.sheets[1].name)

        // 4. Inline strings XLSX
        val inlineXlsx = File(samplesDir, "sample_inlinestr.xlsx").inputStream().use {
            XlsxEngine.parse(it, "sample_inlinestr.xlsx")
        }
        assertEquals("Hello Inline String", inlineXlsx.workbook.activeSheet.getCell(0, 0).displayValue)

        // 5. CSV
        val csvModel = File(samplesDir, "sample.csv").inputStream().use {
            CsvEngine.parse(it, "sample.csv")
        }
        assertTrue(csvModel.workbook.activeSheet.cells.isNotEmpty())

        // 6. Markdown & TXT
        val mdModel = File(samplesDir, "sample.md").inputStream().use {
            TextEngine.parse(it, "sample.md", DocumentFormat.MARKDOWN)
        }
        assertTrue(mdModel.text.contains("Real Engineering Workflow"))

        // 7. Corrupted OLE2 never crashes
        val corruptModel = File(samplesDir, "corrupt_truncated.ole2").inputStream().use {
            Ole2Engine.parseDoc(it, "corrupt.doc")
        }
        assertNotNull(corruptModel)
        assertTrue(corruptModel.paragraphs.isNotEmpty())
    }
}
