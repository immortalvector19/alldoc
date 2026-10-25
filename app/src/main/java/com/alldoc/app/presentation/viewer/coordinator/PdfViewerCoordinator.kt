package com.alldoc.app.presentation.viewer.coordinator

import android.content.Context
import android.net.Uri
import com.alldoc.app.core.engine.ContentResolverStorageSink
import com.alldoc.app.core.engine.SafeDocumentWriter
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.core.engine.pdf.PdfManipulator
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class PdfViewerCoordinator(
    private val context: Context
) {

    suspend fun splitPdfToUri(
        sourcePdfUri: Uri,
        fromPage: Int,
        toPage: Int,
        destinationUri: Uri,
        onResult: (SaveResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val cacheDir = context.cacheDir
        val sink = ContentResolverStorageSink(contentResolver, destinationUri)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _: File, stagedOut: OutputStream ->
                val srcFile = copyStreamToCache(sourcePdfUri, "pdf_split_src_", ".pdf")
                val tempSplit = File.createTempFile("pdf_split_out_", ".pdf", cacheDir)
                try {
                    PdfManipulator.split(srcFile, fromPage, toPage, tempSplit)
                    tempSplit.inputStream().use { it.copyTo(stagedOut) }
                } finally {
                    srcFile.delete()
                    tempSplit.delete()
                }
            },
            validateStagedFile = { stagedFile: File ->
                validatePdfFile(stagedFile)
            }
        )
        onResult(result)
    }

    suspend fun protectPdfToUri(
        sourcePdfUri: Uri,
        password: String,
        destinationUri: Uri,
        onResult: (SaveResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val cacheDir = context.cacheDir
        val sink = ContentResolverStorageSink(contentResolver, destinationUri)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _: File, stagedOut: OutputStream ->
                val srcFile = copyStreamToCache(sourcePdfUri, "pdf_protect_src_", ".pdf")
                val tempProtected = File.createTempFile("pdf_protect_out_", ".pdf", cacheDir)
                try {
                    PdfManipulator.protectWithPassword(srcFile, tempProtected, password)
                    tempProtected.inputStream().use { it.copyTo(stagedOut) }
                } finally {
                    srcFile.delete()
                    tempProtected.delete()
                }
            },
            validateStagedFile = { stagedFile: File ->
                stagedFile.exists() && stagedFile.length() > 0
            }
        )
        onResult(result)
    }

    suspend fun mergePdfFiles(
        firstPdfUri: Uri,
        secondPdfUri: Uri,
        destFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val firstFile = copyStreamToCache(firstPdfUri, "pdf_merge_1_", ".pdf")
        val secondFile = copyStreamToCache(secondPdfUri, "pdf_merge_2_", ".pdf")
        try {
            PdfManipulator.merge(listOf(firstFile, secondFile), destFile)
            destFile.exists() && destFile.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            firstFile.delete()
            secondFile.delete()
        }
    }

    private fun validatePdfFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            PDDocument.load(file).use { doc ->
                doc.numberOfPages > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyStreamToCache(uri: Uri, prefix: String, suffix: String): File {
        val temp = File.createTempFile(prefix, suffix, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open stream for $uri")
        return temp
    }
}
