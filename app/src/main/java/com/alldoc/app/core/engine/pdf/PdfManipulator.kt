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
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                encryptionKeyLength = 128
            }
            doc.protect(spp)
            doc.save(destFile)
        }
    }

    /**
     * Flattens vector annotations (ink, highlights, notes, signatures) onto the pages
     * of a PDF and exports the flattened PDF.
     */
    suspend fun exportWithAnnotations(
        rendererEngine: PdfRendererEngine,
        annotations: DocumentAnnotations,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        for (pageIndex in 0 until rendererEngine.pageCount) {
            val (baseWidth, baseHeight) = rendererEngine.getPageDimensions(pageIndex)
            val w = if (baseWidth > 0) baseWidth else 595
            val h = if (baseHeight > 0) baseHeight else 842

            val pageInfo = PdfDocument.PageInfo.Builder(w, h, pageIndex + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // 1. Render the base page bitmap
            val bitmap = rendererEngine.renderPage(pageIndex, scaleFactor = 1.0f)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }

            // 2. Draw Vector Annotations on top of the page
            val pageAnnotations = annotations.forPage(pageIndex)
            val paint = Paint().apply { isAntiAlias = true }

            for (annotation in pageAnnotations) {
                when (annotation) {
                    is Annotation.InkStroke -> {
                        paint.color = annotation.colorArgb.toInt()
                        paint.strokeWidth = annotation.strokeWidth
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeJoin = Paint.Join.ROUND

                        if (annotation.points.size > 1) {
                            val path = Path()
                            val first = annotation.points.first()
                            path.moveTo(first.x, first.y)
                            for (p in annotation.points.drop(1)) {
                                path.lineTo(p.x, p.y)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }

                    is Annotation.Highlight -> {
                        paint.color = annotation.colorArgb.toInt()
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(
                            annotation.boundsLeft,
                            annotation.boundsTop,
                            annotation.boundsRight,
                            annotation.boundsBottom,
                            paint
                        )
                    }

                    is Annotation.TextNote -> {
                        // Draw background box
                        annotation.backgroundArgb?.let { bg ->
                            paint.color = bg.toInt()
                            paint.style = Paint.Style.FILL
                            canvas.drawRect(
                                annotation.x - 4f,
                                annotation.y - annotation.fontSizeSp - 4f,
                                annotation.x + (annotation.text.length * annotation.fontSizeSp * 0.6f) + 8f,
                                annotation.y + 8f,
                                paint
                            )
                        }
                        // Draw text
                        paint.color = annotation.colorArgb.toInt()
                        paint.style = Paint.Style.FILL
                        paint.textSize = annotation.fontSizeSp
                        canvas.drawText(annotation.text, annotation.x, annotation.y, paint)
                    }

                    is Annotation.SignatureStamp -> {
                        paint.color = annotation.colorArgb.toInt()
                        paint.strokeWidth = 3f
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeJoin = Paint.Join.ROUND

                        for (stroke in annotation.paths) {
                            if (stroke.size > 1) {
                                val path = Path()
                                path.moveTo(stroke.first().x, stroke.first().y)
                                for (p in stroke.drop(1)) {
                                    path.lineTo(p.x, p.y)
                                }
                                canvas.drawPath(path, paint)
                            }
                        }
                    }
                }
            }

            pdfDocument.finishPage(page)
        }

        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
    }
}
