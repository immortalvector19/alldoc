package com.alldoc.app.presentation.viewer.coordinator

import android.content.Context
import android.net.Uri
import com.alldoc.app.core.engine.ContentResolverStorageSink
import com.alldoc.app.core.engine.SafeDocumentWriter
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.core.engine.openxml.CsvEngine
import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.engine.pdf.PdfManipulator
import com.alldoc.app.core.engine.security.DocumentCryptoEngine
import com.alldoc.app.core.engine.text.TextEngine
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.presentation.viewer.ViewerUiState
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class DocumentPersistenceCoordinator(
    private val context: Context
) {

    suspend fun saveActiveDocument(
        current: ViewerUiState.Success,
        activeUri: Uri?,
        onSaveSnapshot: (suspend (File) -> Unit)? = null,
        onResult: (SaveResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val uri = activeUri ?: run {
            onResult(SaveResult.ValidationFailed("No active document URI"))
            return@withContext
        }

        val contentResolver = context.contentResolver
        val cacheDir = context.cacheDir
        val sink = ContentResolverStorageSink(contentResolver, uri)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { backupFile: File, stagedOut: OutputStream ->
                when (val content = current.content) {
                    is WordDocumentModel -> {
                        DocxEngine.save(content, stagedOut)
                    }
                    is SpreadsheetDocumentModel -> {
                        if (current.format == DocumentFormat.CSV) {
                            CsvEngine.save(content, stagedOut)
                        } else {
                            backupFile.inputStream().use { origIn ->
                                XlsxEngine.save(content, origIn, stagedOut)
                            }
                        }
                    }
                    is TextDocumentModel -> {
                        TextEngine.save(content, stagedOut)
                    }
                    is PdfDocumentModel -> {
                        PdfManipulator.exportWithAnnotations(
                            sourcePdfFile = backupFile,
                            annotations = content.annotations,
                            outputStream = stagedOut
                        )
                    }
                    else -> throw UnsupportedOperationException("Document format does not support editing/saving")
                }
            },
            validateStagedFile = { stagedFile: File ->
                validateSavedFile(stagedFile, current.format)
            },
            onSaveSnapshot = onSaveSnapshot
        )
        onResult(result)
    }

    suspend fun saveDocumentAsCopy(
        current: ViewerUiState.Success,
        activeUri: Uri?,
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
                when (val content = current.content) {
                    is WordDocumentModel -> {
                        DocxEngine.save(content, stagedOut)
                    }
                    is SpreadsheetDocumentModel -> {
                        if (current.format == DocumentFormat.CSV) {
                            CsvEngine.save(content, stagedOut)
                        } else {
                            val origStream = activeUri?.let { u ->
                                try { contentResolver.openInputStream(u) } catch (_: Exception) { null }
                            }
                            XlsxEngine.save(content, origStream, stagedOut)
                        }
                    }
                    is TextDocumentModel -> TextEngine.save(content, stagedOut)
                    is PdfDocumentModel -> {
                        val origFile = activeUri?.let { u ->
                            try { copyStreamToCache(u, "pdf_copy_", ".pdf") } catch (_: Exception) { null }
                        }
                        if (origFile != null) {
                            try {
                                PdfManipulator.exportWithAnnotations(
                                    sourcePdfFile = origFile,
                                    annotations = content.annotations,
                                    outputStream = stagedOut
                                )
                            } finally {
                                origFile.delete()
                            }
                        }
                    }
                    else -> throw UnsupportedOperationException("Document format is not supported for copy")
                }
            },
            validateStagedFile = { stagedFile: File ->
                validateSavedFile(stagedFile, current.format)
            }
        )
        onResult(result)
    }

    suspend fun lockDocumentToVault(
        current: ViewerUiState.Success,
        activeUri: Uri?,
        password: CharArray,
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
                val tempPlain = File.createTempFile("plain_vault_", ".tmp", cacheDir)
                try {
                    FileOutputStream(tempPlain).use { out ->
                        when (val content = current.content) {
                            is WordDocumentModel -> DocxEngine.save(content, out)
                            is SpreadsheetDocumentModel -> {
                                if (current.format == DocumentFormat.CSV) {
                                    CsvEngine.save(content, out)
                                } else {
                                    val origStream = activeUri?.let { u ->
                                        try { contentResolver.openInputStream(u) } catch (_: Exception) { null }
                                    }
                                    XlsxEngine.save(content, origStream, out)
                                }
                            }
                            is TextDocumentModel -> TextEngine.save(content, out)
                            is PdfDocumentModel -> {
                                val origFile = activeUri?.let { u ->
                                    try { copyStreamToCache(u, "pdf_lock_", ".pdf") } catch (_: Exception) { null }
                                }
                                if (origFile != null) {
                                    try {
                                        PdfManipulator.exportWithAnnotations(
                                            sourcePdfFile = origFile,
                                            annotations = content.annotations,
                                            outputStream = out
                                        )
                                    } finally {
                                        origFile.delete()
                                    }
                                }
                            }
                            else -> throw UnsupportedOperationException("Format is not supported for encryption")
                        }
                    }
                    val plainBytes = tempPlain.readBytes()
                    val encryptedBytes = DocumentCryptoEngine.encrypt(plainBytes, password)
                    stagedOut.write(encryptedBytes)
                } finally {
                    tempPlain.delete()
                }
            },
            validateStagedFile = { stagedFile: File ->
                stagedFile.exists() && stagedFile.length() > 0
            }
        )
        onResult(result)
    }

    private fun validateSavedFile(file: File, format: DocumentFormat): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            file.inputStream().use { stream ->
                when (format) {
                    DocumentFormat.DOCX -> {
                        val m = DocxEngine.parse(stream, file.name)
                        m.paragraphs.isNotEmpty()
                    }
                    DocumentFormat.XLSX -> {
                        val m = XlsxEngine.parse(stream, file.name)
                        m.workbook.sheets.isNotEmpty()
                    }
                    DocumentFormat.CSV -> {
                        val m = CsvEngine.parse(stream, file.name)
                        m.workbook.sheets.isNotEmpty()
                    }
                    DocumentFormat.TXT, DocumentFormat.RTF, DocumentFormat.MARKDOWN -> {
                        val m = TextEngine.parse(stream, file.name, format)
                        m.text.isNotEmpty() || file.length() == 0L
                    }
                    DocumentFormat.PDF -> {
                        PDDocument.load(file).use { doc ->
                            doc.numberOfPages > 0
                        }
                    }
                    else -> true
                }
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
