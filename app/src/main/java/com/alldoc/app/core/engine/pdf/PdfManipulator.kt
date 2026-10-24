package com.alldoc.app.core.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.DocumentAnnotations
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object PdfManipulator {

    /**
     * Reorders, rotates, or removes pages from an existing PDF file.
     */
    suspend fun reorderAndRotate(
        sourceFile: File,
        destFile: File,
        pageOrder: List<Int>, // 0-based original page indices
        rotationMap: Map<Int, Int> = emptyMap() // pageIndex -> rotation degrees (90, 180, 270)
    ) = withContext(Dispatchers.IO) {
        PDDocument.load(sourceFile).use { srcDoc ->
            PDDocument().use { outDoc ->
                for (originalPageIdx in pageOrder) {
                    if (originalPageIdx in 0 until srcDoc.numberOfPages) {
                        val page = srcDoc.getPage(originalPageIdx)
                        val extraRotation = rotationMap[originalPageIdx] ?: 0
                        if (extraRotation != 0) {
                            page.rotation = (page.rotation + extraRotation) % 360
                        }
                        outDoc.addPage(page)
                    }
                }
                outDoc.save(destFile)
            }
        }
    }

    /**
     * Merges multiple PDF files into a single destination file.
     */
    suspend fun merge(pdfFiles: List<File>, destFile: File) = withContext(Dispatchers.IO) {
        val merger = PDFMergerUtility()
        for (file in pdfFiles) {
            merger.addSource(file)
        }
        merger.destinationFileName = destFile.absolutePath
        merger.mergeDocuments(null)
    }

    /**
     * Splits a PDF from fromPage to toPage (1-based, inclusive).
     */
    suspend fun split(sourceFile: File, fromPage: Int, toPage: Int, destFile: File) = withContext(Dispatchers.IO) {
        PDDocument.load(sourceFile).use { srcDoc ->
            PDDocument().use { outDoc ->
                val start = (fromPage - 1).coerceAtLeast(0)
                val end = (toPage - 1).coerceAtMost(srcDoc.numberOfPages - 1)
                for (i in start..end) {
                    outDoc.addPage(srcDoc.getPage(i))
                }
                outDoc.save(destFile)
            }
        }
    }

    /**
     * Password-protects a PDF file using 128-bit or 256-bit AES encryption.
     */
    suspend fun protectWithPassword(sourceFile: File, destFile: File, password: String) = withContext(Dispatchers.IO) {
        PDDocument.load(sourceFile).use { doc ->
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(password, password, ap).apply {
                encryptionKeyLength = 256
            }
            doc.protect(spp)
            doc.save(destFile)
        }
    }

    /**
     * Lossless vector annotation export: Appends vector annotations directly onto the
     * original PDF content streams using PdfBox-Android without rasterizing pages.
     * Text streams, fonts, links, and forms remain 100% vector-sharp and searchable.
     */
    suspend fun exportWithAnnotations(
        sourcePdfFile: File,
        annotations: DocumentAnnotations,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        PDDocument.load(sourcePdfFile).use { doc ->
            applyAnnotationsToDoc(doc, annotations)
            doc.save(outputStream)
        }
    }

    suspend fun exportWithAnnotations(
        sourcePdfBytes: ByteArray,
        annotations: DocumentAnnotations,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        PDDocument.load(ByteArrayInputStream(sourcePdfBytes)).use { doc ->
            applyAnnotationsToDoc(doc, annotations)
            doc.save(outputStream)
        }
    }

    private fun applyAnnotationsToDoc(doc: PDDocument, annotations: DocumentAnnotations) {
        val highlightGState = PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = 0.35f
            blendMode = BlendMode.MULTIPLY
        }
        val normalGState = PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = 1.0f
            blendMode = BlendMode.NORMAL
        }

        for (pageIndex in 0 until doc.numberOfPages) {
            val pageAnnotations = annotations.forPage(pageIndex)
            if (pageAnnotations.isEmpty()) continue

            val page = doc.getPage(pageIndex)
            val cropBox = page.cropBox ?: page.mediaBox
            val rotation = page.rotation

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (annotation in pageAnnotations) {
                    when (annotation) {
                        is Annotation.InkStroke -> {
                            val r = ((annotation.colorArgb.toInt() shr 16) and 0xFF) / 255f
                            val g = ((annotation.colorArgb.toInt() shr 8) and 0xFF) / 255f
                            val b = (annotation.colorArgb.toInt() and 0xFF) / 255f
                            cs.setStrokingColor(r, g, b)
                            cs.setLineWidth(annotation.strokeWidth)

                            if (annotation.points.size > 1) {
                                val first = transformPoint(annotation.points.first().x, annotation.points.first().y, cropBox, rotation)
                                cs.moveTo(first.first, first.second)
                                for (p in annotation.points.drop(1)) {
                                    val pt = transformPoint(p.x, p.y, cropBox, rotation)
                                    cs.lineTo(pt.first, pt.second)
                                }
                                cs.stroke()
                            }
                        }

                        is Annotation.Highlight -> {
                            val r = ((annotation.colorArgb.toInt() shr 16) and 0xFF) / 255f
                            val g = ((annotation.colorArgb.toInt() shr 8) and 0xFF) / 255f
                            val b = (annotation.colorArgb.toInt() and 0xFF) / 255f

                            // Apply Multiply blend mode & 35% alpha so underlying text stays 100% visible
                            cs.setGraphicsStateParameters(highlightGState)
                            cs.setNonStrokingColor(r, g, b)

                            val p1 = transformPoint(annotation.boundsLeft, annotation.boundsBottom, cropBox, rotation)
                            val p2 = transformPoint(annotation.boundsRight, annotation.boundsTop, cropBox, rotation)
                            val x = minOf(p1.first, p2.first)
                            val y = minOf(p1.second, p2.second)
                            val w = Math.abs(p2.first - p1.first)
                            val h = Math.abs(p2.second - p1.second)

                            cs.addRect(x, y, w, h)
                            cs.fill()

                            // Restore normal graphics state
                            cs.setGraphicsStateParameters(normalGState)
                        }

                        is Annotation.TextNote -> {
                            val r = ((annotation.colorArgb.toInt() shr 16) and 0xFF) / 255f
                            val g = ((annotation.colorArgb.toInt() shr 8) and 0xFF) / 255f
                            val b = (annotation.colorArgb.toInt() and 0xFF) / 255f
                            try {
                                val font = getFontForText(doc, annotation.text)
                                val pt = transformPoint(annotation.x, annotation.y, cropBox, rotation)

                                cs.beginText()
                                cs.setFont(font, annotation.fontSizeSp)
                                cs.setNonStrokingColor(r, g, b)
                                cs.newLineAtOffset(pt.first, pt.second)
                                cs.showText(annotation.text)
                                cs.endText()
                            } catch (_: Exception) {
                                // If the font lacks specific glyphs, attempt ASCII fallback
                                try {
                                    cs.beginText()
                                    cs.setFont(PDType1Font.HELVETICA_BOLD, annotation.fontSizeSp)
                                    cs.setNonStrokingColor(r, g, b)
                                    val pt = transformPoint(annotation.x, annotation.y, cropBox, rotation)
                                    cs.newLineAtOffset(pt.first, pt.second)
                                    val ascii = annotation.text.filter { it.code in 32..126 }
                                    cs.showText(ascii)
                                    cs.endText()
                                } catch (_: Exception) {}
                            }
                        }

                        is Annotation.SignatureStamp -> {
                            val r = ((annotation.colorArgb.toInt() shr 16) and 0xFF) / 255f
                            val g = ((annotation.colorArgb.toInt() shr 8) and 0xFF) / 255f
                            val b = (annotation.colorArgb.toInt() and 0xFF) / 255f
                            cs.setStrokingColor(r, g, b)
                            cs.setLineWidth(3f)

                            for (stroke in annotation.paths) {
                                if (stroke.size > 1) {
                                    val first = transformPoint(stroke.first().x, stroke.first().y, cropBox, rotation)
                                    cs.moveTo(first.first, first.second)
                                    for (p in stroke.drop(1)) {
                                        val pt = transformPoint(p.x, p.y, cropBox, rotation)
                                        cs.lineTo(pt.first, pt.second)
                                    }
                                    cs.stroke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts viewer display coordinates (where origin is top-left of the upright displayed page)
     * to PDF user space coordinates (where origin is lowerLeft of CropBox/MediaBox).
     * Correctly handles CropBox offsets and page rotations (0, 90, 180, 270 degrees).
     */
    fun transformPoint(viewX: Float, viewY: Float, cropBox: PDRectangle, rotation: Int): Pair<Float, Float> {
        val normRot = ((rotation % 360) + 360) % 360
        return when (normRot) {
            90 -> {
                val pdfX = cropBox.lowerLeftX + viewY
                val pdfY = cropBox.lowerLeftY + viewX
                Pair(pdfX, pdfY)
            }
            180 -> {
                val pdfX = cropBox.lowerLeftX + (cropBox.width - viewX)
                val pdfY = cropBox.lowerLeftY + viewY
                Pair(pdfX, pdfY)
            }
            270 -> {
                val pdfX = cropBox.lowerLeftX + (cropBox.width - viewY)
                val pdfY = cropBox.lowerLeftY + (cropBox.height - viewX)
                Pair(pdfX, pdfY)
            }
            else -> { // 0 degrees
                val pdfX = cropBox.lowerLeftX + viewX
                val pdfY = cropBox.lowerLeftY + (cropBox.height - viewY)
                Pair(pdfX, pdfY)
            }
        }
    }

    private fun getFontForText(doc: PDDocument, text: String): PDFont {
        val hasCjk = text.any { it.code in 0x4E00..0x9FFF }
        val hasArabic = text.any { it.code in 0x0600..0x06FF }

        if (hasCjk) {
            val cjkFile = listOf(
                File("/system/fonts/NotoSansCJK-Regular.ttc"),
                File("/system/fonts/DroidSansFallback.ttf"),
                File("C:/Windows/Fonts/msyh.ttc"),
                File("C:/Windows/Fonts/simsun.ttc")
            ).firstOrNull { it.exists() }
            if (cjkFile != null) {
                try {
                    return PDType0Font.load(doc, cjkFile)
                } catch (_: Exception) {}
            }
        }

        if (hasArabic) {
            val arabicFile = listOf(
                File("/system/fonts/NotoNaskhArabic-Regular.ttf"),
                File("C:/Windows/Fonts/arial.ttf")
            ).firstOrNull { it.exists() }
            if (arabicFile != null) {
                try {
                    return PDType0Font.load(doc, arabicFile)
                } catch (_: Exception) {}
            }
        }

        // For Latin Extended, Yoruba ("Ẹ̀kọ́ ṣe àṣeyọrí"), and general Unicode:
        val resourceStream = PdfManipulator::class.java.classLoader?.getResourceAsStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            ?: PdfManipulator::class.java.getResourceAsStream("/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            ?: PdfManipulator::class.java.classLoader?.getResourceAsStream("assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")

        if (resourceStream != null) {
            try {
                return PDType0Font.load(doc, resourceStream)
            } catch (_: Exception) {}
        }

        val sysFallback = listOf(
            File("/system/fonts/Roboto-Regular.ttf"),
            File("C:/Windows/Fonts/arial.ttf")
        ).firstOrNull { it.exists() }

        if (sysFallback != null) {
            try {
                return PDType0Font.load(doc, sysFallback)
            } catch (_: Exception) {}
        }

        return PDType1Font.HELVETICA_BOLD
    }
}
