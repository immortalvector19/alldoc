package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.text.TextEngine
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.TextDocumentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TextEngineTest {

    @Test
    fun testParseAndSavePlainText() {
        val sampleText = "Hello AllDoc!\nThis is a clean, local-first document viewer."
        val inStream = ByteArrayInputStream(sampleText.toByteArray(Charsets.UTF_8))

        val model = TextEngine.parse(inStream, "content://test/sample.txt", DocumentFormat.TXT)
        assertEquals(DocumentFormat.TXT, model.format)
        assertEquals(sampleText, model.text)

        val outStream = ByteArrayOutputStream()
        val modifiedModel = model.copy(text = sampleText + "\nNew line added.")
        TextEngine.save(modifiedModel, outStream)

        val savedText = String(outStream.toByteArray(), Charsets.UTF_8)
        assertTrue(savedText.contains("New line added."))
    }

    @Test
    fun testParseZeroByteEmptyText() {
        val inStream = ByteArrayInputStream(ByteArray(0))
        val model = TextEngine.parse(inStream, "content://test/empty.txt", DocumentFormat.TXT)

        assertEquals("", model.text)
        assertEquals(DocumentFormat.TXT, model.format)
    }

    @Test
    fun testStripRtfFormatting() {
        val rtfSample = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Courier;}}\\f0\\fs24 AllDoc RTF Parser\\par Next line}"
        val inStream = ByteArrayInputStream(rtfSample.toByteArray(Charsets.UTF_8))

        val model = TextEngine.parse(inStream, "content://test/sample.rtf", DocumentFormat.RTF)
        assertEquals(DocumentFormat.RTF, model.format)
        assertTrue(model.text.contains("AllDoc RTF Parser"))
        assertTrue(!model.text.contains("\\rtf1"))
    }
}
