package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTable
import com.alldoc.app.core.model.WordTableCell
import com.alldoc.app.core.model.WordTableRow
import com.alldoc.app.core.model.WordTextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxEngineTest {

    @Test
    fun testDocxRoundtripSaveAndPreserve() {
        val originalXml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:r>
                            <w:rPr><w:b/></w:rPr>
                            <w:t>Hello AllDoc</w:t>
                        </w:r>
                    </w:p>
                </w:body>
            </w:document>""".trimIndent()

        // Create sample docx in memory with an extra file to ensure preservation
        val outZip = ByteArrayOutputStream()
        ZipOutputStream(outZip).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(originalXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("custom/metadata.xml"))
            zos.write("<meta>unaltered-data</meta>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val docxBytes = outZip.toByteArray()

        // Parse
        val model = DocxEngine.parse(ByteArrayInputStream(docxBytes), "content://test/doc.docx")
        assertEquals(1, model.paragraphs.size)
        assertEquals("Hello AllDoc", model.paragraphs[0].fullText)
        assertTrue(model.paragraphs[0].runs[0].isBold)
        assertTrue(model.uneditedXmlParts.containsKey("custom/metadata.xml"))

        // Modify model: add a new paragraph
        val updatedModel = model.copy(
            paragraphs = model.paragraphs + WordParagraph(
                runs = listOf(WordTextRun(text = "Second paragraph", isItalic = true))
            )
        )

        // Save back
        val savedOut = ByteArrayOutputStream()
        DocxEngine.save(updatedModel, savedOut)

        // Verify saved zip contains both paragraphs and untouched custom/metadata.xml
        val reloaded = DocxEngine.parse(ByteArrayInputStream(savedOut.toByteArray()), "content://test/doc.docx")
        assertEquals(2, reloaded.paragraphs.size)
        assertEquals("Hello AllDoc", reloaded.paragraphs[0].fullText)
        assertEquals("Second paragraph", reloaded.paragraphs[1].fullText)
        assertTrue(reloaded.paragraphs[1].runs[0].isItalic)
        assertTrue(reloaded.uneditedXmlParts.containsKey("custom/metadata.xml"))
    }
}
