package com.alldoc.app.core.engine.scanner

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object DocumentScannerManager {

    fun createScannerOptions(pageLimit: Int = 100): GmsDocumentScannerOptions {
        return GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }

    suspend fun getStartScanIntent(activity: Activity, options: GmsDocumentScannerOptions = createScannerOptions()): IntentSender {
        val client = GmsDocumentScanning.getClient(options)
        return client.getStartScanIntent(activity).await()
    }

    fun parseResult(intentData: android.content.Intent?): GmsDocumentScanningResult? {
        return GmsDocumentScanningResult.fromActivityResultIntent(intentData)
    }

    /**
     * Converts a list of scanned page bitmaps into a multi-page PDF document
     * with standard A4 point dimensions and an invisible text layer using PDF Text
     * Rendering Mode 3 (Neither fill nor stroke text) for full cross-platform OCR searchability.
     */
    suspend fun createSearchablePdf(
        pages: List<Pair<Bitmap?, OcrResult>>,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val doc = PDDocument()
        try {
            val a4Width = PDRectangle.A4.width // 595.2756 pt
            val a4Height = PDRectangle.A4.height // 841.8898 pt

            for ((bitmap, ocr) in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                val bmpWidth = if (bitmap != null && bitmap.width > 0) bitmap.width.toFloat() else a4Width
                val bmpHeight = if (bitmap != null && bitmap.height > 0) bitmap.height.toFloat() else a4Height

                // Compress image to JPEG to optimize storage
                val pdImage = if (bitmap != null) {
                    try {
                        JPEGFactory.createFromImage(doc, bitmap, 0.85f)
                    } catch (_: Throwable) {
                        try {
                            LosslessFactory.createFromImage(doc, bitmap)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else null

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, true).use { cs ->
                    // 1. Draw page background image scaled to full A4
                    if (pdImage != null) {
                        cs.drawImage(pdImage, 0f, 0f, a4Width, a4Height)
                    }

                    // 2. Set Text Rendering Mode 3 (Neither fill nor stroke text = invisible selectable layer)
                    cs.appendRawCommands("3 Tr\n")

                    // 3. Render invisible text layer aligned with scaled A4 coordinates
                    val scaleX = a4Width / bmpWidth
                    val scaleY = a4Height / bmpHeight

                    for (block in ocr.blocks) {
                        val box = block.boundingBox
                        if (box != null && block.text.isNotBlank()) {
                            val pdfX = box.left * scaleX
                            // In PDF, Y=0 is at bottom; in Android Bitmap, Y=0 is at top
                            val pdfY = a4Height - (box.bottom * scaleY)
                            val fontSize = (box.height() * scaleY * 0.8f).coerceIn(8f, 36f)

                            try {
                                cs.beginText()
                                cs.setFont(PDType1Font.HELVETICA, fontSize)
                                cs.newLineAtOffset(pdfX, pdfY)
                                val safeText = block.text.replace(Regex("[\\r\\n]+"), " ")
                                    .filter { it.code in 32..126 }
                                if (safeText.isNotBlank()) {
                                    cs.showText(safeText)
                                }
                                cs.endText()
                            } catch (_: Exception) {
                                // Ignore unencodable characters
                            }
                        }
                    }
                }
            }
            doc.save(outputFile)
        } finally {
            doc.close()
        }
    }
}
