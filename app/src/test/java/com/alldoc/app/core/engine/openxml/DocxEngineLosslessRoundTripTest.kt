package com.alldoc.app.core.engine.openxml

import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTextRun
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

class DocxEngineLosslessRoundTripTest {

    @Test
    fun testWordLosslessRoundTripAndDomPatching() {
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    <w:body>
        <w:p>
            <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
            <w:r><w:t>Executive Summary</w:t></w:r>
        </w:p>
        <w:p>
            <w:pPr><w:pStyle w:val="ListParagraph1"/></w:pPr>
            <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>Item with ListParagraph1 not Heading</w:t></w:r>
        </w:p>
        <w:tbl>
            <w:tr>
                <w:tc><w:p><w:r><w:t>Cell 1,1</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>Cell 1,2</w:t></w:r></w:p></w:tc>
            </w:tr>
        </w:tbl>
        <w:p>
            <w:r><w:t>Closing notes after table</w:t></w:r>
        </w:p>
        <w:sectPr>
            <w:pgSz w:w="12240" w:h="15840"/>
            <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
        </w:sectPr>
    </w:body>
</w:document>""".trimIndent()

        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
</Relationships>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zos.write(relsXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/media/image1.png"))
            zos.write(imageBytes)
            zos.closeEntry()
        }
        val originalDocxBytes = baos.toByteArray()

        // 1. Parse document
        val model = DocxEngine.parse(ByteArrayInputStream(originalDocxBytes), "test.docx")
        assertEquals(3, model.paragraphs.size)
        assertEquals(1, model.tables.size)

        // Verify bugfix: ListParagraph1 is NOT treated as Heading 1
        assertEquals(1, model.paragraphs[0].headingLevel)
        assertNull("ListParagraph1 must not match Heading 1", model.paragraphs[1].headingLevel)

        // Verify bugfix: <w:b w:val="0"/> is NOT treated as bold
        assertFalse("w:b w:val='0' must not be bold", model.paragraphs[1].runs[0].isBold)

        // 2. Modify only paragraph 0
        val updatedParagraphs = model.paragraphs.toMutableList()
        updatedParagraphs[0] = model.paragraphs[0].copy(
            runs = listOf(WordTextRun(text = "Updated Executive Summary 2026", isBold = true))
        )
        val updatedModel = model.copy(paragraphs = updatedParagraphs)

        // 3. Save modified document
        val saveOut = ByteArrayOutputStream()
        DocxEngine.save(updatedModel, saveOut)
        val savedDocxBytes = saveOut.toByteArray()

        // 4. Verify ZIP parts and XML preservation
        val savedParts = mutableMapOf<String, ByteArray>()
        val zipIn = ZipInputStream(ByteArrayInputStream(savedDocxBytes))
        var entry = zipIn.nextEntry
        while (entry != null) {
            val out = ByteArrayOutputStream()
            zipIn.copyTo(out)
            savedParts[entry.name] = out.toByteArray()
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // Verify media and rels are 100% byte-for-byte preserved
        assertNotNull(savedParts["word/media/image1.png"])
        assertTrue(imageBytes.contentEquals(savedParts["word/media/image1.png"]!!))
        assertNotNull(savedParts["word/_rels/document.xml.rels"])
        assertTrue(relsXml.toByteArray(StandardCharsets.UTF_8).contentEquals(savedParts["word/_rels/document.xml.rels"]!!))

        // Verify word/document.xml preserved structure, sectPr, table order, and patched only text
        val savedDocXml = String(savedParts["word/document.xml"]!!, StandardCharsets.UTF_8)
        assertTrue("Must contain updated text", savedDocXml.contains("Updated Executive Summary 2026"))
        assertTrue("Must preserve section properties sectPr", savedDocXml.contains("pgSz"))
        assertTrue("Must preserve page margins", savedDocXml.contains("pgMar"))
        assertTrue("Must preserve table in document", savedDocXml.contains("<w:tbl") || savedDocXml.contains("<tbl"))

        // Document order check: tbl must appear BEFORE the closing paragraph in XML
        val tblIndex = savedDocXml.indexOf("Cell 1,1")
        val closingIndex = savedDocXml.indexOf("Closing notes after table")
        assertTrue("Table must stay before the closing paragraph (document order preserved)", tblIndex < closingIndex)

        // 5. Re-parse saved document
        val reParsed = DocxEngine.parse(ByteArrayInputStream(savedDocxBytes), "saved.docx")
        assertEquals("Updated Executive Summary 2026", reParsed.paragraphs[0].fullText)
        assertEquals(1, reParsed.tables.size)
        assertEquals("Cell 1,1", reParsed.tables[0].rows[0].cells[0].text)
        assertEquals("Closing notes after table", reParsed.paragraphs[2].fullText)
    }

    @Test
    fun testEditingWordInParagraphDoesNotFlattenRuns() {
        // Paragraph with 3 runs: Bold "Hello ", Italic "World", Normal "!"
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:body>
        <w:p>
            <w:r><w:rPr><w:b/></w:rPr><w:t>Hello </w:t></w:r>
            <w:r><w:rPr><w:i/></w:rPr><w:t>World</w:t></w:r>
            <w:r><w:t>!</w:t></w:r>
        </w:p>
    </w:body>
</w:document>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        // 1. Parse
        val model = DocxEngine.parse(ByteArrayInputStream(baos.toByteArray()), "multi_run.docx")
        assertEquals(1, model.paragraphs.size)
        val p = model.paragraphs[0]
        assertEquals(3, p.runs.size)
        assertTrue(p.runs[0].isBold)
        assertTrue(p.runs[1].isItalic)
        assertFalse(p.runs[2].isBold || p.runs[2].isItalic)

        // 2. Edit ONLY Run 1 from "World" to "Universe"
        val updatedRuns = listOf(
            p.runs[0],
            p.runs[1].copy(text = "Universe"),
            p.runs[2]
        )
        val updatedModel = model.copy(paragraphs = listOf(p.copy(runs = updatedRuns)))

        // 3. Save
        val saveOut = ByteArrayOutputStream()
        DocxEngine.save(updatedModel, saveOut)

        // 4. Inspect XML
        val zipIn = ZipInputStream(ByteArrayInputStream(saveOut.toByteArray()))
        var entry = zipIn.nextEntry
        var savedXml = ""
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                savedXml = zipIn.bufferedReader().readText()
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // 5. Verify NO paragraph flattening occurred:
        // Run 0 must still have <w:b/> and "Hello "
        assertTrue(savedXml.contains("<w:b/>") || savedXml.contains("<w:b />"))
        assertTrue(savedXml.contains("Hello "))

        // Run 1 must still have <w:i/> and "Universe"
        assertTrue(savedXml.contains("<w:i/>") || savedXml.contains("<w:i />"))
        assertTrue(savedXml.contains("Universe"))

        // Run 2 must still have "!"
        assertTrue(savedXml.contains("!"))

        // 6. Re-parse
        val reParsed = DocxEngine.parse(ByteArrayInputStream(saveOut.toByteArray()), "saved.docx")
        assertEquals(3, reParsed.paragraphs[0].runs.size)
        assertEquals("Hello ", reParsed.paragraphs[0].runs[0].text)
        assertTrue("Run 0 must remain bold", reParsed.paragraphs[0].runs[0].isBold)

        assertEquals("Universe", reParsed.paragraphs[0].runs[1].text)
        assertTrue("Run 1 must remain italic", reParsed.paragraphs[0].runs[1].isItalic)

        assertEquals("!", reParsed.paragraphs[0].runs[2].text)
        assertFalse("Run 2 must remain non-bold non-italic", reParsed.paragraphs[0].runs[2].isBold)
    }

    @Test
    fun testNoMappingDriftWithTablesTextBoxesTrackedChangesAndFootnotes() {
        val complexXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:body>
        <w:p>
            <w:r><w:t>Heading Paragraph 1</w:t></w:r>
        </w:p>
        
        <w:tbl>
            <w:tr>
                <w:tc><w:p><w:r><w:t>Table Cell P1</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>Table Cell P2</w:t></w:r></w:p></w:tc>
            </w:tr>
        </w:tbl>

        <w:p>
            <w:r><w:t>Paragraph 2 with </w:t></w:r>
            <w:drawing>
                <w:txbxContent>
                    <w:p><w:r><w:t>Floating Textbox P</w:t></w:r></w:p>
                </w:txbxContent>
            </w:drawing>
            <w:ins w:id="1" w:author="Reviewer">
                <w:r><w:t>Tracked Insertion Text</w:t></w:r>
            </w:ins>
            <w:footnoteReference w:id="2"/>
        </w:p>

        <w:p>
            <w:r><w:t>Final Paragraph 3</w:t></w:r>
        </w:p>
    </w:body>
</w:document>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(complexXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        // 1. Parse
        val model = DocxEngine.parse(ByteArrayInputStream(baos.toByteArray()), "complex.docx")

        // Crucial verification: exactly 3 top-level body paragraphs (the 2 paragraphs inside tbl and 1 inside txbxContent DO NOT DRIFT the index)
        assertEquals(3, model.paragraphs.size)
        assertEquals("Heading Paragraph 1", model.paragraphs[0].fullText)
        assertTrue("Paragraph 2 must include tracked insertion text", model.paragraphs[1].fullText.contains("Paragraph 2 with Tracked Insertion Text"))
        assertEquals("Final Paragraph 3", model.paragraphs[2].fullText)

        // 2. Modify Paragraph 2: edit the text run
        val p2 = model.paragraphs[1]
        val updatedRuns = p2.runs.map {
            if (it.text.contains("Paragraph 2 with")) it.copy(text = "Paragraph 2 EDITED with ")
            else it
        }
        val updatedModel = model.copy(
            paragraphs = listOf(model.paragraphs[0], p2.copy(runs = updatedRuns), model.paragraphs[2])
        )

        // 3. Save
        val saveOut = ByteArrayOutputStream()
        DocxEngine.save(updatedModel, saveOut)

        // 4. Verify saved XML
        val zipIn = ZipInputStream(ByteArrayInputStream(saveOut.toByteArray()))
        var entry = zipIn.nextEntry
        var savedXml = ""
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                savedXml = zipIn.bufferedReader().readText()
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // Verify edited text in paragraph 2
        assertTrue(savedXml.contains("Paragraph 2 EDITED with"))

        // Verify floating textbox is still intact and in place
        assertTrue(savedXml.contains("<w:txbxContent>"))
        assertTrue(savedXml.contains("Floating Textbox P"))

        // Verify table is intact
        assertTrue(savedXml.contains("Table Cell P1"))

        // Verify footnote reference is intact
        assertTrue(savedXml.contains("footnoteReference"))

        // 5. Re-parse to confirm 100% zero drift
        val reParsed = DocxEngine.parse(ByteArrayInputStream(saveOut.toByteArray()), "saved.docx")
        assertEquals(3, reParsed.paragraphs.size)
        assertEquals("Heading Paragraph 1", reParsed.paragraphs[0].fullText)
        assertTrue(reParsed.paragraphs[1].fullText.contains("Paragraph 2 EDITED with Tracked Insertion Text"))
        assertEquals("Final Paragraph 3", reParsed.paragraphs[2].fullText)
    }
}
