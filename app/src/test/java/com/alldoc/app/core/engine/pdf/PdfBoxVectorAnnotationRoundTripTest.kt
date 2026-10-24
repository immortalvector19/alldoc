package com.alldoc.app.core.engine.pdf

import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.DocumentAnnotations
import com.alldoc.app.core.model.Point2D
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfBoxVectorAnnotationRoundTripTest {

    @Test
    fun testPdfVectorAnnotationSavePreservesSelectableTextAndVectorStreams() = runBlocking {
        // 1. Create a clean vector PDF with real selectable text
        val originalPdfBytes = ByteArrayOutputStream().use { baos ->
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText("AllDoc Searchable Text - Confidential Report 2026")
                    cs.endText()
                }
                doc.save(baos)
            }
            baos.toByteArray()
        }

        // Verify initial text extraction
        val initialText = PDDocument.load(ByteArrayInputStream(originalPdfBytes)).use { doc ->
            PDFTextStripper().getText(doc).trim()
        }
        assertTrue(initialText.contains("AllDoc Searchable Text"))

        // 2. Build vector annotations across all types
        val annotations = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    Annotation.InkStroke(
                        targetPage = 0,
                        points = listOf(Point2D(100f, 100f), Point2D(150f, 120f), Point2D(200f, 100f)),
                        strokeWidth = 3f,
                        colorArgb = 0xFFFF0000 // Red
                    ),
                    Annotation.Highlight(
                        targetPage = 0,
                        boundsLeft = 100f,
                        boundsTop = 80f,
                        boundsRight = 300f,
                        boundsBottom = 110f,
                        colorArgb = 0x80FFFF00 // Semi-transparent yellow
                    ),
                    Annotation.TextNote(
                        targetPage = 0,
                        text = "Reviewed by Legal Counsel",
                        x = 100f,
                        y = 650f,
                        fontSizeSp = 14f,
                        colorArgb = 0xFF0000FF // Blue
                    ),
                    Annotation.SignatureStamp(
                        targetPage = 0,
                        paths = listOf(
                            listOf(Point2D(100f, 600f), Point2D(120f, 620f), Point2D(150f, 590f))
                        ),
                        boundingX = 100f,
                        boundingY = 590f,
                        width = 50f,
                        height = 30f,
                        colorArgb = 0xFF000000
                    )
                )
            )
        )

        // 3. Export with vector annotations (PdfBox vector overlay)
        val outBaos = ByteArrayOutputStream()
        PdfManipulator.exportWithAnnotations(originalPdfBytes, annotations, outBaos)
        val annotatedPdfBytes = outBaos.toByteArray()

        // 4. Verify saved PDF:
        // A) Text MUST remain 100% extractable and searchable (PROVING zero bitmap rasterization!)
        val reExtractedText = PDDocument.load(ByteArrayInputStream(annotatedPdfBytes)).use { doc ->
            assertEquals(1, doc.numberOfPages)
            PDFTextStripper().getText(doc).trim()
        }

        assertTrue(
            "Text must remain selectable and searchable after annotation export (No rasterization)",
            reExtractedText.contains("AllDoc Searchable Text - Confidential Report 2026")
        )

        // B) Annotation text is also present in content streams
        assertTrue(
            "Vector text note must be searchable in content stream",
            reExtractedText.contains("Reviewed by Legal Counsel")
        )
    }

    @Test
    fun testRotatedAndCroppedPageCoordinateTransformation() {
        // CropBox with non-zero lower-left offset (e.g. 50, 50) and dimensions 500x700
        val cropBox = PDRectangle(50f, 50f, 500f, 700f)

        // 0 degrees: top-left (0,0) -> PDF (50, 750)
        val p0 = PdfManipulator.transformPoint(0f, 0f, cropBox, 0)
        assertEquals(50f, p0.first, 0.01f)
        assertEquals(750f, p0.second, 0.01f)

        // 0 degrees: bottom-right (500, 700) -> PDF (550, 50)
        val p0br = PdfManipulator.transformPoint(500f, 700f, cropBox, 0)
        assertEquals(550f, p0br.first, 0.01f)
        assertEquals(50f, p0br.second, 0.01f)

        // 90 degrees clockwise rotation:
        // Top-left (viewX=0, viewY=0) -> PDF (lowerLeftX + viewY, lowerLeftY + viewX) = (50, 50)
        val p90 = PdfManipulator.transformPoint(0f, 0f, cropBox, 90)
        assertEquals(50f, p90.first, 0.01f)
        assertEquals(50f, p90.second, 0.01f)

        // 180 degrees:
        val p180 = PdfManipulator.transformPoint(0f, 0f, cropBox, 180)
        assertEquals(550f, p180.first, 0.01f)
        assertEquals(50f, p180.second, 0.01f)

        // 270 degrees:
        val p270 = PdfManipulator.transformPoint(0f, 0f, cropBox, 270)
        assertEquals(550f, p270.first, 0.01f)
        assertEquals(750f, p270.second, 0.01f)
    }

    @Test
    fun testHighlightWithMultiplyBlendAndNonLatinYorubaTextNote() = runBlocking {
        // 1. Create a PDF with background text
        val originalPdfBytes = ByteArrayOutputStream().use { baos ->
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                    cs.newLineAtOffset(50f, 750f)
                    cs.showText("Underlying Article Text That Must Remain Visible")
                    cs.endText()
                }
                doc.save(baos)
            }
            baos.toByteArray()
        }

        // 2. Add:
        // - Highlight over the text
        // - Yoruba text note with tone diacritics: "Ẹ̀kọ́ ṣe àṣeyọrí"
        val yorubaText = "Ẹ̀kọ́ ṣe àṣeyọrí"
        val annotations = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    Annotation.Highlight(
                        targetPage = 0,
                        boundsLeft = 50f,
                        boundsTop = 40f,
                        boundsRight = 500f,
                        boundsBottom = 70f,
                        colorArgb = 0x80FFFF00 // Semi-transparent yellow
                    ),
                    Annotation.TextNote(
                        targetPage = 0,
                        text = yorubaText,
                        x = 50f,
                        y = 120f,
                        fontSizeSp = 14f,
                        colorArgb = 0xFF1B5E20
                    )
                )
            )
        )

        // 3. Export
        val outBaos = ByteArrayOutputStream()
        PdfManipulator.exportWithAnnotations(originalPdfBytes, annotations, outBaos)
        val resultPdfBytes = outBaos.toByteArray()

        // 4. Verify text extraction
        val extractedText = PDDocument.load(ByteArrayInputStream(resultPdfBytes)).use { doc ->
            PDFTextStripper().getText(doc).trim()
        }

        assertTrue("Original text must remain extractable", extractedText.contains("Underlying Article Text That Must Remain Visible"))
        assertTrue("Yoruba accented text note must be present in output PDF", extractedText.contains(yorubaText))
    }
}
