package com.alldoc.app.presentation.viewer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.alldoc.app.core.data.repository.DocumentRepository
import com.alldoc.app.core.engine.ContentResolverStorageSink
import com.alldoc.app.core.engine.FileSniffer
import com.alldoc.app.core.engine.SafeDocumentWriter
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.core.engine.legacy.Ole2Engine
import com.alldoc.app.core.engine.security.DocumentCryptoEngine
import com.alldoc.app.core.engine.openxml.CsvEngine
import com.alldoc.app.core.engine.openxml.DocxEngine
import com.alldoc.app.core.engine.openxml.PptxEngine
import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.engine.pdf.PdfManipulator
import com.alldoc.app.core.engine.pdf.PdfRendererEngine
import com.alldoc.app.core.engine.text.TextEngine
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.DocumentAnnotations
import com.alldoc.app.core.model.DocumentContent
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.ImageDocumentModel
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.PresentationDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTextRun
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Success(
        val documentName: String,
        val format: DocumentFormat,
        val content: DocumentContent,
        val isModified: Boolean = false,
        val isSaving: Boolean = false,
        val pdfRendererEngine: PdfRendererEngine? = null,
        val originalUri: Uri? = null,
        val isCachedCopy: Boolean = false
    ) : ViewerUiState
    data class PasswordRequired(
        val documentName: String,
        val uri: Uri,
        val errorMessage: String? = null
    ) : ViewerUiState
    data class UnsupportedFormat(
        val documentName: String,
        val uri: Uri,
        val mimeType: String? = null
    ) : ViewerUiState
    data class Error(
        val message: String,
        val isTemporaryPermissionRevoked: Boolean = false
    ) : ViewerUiState
}

class DocumentViewerViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val repository: DocumentRepository = DocumentRepository(application)
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_ACTIVE_DOCUMENT_URI = "key_active_document_uri"
    }

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    var activeUri: Uri? = null
        private set

    private var loadJob: Job? = null

    init {
        val savedUriStr: String? = savedStateHandle[KEY_ACTIVE_DOCUMENT_URI]
        if (savedUriStr != null && activeUri == null) {
            loadDocument(Uri.parse(savedUriStr))
        }
    }

    fun loadDocument(uri: Uri) {
        val previousState = _uiState.value as? ViewerUiState.Success
        previousState?.pdfRendererEngine?.close()

        activeUri = uri
        savedStateHandle[KEY_ACTIVE_DOCUMENT_URI] = uri.toString()
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading

            withContext(Dispatchers.IO) {
                try {
                    val metadata = repository.recordDocumentAccess(uri)
                    val contentResolver = getApplication<Application>().contentResolver

                    val isCached = uri.toString().contains("cached_docs") ||
                        (uri.scheme == ContentResolver.SCHEME_FILE && uri.path?.contains("cached_docs") == true)

                    // 1. Sniff real format by inspecting file stream header
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
                            // Ensure seekable local file descriptor for PdfRenderer
                            val cachedPdfFile = copyStreamToCache(uri, "pdf_view", ".pdf")
                            
                            // Check if encrypted with PdfBox
                            val encrypted = isPdfEncrypted(cachedPdfFile)
                            if (encrypted) {
                                _uiState.value = ViewerUiState.PasswordRequired(
                                    documentName = metadata.name,
                                    uri = uri
                                )
                                return@withContext
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
                            _uiState.value = ViewerUiState.PasswordRequired(
                                documentName = metadata.name,
                                uri = uri,
                                errorMessage = null
                            )
                            return@withContext
                        }

                        DocumentFormat.UNKNOWN -> {
                            _uiState.value = ViewerUiState.UnsupportedFormat(
                                documentName = metadata.name,
                                uri = uri,
                                mimeType = metadata.format.mimeType
                            )
                            return@withContext
                        }
                    }

                    _uiState.value = ViewerUiState.Success(
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
                            _uiState.value = ViewerUiState.Error(
                                "Document is too large to open on this device (Out of Memory)."
                            )
                        }
                        t is SecurityException -> {
                            _uiState.value = ViewerUiState.Error(
                                "Access permission expired or was revoked. Please re-open the document from the originating app.",
                                isTemporaryPermissionRevoked = true
                            )
                        }
                        else -> {
                            _uiState.value = ViewerUiState.Error(
                                t.localizedMessage ?: "Failed to open document"
                            )
                        }
                    }
                }
            }
        }
    }

    fun unlockPdfWithPassword(password: String) {
        val uri = activeUri ?: return
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading

            withContext(Dispatchers.IO) {
                try {
                    val metadata = repository.recordDocumentAccess(uri)
                    val cachedPdfFile = copyStreamToCache(uri, "pdf_locked", ".pdf")
                    val unlockedFile = File(
                        getApplication<Application>().cacheDir,
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

                    _uiState.value = ViewerUiState.Success(
                        documentName = metadata.name,
                        format = DocumentFormat.PDF,
                        content = PdfDocumentModel(uriString = uri.toString(), pageCount = engine.pageCount),
                        pdfRendererEngine = engine,
                        originalUri = uri
                    )
                } catch (t: Throwable) {
                    _uiState.value = ViewerUiState.PasswordRequired(
                        documentName = "Password Protected PDF",
                        uri = uri,
                        errorMessage = "Incorrect password. Please try again."
                    )
                }
            }
        }
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

    private fun copyStreamToCache(uri: Uri, prefix: String, suffix: String): File {
        val cacheFile = File.createTempFile(prefix, suffix, getApplication<Application>().cacheDir)
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")
        return cacheFile
    }

    // PDF Tools operations
    fun rotatePdf(degrees: Int, allPages: Boolean, currentPage: Int = 1, onComplete: (Boolean) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: run { onComplete(false); return }
        val uri = activeUri ?: run { onComplete(false); return }
        val pdfModel = current.content as? PdfDocumentModel ?: run { onComplete(false); return }

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val cacheDir = getApplication<Application>().cacheDir
                    val srcFile = copyStreamToCache(uri, "pdf_rot_src_", ".pdf")
                    val destFile = File.createTempFile("pdf_rot_dest_", ".pdf", cacheDir)

                    val pageOrder = (0 until pdfModel.pageCount).toList()
                    val rotationMap = if (allPages) {
                        pageOrder.associateWith { degrees }
                    } else {
                        mapOf((currentPage - 1).coerceIn(0, pdfModel.pageCount - 1) to degrees)
                    }

                    PdfManipulator.reorderAndRotate(srcFile, destFile, pageOrder, rotationMap)
                    srcFile.delete()

                    val pfd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val engine = PdfRendererEngine(pfd)
                    current.pdfRendererEngine?.close()

                    withContext(Dispatchers.Main) {
                        _uiState.value = current.copy(
                            content = pdfModel.copy(pageCount = engine.pageCount),
                            pdfRendererEngine = engine,
                            isModified = true
                        )
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            onComplete(success)
        }
    }

    private val persistenceCoordinator by lazy { com.alldoc.app.presentation.viewer.coordinator.DocumentPersistenceCoordinator(application) }
    private val pdfCoordinator by lazy { com.alldoc.app.presentation.viewer.coordinator.PdfViewerCoordinator(application) }

    fun splitPdfToUri(fromPage: Int, toPage: Int, destUri: Uri, onResult: (SaveResult) -> Unit) {
        val uri = activeUri ?: run { onResult(SaveResult.ValidationFailed("No active URI")); return }
        viewModelScope.launch {
            pdfCoordinator.splitPdfToUri(uri, fromPage, toPage, destUri, onResult)
        }
    }

    fun mergePdfWithUri(secondPdfUri: Uri, onComplete: (Boolean) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: run { onComplete(false); return }
        val uri = activeUri ?: run { onComplete(false); return }
        val pdfModel = current.content as? PdfDocumentModel ?: run { onComplete(false); return }

        viewModelScope.launch {
            val cacheDir = getApplication<Application>().cacheDir
            val destFile = File.createTempFile("pdf_merged_", ".pdf", cacheDir)
            val success = pdfCoordinator.mergePdfFiles(uri, secondPdfUri, destFile)
            if (success) {
                try {
                    val pfd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val engine = PdfRendererEngine(pfd)
                    current.pdfRendererEngine?.close()
                    _uiState.value = current.copy(
                        content = pdfModel.copy(pageCount = engine.pageCount),
                        pdfRendererEngine = engine,
                        isModified = true
                    )
                    onComplete(true)
                } catch (_: Exception) {
                    onComplete(false)
                }
            } else {
                onComplete(false)
            }
        }
    }

    fun protectPdfToUri(password: String, destUri: Uri, onResult: (SaveResult) -> Unit) {
        val uri = activeUri ?: run { onResult(SaveResult.ValidationFailed("No active URI")); return }
        viewModelScope.launch {
            pdfCoordinator.protectPdfToUri(uri, password, destUri, onResult)
        }
    }

    // Vault Document Unlock & Lock
    fun unlockVaultDocument(password: CharArray) {
        val uri = activeUri ?: return
        viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading
            withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver
                    val encryptedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Cannot read vault file")

                    val decryptedBytes = DocumentCryptoEngine.decrypt(encryptedBytes, password)

                    val innerFormat = java.io.ByteArrayInputStream(decryptedBytes).use {
                        FileSniffer.sniffFormat(it, uri.lastPathSegment?.removeSuffix(".vault")?.removeSuffix(".enc"))
                    }

                    var pdfRenderer: PdfRendererEngine? = null
                    val content: DocumentContent = when (innerFormat) {
                        DocumentFormat.PDF -> {
                            val tempPdf = File.createTempFile("unlocked_pdf_", ".pdf", getApplication<Application>().cacheDir)
                            tempPdf.writeBytes(decryptedBytes)
                            val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                            val engine = PdfRendererEngine(pfd)
                            pdfRenderer = engine
                            PdfDocumentModel(uriString = uri.toString(), pageCount = engine.pageCount)
                        }
                        DocumentFormat.DOCX -> {
                            java.io.ByteArrayInputStream(decryptedBytes).use { stream ->
                                DocxEngine.parse(stream, uri.toString())
                            }
                        }
                        DocumentFormat.XLSX -> {
                            java.io.ByteArrayInputStream(decryptedBytes).use { stream ->
                                XlsxEngine.parse(stream, uri.toString())
                            }
                        }
                        DocumentFormat.CSV -> {
                            java.io.ByteArrayInputStream(decryptedBytes).use { stream ->
                                CsvEngine.parse(stream, uri.toString())
                            }
                        }
                        DocumentFormat.TXT, DocumentFormat.RTF, DocumentFormat.MARKDOWN -> {
                            java.io.ByteArrayInputStream(decryptedBytes).use { stream ->
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

                    _uiState.value = ViewerUiState.Success(
                        documentName = (uri.lastPathSegment ?: "Unlocked Document").removeSuffix(".vault").removeSuffix(".enc"),
                        format = innerFormat,
                        content = content,
                        pdfRendererEngine = pdfRenderer,
                        originalUri = uri,
                        isCachedCopy = true
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    _uiState.value = ViewerUiState.PasswordRequired(
                        documentName = "Encrypted Document Vault",
                        uri = uri,
                        errorMessage = "Incorrect password or corrupt vault file. Please try again."
                    )
                }
            }
        }
    }

    fun lockDocumentToUri(password: CharArray, destinationUri: Uri, onResult: (SaveResult) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: run {
            onResult(SaveResult.ValidationFailed("No active document loaded"))
            return
        }

        viewModelScope.launch {
            _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = true) ?: it }
            persistenceCoordinator.lockDocumentToVault(current, activeUri, password, destinationUri) { result ->
                _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = false) ?: it }
                onResult(result)
            }
        }
    }

    // PDF Annotation
    fun addPdfAnnotation(annotation: Annotation) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val pdfModel = current.content as? PdfDocumentModel ?: return
        val updatedAnnotations = pdfModel.annotations.add(annotation)
        val updatedContent = pdfModel.copy(annotations = updatedAnnotations)

        _uiState.value = current.copy(
            content = updatedContent,
            isModified = true
        )
    }

    // Word modifications
    fun updateWordText(paragraphIndex: Int, newText: String) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val wordModel = current.content as? WordDocumentModel ?: return

        val updatedParagraphs = wordModel.paragraphs.toMutableList()
        if (paragraphIndex in updatedParagraphs.indices) {
            val oldP = updatedParagraphs[paragraphIndex]
            val updatedRun = oldP.runs.firstOrNull()?.copy(text = newText) ?: WordTextRun(text = newText)
            updatedParagraphs[paragraphIndex] = oldP.copy(runs = listOf(updatedRun))
        }

        _uiState.value = current.copy(
            content = wordModel.copy(paragraphs = updatedParagraphs),
            isModified = true
        )
    }

    // Excel modifications
    fun updateExcelCell(row: Int, col: Int, rawValue: String, formula: String? = null) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val xlsxModel = current.content as? SpreadsheetDocumentModel ?: return

        val updatedSheet = xlsxModel.workbook.activeSheet.updateCell(
            row, col, CellData(rawValue = rawValue, formula = formula)
        )
        val evaluatedSheet = XlsxEngine.evaluateFormulas(updatedSheet)
        val updatedWorkbook = xlsxModel.workbook.updateActiveSheet(evaluatedSheet)

        _uiState.value = current.copy(
            content = xlsxModel.copy(workbook = updatedWorkbook),
            isModified = true
        )
    }

    fun selectSpreadsheetSheet(sheetIndex: Int) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val xlsxModel = current.content as? SpreadsheetDocumentModel ?: return
        if (sheetIndex in xlsxModel.workbook.sheets.indices) {
            val updatedWorkbook = xlsxModel.workbook.copy(activeSheetIndex = sheetIndex)
            _uiState.value = current.copy(content = xlsxModel.copy(workbook = updatedWorkbook))
        }
    }

    // Text modifications
    fun updateText(newText: String) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val textModel = current.content as? TextDocumentModel ?: return

        _uiState.value = current.copy(
            content = textModel.copy(text = newText),
            isModified = true
        )
    }

    // Save back to original file with safe staging, post-write hash verification, and automatic backup restore
    fun saveDocument(onComplete: (Boolean) -> Unit) {
        saveDocumentWithResult { result: SaveResult ->
            onComplete(result is SaveResult.Success)
        }
    }

    fun saveDocumentWithResult(onResult: (SaveResult) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: run {
            onResult(SaveResult.ValidationFailed("No active document loaded"))
            return
        }
        val uri = activeUri ?: run {
            onResult(SaveResult.ValidationFailed("No active URI"))
            return
        }

        // Read-only legacy formats, images, or cached temporary copies cannot overwrite original
        if (!current.format.isEditable || current.isCachedCopy) {
            onResult(SaveResult.PermissionDenied("File is read-only or a temporary cached copy. Use 'Save a copy' instead."))
            return
        }

        viewModelScope.launch {
            _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = true) ?: it }
            persistenceCoordinator.saveActiveDocument(
                current = current,
                activeUri = uri,
                onSaveSnapshot = { backupFile ->
                    repository.saveSnapshotVersion(uriString = uri.toString(), file = backupFile, note = "Pre-save backup")
                },
                onResult = { result ->
                    _uiState.update {
                        if (it is ViewerUiState.Success) {
                            it.copy(isSaving = false, isModified = result !is SaveResult.Success)
                        } else it
                    }
                    onResult(result)
                }
            )
        }
    }

    /**
     * Saves a copy of the current document to a user-chosen destination URI (via SAF ACTION_CREATE_DOCUMENT)
     * using SafeDocumentWriter with post-write verification.
     */
    fun saveCopyToUri(destinationUri: Uri, onComplete: (Boolean) -> Unit) {
        saveCopyToUriWithResult(destinationUri) { result: SaveResult ->
            onComplete(result is SaveResult.Success)
        }
    }

    fun saveCopyToUriWithResult(destinationUri: Uri, onResult: (SaveResult) -> Unit = {}) {
        val current = _uiState.value as? ViewerUiState.Success ?: run {
            onResult(SaveResult.ValidationFailed("No active document loaded"))
            return
        }

        viewModelScope.launch {
            _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = true) ?: it }
            persistenceCoordinator.saveDocumentAsCopy(current, activeUri, destinationUri) { result ->
                if (result is SaveResult.Success) {
                    viewModelScope.launch { repository.recordDocumentAccess(destinationUri) }
                }
                _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = false) ?: it }
                onResult(result)
            }
        }
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

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        val current = _uiState.value as? ViewerUiState.Success
        current?.pdfRendererEngine?.close()
    }
}
