package com.alldoc.app.presentation.viewer.coordinator

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.alldoc.app.core.data.repository.DocumentRepository
import com.alldoc.app.core.engine.FileSniffer
import com.alldoc.app.core.engine.legacy.Ole2Engine
import com.alldoc.app.core.engine.openxml.CsvEngine
import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.engine.openxml.PptxEngine
import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.engine.pdf.PdfRendererEngine
import com.alldoc.app.core.engine.security.DocumentCryptoEngine
import com.alldoc.app.core.engine.text.TextEngine
import com.alldoc.app.core.model.DocumentContent
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.ImageDocumentModel
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.presentation.viewer.ViewerUiState
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class DocumentLoadCoordinator(
    private val context: Context,
    private val repository: DocumentRepository
) {

    suspend fun loadDocument(uri: Uri): ViewerUiState = withContext(Dispatchers.IO) {
        try {
            val metadata = repository.recordDocumentAccess(uri)
            val contentResolver = context.contentResolver

            val isCached = uri.toString().contains("cached_docs") ||
                (uri.scheme == ContentResolver.SCHEME_FILE && uri.path?.contains("cached_docs") == true)

            // Sniff real format by inspecting file stream header
            val realFormat = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    FileSniffer.sniffFormat(stream, metadata.name, metadata.format.mimeType)
                } ?: metadata.format
            } catch (_: Throwable) {
                metadata.format
            }

            var pdfRenderer: PdfRendererEngine? = null

            val content: DocumentContent = when (realFormat) {
                DocumentFormat.PDF -> {
                    val cachedPdfFile = copyStreamToCache(uri, "pdf_view", ".pdf")
                    val encrypted = isPdfEncrypted(cachedPdfFile)
                    if (encrypted) {
                        return@withContext ViewerUiState.PasswordRequired(
                            documentName = metadata.name,
                            uri = uri
                        )
                    }

                    val pfd = ParcelFileDescriptor.open(cachedPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val engine = PdfRendererEngine(pfd)
                    pdfRenderer = engine
                    PdfDocumentModel(
                        uriString = uri.toString(),
                        pageCount = engine.pageCount
                    )
                }

                DocumentFormat.DOCX -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        DocxEngine.parse(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open Word document stream")
                }

                DocumentFormat.DOC -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        Ole2Engine.parseDoc(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open legacy Word stream")
                }

                DocumentFormat.XLSX -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        XlsxEngine.parse(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open Excel spreadsheet stream")
                }

                DocumentFormat.XLS -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        Ole2Engine.parseXls(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open legacy Excel stream")
                }

                DocumentFormat.CSV -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        CsvEngine.parse(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open CSV stream")
                }

                DocumentFormat.PPTX -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        PptxEngine.parse(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open PowerPoint presentation stream")
                }

                DocumentFormat.PPT -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        Ole2Engine.parsePpt(stream, uri.toString())
                    } ?: throw IllegalStateException("Cannot open legacy PowerPoint stream")
                }

                DocumentFormat.TXT, DocumentFormat.RTF, DocumentFormat.MARKDOWN -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        TextEngine.parse(stream, uri.toString(), realFormat)
                    } ?: throw IllegalStateException("Cannot open text document stream")
                }

                DocumentFormat.IMAGE_JPEG, DocumentFormat.IMAGE_PNG, DocumentFormat.IMAGE_WEBP -> {
                    ImageDocumentModel(uriString = uri.toString())
                }

                DocumentFormat.VAULT -> {
                    return@withContext ViewerUiState.PasswordRequired(
                        documentName = metadata.name,
                        uri = uri,
                        errorMessage = null
                    )
                }

                DocumentFormat.UNKNOWN -> {
                    return@withContext ViewerUiState.UnsupportedFormat(
                        documentName = metadata.name,
                        uri = uri,
                        mimeType = metadata.format.mimeType
                    )
                }
            }

            ViewerUiState.Success(
                documentName = metadata.name,
                format = realFormat,
                content = content,
                pdfRendererEngine = pdfRenderer,
                originalUri = uri,
                isCachedCopy = isCached
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            when {
                t is OutOfMemoryError -> {
                    ViewerUiState.Error("Document is too large to open on this device (Out of Memory).")
                }
                t is SecurityException -> {
                    ViewerUiState.Error(
                        "Access permission expired or was revoked. Please re-open the document from the originating app.",
                        isTemporaryPermissionRevoked = true
                    )
                }
                else -> {
                    ViewerUiState.Error(t.localizedMessage ?: "Failed to open document")
                }
            }
        }
    }

    suspend fun unlockPdf(uri: Uri, password: String): ViewerUiState = withContext(Dispatchers.IO) {
        try {
            val metadata = repository.recordDocumentAccess(uri)
            val cachedPdfFile = copyStreamToCache(uri, "pdf_locked", ".pdf")
            val unlockedFile = File(
                context.cacheDir,
                "pdf_unlocked_${System.currentTimeMillis()}.pdf"
            )

            PDDocument.load(cachedPdfFile, password).use { doc ->
                if (doc.isEncrypted) {
                    doc.isAllSecurityToBeRemoved = true
                }
                doc.save(unlockedFile)
            }

            val pfd = ParcelFileDescriptor.open(unlockedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val engine = PdfRendererEngine(pfd)

            ViewerUiState.Success(
                documentName = metadata.name,
                format = DocumentFormat.PDF,
                content = PdfDocumentModel(uriString = uri.toString(), pageCount = engine.pageCount),
                pdfRendererEngine = engine,
                originalUri = uri
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ViewerUiState.PasswordRequired(
                documentName = "Password Protected PDF",
                uri = uri,
                errorMessage = "Incorrect password. Please try again."
            )
        }
    }

    suspend fun unlockVault(uri: Uri, password: CharArray): ViewerUiState = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val encryptedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot read vault file")

            val decryptedBytes = DocumentCryptoEngine.decrypt(encryptedBytes, password)

            val innerFormat = ByteArrayInputStream(decryptedBytes).use {
                FileSniffer.sniffFormat(it, uri.lastPathSegment?.removeSuffix(".vault")?.removeSuffix(".enc"))
            }

            var pdfRenderer: PdfRendererEngine? = null
            val content: DocumentContent = when (innerFormat) {
                DocumentFormat.PDF -> {
                    val tempPdf = File.createTempFile("unlocked_pdf_", ".pdf", context.cacheDir)
                    tempPdf.writeBytes(decryptedBytes)
                    val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                    val engine = PdfRendererEngine(pfd)
                    pdfRenderer = engine
                    PdfDocumentModel(uriString = uri.toString(), pageCount = engine.pageCount)
                }
                DocumentFormat.DOCX -> {
                    ByteArrayInputStream(decryptedBytes).use { stream ->
                        DocxEngine.parse(stream, uri.toString())
                    }
                }
                DocumentFormat.XLSX -> {
                    ByteArrayInputStream(decryptedBytes).use { stream ->
                        XlsxEngine.parse(stream, uri.toString())
                    }
                }
                DocumentFormat.CSV -> {
                    ByteArrayInputStream(decryptedBytes).use { stream ->
                        CsvEngine.parse(stream, uri.toString())
                    }
                }
                DocumentFormat.TXT, DocumentFormat.RTF, DocumentFormat.MARKDOWN -> {
                    ByteArrayInputStream(decryptedBytes).use { stream ->
                        TextEngine.parse(stream, uri.toString(), innerFormat)
                    }
                }
                else -> {
                    TextDocumentModel(
                        uriString = uri.toString(),
                        text = String(decryptedBytes, Charsets.UTF_8),
                        format = DocumentFormat.TXT
                    )
                }
            }

            ViewerUiState.Success(
                documentName = (uri.lastPathSegment ?: "Unlocked Document").removeSuffix(".vault").removeSuffix(".enc"),
                format = innerFormat,
                content = content,
                pdfRendererEngine = pdfRenderer,
                originalUri = uri,
                isCachedCopy = true
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ViewerUiState.PasswordRequired(
                documentName = "Encrypted Document Vault",
                uri = uri,
                errorMessage = "Incorrect password or corrupt vault file. Please try again."
            )
        }
    }

    fun copyStreamToCache(uri: Uri, prefix: String, suffix: String): File {
        val cacheFile = File.createTempFile(prefix, suffix, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")
        return cacheFile
    }

    private fun isPdfEncrypted(file: File): Boolean {
        return try {
            PDDocument.load(file).use { doc ->
                doc.isEncrypted
            }
        } catch (_: Exception) {
            true
        }
    }
}
