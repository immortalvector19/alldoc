package com.alldoc.app.core.engine.scanner

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
     * with an invisible text layer containing the OCR results for full searchability.
     */
    suspend fun createSearchablePdf(
        pages: List<Pair<Bitmap, OcrResult>>,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        for ((index, pair) in pages.withIndex()) {
            val (bitmap, ocr) = pair
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // 1. Draw page bitmap
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            // 2. Draw invisible text layer for OCR selection and searchability
            val invisiblePaint = Paint().apply {
                color = Color.TRANSPARENT
                textSize = 12f
            }

            for (block in ocr.blocks) {
                val box = block.boundingBox
                if (box != null) {
                    canvas.drawText(block.text, box.left.toFloat(), box.bottom.toFloat(), invisiblePaint)
                }
            }

            pdfDocument.finishPage(page)
        }

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
    }
}
