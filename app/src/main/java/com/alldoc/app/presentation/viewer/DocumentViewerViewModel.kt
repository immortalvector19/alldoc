package com.alldoc.app.presentation.viewer

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.alldoc.app.core.data.repository.DocumentRepository
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.core.engine.pdf.PdfManipulator
import com.alldoc.app.core.engine.pdf.PdfRendererEngine
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.DocumentContent
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.presentation.viewer.coordinator.DocumentEditCoordinator
import com.alldoc.app.presentation.viewer.coordinator.DocumentLoadCoordinator
import com.alldoc.app.presentation.viewer.coordinator.DocumentPersistenceCoordinator
import com.alldoc.app.presentation.viewer.coordinator.PdfViewerCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private val loadCoordinator by lazy { DocumentLoadCoordinator(application, repository) }
    private val persistenceCoordinator by lazy { DocumentPersistenceCoordinator(application) }
    private val pdfCoordinator by lazy { PdfViewerCoordinator(application) }

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
            _uiState.value = loadCoordinator.loadDocument(uri)
        }
    }

    fun unlockPdfWithPassword(password: String) {
        val uri = activeUri ?: return
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading
            _uiState.value = loadCoordinator.unlockPdf(uri, password)
        }
    }

    fun unlockVaultDocument(password: CharArray) {
        val uri = activeUri ?: return
        viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading
            _uiState.value = loadCoordinator.unlockVault(uri, password)
        }
    }

    // PDF Operations
    fun rotatePdf(degrees: Int, allPages: Boolean, currentPage: Int = 1, onComplete: (Boolean) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: run { onComplete(false); return }
        val uri = activeUri ?: run { onComplete(false); return }
        val pdfModel = current.content as? PdfDocumentModel ?: run { onComplete(false); return }

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val cacheDir = getApplication<Application>().cacheDir
                    val srcFile = loadCoordinator.copyStreamToCache(uri, "pdf_rot_src_", ".pdf")
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

    // In-Memory Document Modifications
    fun addPdfAnnotation(annotation: Annotation) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val pdfModel = current.content as? PdfDocumentModel ?: return
        _uiState.value = current.copy(
            content = DocumentEditCoordinator.addPdfAnnotation(pdfModel, annotation),
            isModified = true
        )
    }

    fun updateWordText(paragraphIndex: Int, newText: String) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val wordModel = current.content as? WordDocumentModel ?: return
        _uiState.value = current.copy(
            content = DocumentEditCoordinator.updateWordText(wordModel, paragraphIndex, newText),
            isModified = true
        )
    }

    fun updateExcelCell(row: Int, col: Int, rawValue: String, formula: String? = null) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val xlsxModel = current.content as? SpreadsheetDocumentModel ?: return
        _uiState.value = current.copy(
            content = DocumentEditCoordinator.updateExcelCell(xlsxModel, row, col, rawValue, formula),
            isModified = true
        )
    }

    fun selectSpreadsheetSheet(sheetIndex: Int) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val xlsxModel = current.content as? SpreadsheetDocumentModel ?: return
        _uiState.value = current.copy(
            content = DocumentEditCoordinator.selectSpreadsheetSheet(xlsxModel, sheetIndex)
        )
    }

    fun updateText(newText: String) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val textModel = current.content as? TextDocumentModel ?: return
        _uiState.value = current.copy(
            content = DocumentEditCoordinator.updateText(textModel, newText),
            isModified = true
        )
    }

    // Save flows
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

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        val current = _uiState.value as? ViewerUiState.Success
        current?.pdfRendererEngine?.close()
    }
}
