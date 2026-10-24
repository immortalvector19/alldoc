package com.alldoc.app.core.engine.scanner

import android.graphics.Rect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchablePdfAndOcrPersistenceTest {

    @Test
    fun testSearchablePdfCreatedWithA4DimensionsAndMode3() {
        runBlocking {
            val tempPdf = File.createTempFile("searchable_test_", ".pdf")
            try {
                val ocr = OcrResult(
                    fullText = "Invoice Number 12345 Due Date 2026-11-01",
                    blocks = listOf(
                        OcrTextBlock(
                            text = "Invoice Number 12345",
                            boundingBox = Rect(50, 100, 300, 150),
                            lines = listOf("Invoice Number 12345")
                        ),
                        OcrTextBlock(
                            text = "Due Date 2026-11-01",
                            boundingBox = Rect(50, 200, 300, 250),
                            lines = listOf("Due Date 2026-11-01")
                        )
                    )
                )

                DocumentScannerManager.createSearchablePdf(
                    pages = listOf(Pair(null, ocr)),
                    outputFile = tempPdf
                )

                assertTrue(tempPdf.exists())
                assertTrue(tempPdf.length() > 0)

                PDDocument.load(tempPdf).use { doc ->
                    assertEquals(1, doc.numberOfPages)
                    val page = doc.getPage(0)
                    assertEquals(PDRectangle.A4.width, page.mediaBox.width, 1.0f)
                    assertEquals(PDRectangle.A4.height, page.mediaBox.height, 1.0f)

                    val text = PDFTextStripper().getText(doc)
                    assertTrue("Expected extracted OCR text in PDF text layer", text.contains("Invoice Number 12345"))
                    assertTrue("Expected extracted OCR text in PDF text layer", text.contains("Due Date 2026-11-01"))
                }
            } finally {
                tempPdf.delete()
            }
        }
    }
}
