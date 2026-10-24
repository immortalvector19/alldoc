package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.engine.pdf.PdfManipulator
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.DocumentAnnotations
import com.alldoc.app.core.model.Point2D
import com.alldoc.app.core.model.WordTextRun
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VerificationArtifactsExportTest {

    private val verificationDir = File("docs/verification").apply { mkdirs() }

    @Test
    fun exportVerifiedSavedExcel() {
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
    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain" Target="calcChain.xml"/>
</Relationships>""".trimIndent()

        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
    <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
    <Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/>
</Types>""".trimIndent()

        val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <cols><col min="1" max="2" width="28.5"/></cols>
    <sheetData>
        <row r="1" ht="28" customHeight="1">
            <c r="A1" s="1"><v>100</v></c>
            <c r="B1" s="5"><v>45200</v></c>
            <c r="C1" s="2"><f>SUM(A1:B1)</f><v>45300</v></c>
        </row>
    </sheetData>
    <mergeCells count="1">
        <mergeCell ref="D1:E1"/>
    </mergeCells>
</worksheet>""".trimIndent()

        val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <sheetData>
        <row r="1"><c r="A1"><v>99999</v></c></row>
    </sheetData>
</worksheet>""".trimIndent()

        val calcChainXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<calcChain xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <c r="C1" i="1"/>
</calcChain>""".trimIndent()

        val originalBytes = ByteArrayOutputStream().use { baos ->
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

                zos.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
                zos.write(sheet2Xml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("xl/calcChain.xml"))
                zos.write(calcChainXml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val model = XlsxEngine.parse(ByteArrayInputStream(originalBytes), "sample.xlsx")
        val editedSheet0 = model.workbook.sheets[0].updateCell(0, 0, CellData(rawValue = "500"))
        val updatedModel = model.copy(
            workbook = model.workbook.copy(sheets = listOf(editedSheet0, model.workbook.sheets[1]))
        )

        val outputFile = File(verificationDir, "verified_saved_excel.xlsx")
        FileOutputStream(outputFile).use { out ->
            XlsxEngine.save(updatedModel, null, out)
        }

        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }

    @Test
    fun exportVerifiedSavedWord() {
        val docXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:body>
        <w:p>
            <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
            <w:r><w:t>Project Verification Report</w:t></w:r>
        </w:p>
        <w:p>
            <w:r><w:rPr><w:b/></w:rPr><w:t>Hello </w:t></w:r>
            <w:r><w:rPr><w:i/></w:rPr><w:t>World</w:t></w:r>
            <w:r><w:t> - AllDoc verified lossless saving.</w:t></w:r>
        </w:p>
        <w:tbl>
            <w:tr>
                <w:tc><w:p><w:r><w:t>Metric</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>Status</w:t></w:r></w:p></w:tc>
            </w:tr>
            <w:tr>
                <w:tc><w:p><w:r><w:t>Document Order</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>Preserved</w:t></w:r></w:p></w:tc>
            </w:tr>
        </w:tbl>
        <w:p>
            <w:r><w:t>Closing remarks after table.</w:t></w:r>
        </w:p>
        <w:sectPr>
            <w:pgSz w:w="12240" w:h="15840"/>
            <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
        </w:sectPr>
    </w:body>
</w:document>""".trimIndent()

        val originalBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("word/document.xml"))
                zos.write(docXml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val model = DocxEngine.parse(ByteArrayInputStream(originalBytes), "sample.docx")
        val p1 = model.paragraphs[1]
        val updatedRuns = listOf(
            p1.runs[0],
            p1.runs[1].copy(text = "Universe"),
            p1.runs[2]
        )
        val updatedParagraphs = listOf(model.paragraphs[0], p1.copy(runs = updatedRuns), model.paragraphs[2])
        val updatedModel = model.copy(paragraphs = updatedParagraphs)

        val outputFile = File(verificationDir, "verified_saved_word.docx")
        FileOutputStream(outputFile).use { out ->
            DocxEngine.save(updatedModel, out)
        }

        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }

    @Test
    fun exportVerifiedSavedPdf() = runBlocking {
        val originalPdfBytes = ByteArrayOutputStream().use { baos ->
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 20f)
                    cs.newLineAtOffset(50f, 750f)
                    cs.showText("AllDoc Professional PDF Document")
                    cs.endText()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 710f)
                    cs.showText("This text remains 100% vector, selectable, and searchable underneath annotations.")
                    cs.endText()
                }
                doc.save(baos)
            }
            baos.toByteArray()
        }

        val yorubaNote = "Ẹ̀kọ́ ṣe àṣeyọrí"
        val annotations = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    Annotation.Highlight(
                        targetPage = 0,
                        boundsLeft = 45f,
                        boundsTop = 75f,
                        boundsRight = 550f,
                        boundsBottom = 100f,
                        colorArgb = 0x80FFFF00
                    ),
                    Annotation.InkStroke(
                        targetPage = 0,
                        points = listOf(Point2D(50f, 200f), Point2D(150f, 220f), Point2D(250f, 190f)),
                        strokeWidth = 3f,
                        colorArgb = 0xFFFF0000
                    ),
                    Annotation.TextNote(
                        targetPage = 0,
                        text = yorubaNote,
                        x = 50f,
                        y = 150f,
                        fontSizeSp = 14f,
                        colorArgb = 0xFF1B5E20
                    ),
                    Annotation.SignatureStamp(
                        targetPage = 0,
                        paths = listOf(
                            listOf(Point2D(50f, 600f), Point2D(80f, 630f), Point2D(120f, 590f), Point2D(160f, 620f))
                        ),
                        boundingX = 50f,
                        boundingY = 590f,
                        width = 110f,
                        height = 40f,
                        colorArgb = 0xFF0D47A1
                    )
                )
            )
        )

        val outputFile = File(verificationDir, "verified_saved_pdf.pdf")
        FileOutputStream(outputFile).use { out ->
            PdfManipulator.exportWithAnnotations(originalPdfBytes, annotations, out)
        }

        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }
}
