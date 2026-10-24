package com.alldoc.app.presentation.viewer

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alldoc.app.core.data.repository.DocumentRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Success(
        val documentName: String,
        val format: DocumentFormat,
        val content: DocumentContent,
        val isModified: Boolean = false,
        val isSaving: Boolean = false,
        val pdfRendererEngine: PdfRendererEngine? = null
    ) : ViewerUiState
    data class Error(val message: String) : ViewerUiState
}

class DocumentViewerViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DocumentRepository = DocumentRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var activeUri: Uri? = null

    fun loadDocument(uri: Uri) {
        val previousState = _uiState.value as? ViewerUiState.Success
        previousState?.pdfRendererEngine?.close()

        activeUri = uri
        viewModelScope.launch {
            _uiState.value = ViewerUiState.Loading
            try {
                val metadata = repository.recordDocumentAccess(uri)
                val contentResolver = getApplication<Application>().contentResolver

                var pdfRenderer: PdfRendererEngine? = null

                val content: DocumentContent = when (metadata.format) {
                    DocumentFormat.PDF -> {
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                            ?: throw IllegalStateException("Cannot open PDF file descriptor")
                        val engine = PdfRendererEngine(pfd)
                        pdfRenderer = engine
                        PdfDocumentModel(
                            uriString = uri.toString(),
                            pageCount = engine.pageCount
                        )
                    }

                    DocumentFormat.DOCX, DocumentFormat.DOC -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            DocxEngine.parse(stream, uri.toString())
                        } ?: throw IllegalStateException("Cannot open Word stream")
                    }

                    DocumentFormat.XLSX, DocumentFormat.XLS -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            XlsxEngine.parse(stream, uri.toString())
                        } ?: throw IllegalStateException("Cannot open Excel stream")
                    }

                    DocumentFormat.CSV -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            CsvEngine.parse(stream, uri.toString())
                        } ?: throw IllegalStateException("Cannot open CSV stream")
                    }

                    DocumentFormat.PPTX, DocumentFormat.PPT -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            PptxEngine.parse(stream, uri.toString())
                        } ?: throw IllegalStateException("Cannot open PowerPoint stream")
                    }

                    DocumentFormat.TXT, DocumentFormat.RTF, DocumentFormat.MARKDOWN -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            TextEngine.parse(stream, uri.toString(), metadata.format)
                        } ?: throw IllegalStateException("Cannot open text stream")
                    }

                    DocumentFormat.IMAGE_JPEG, DocumentFormat.IMAGE_PNG, DocumentFormat.IMAGE_WEBP -> {
                        ImageDocumentModel(uriString = uri.toString())
                    }

                    DocumentFormat.UNKNOWN -> {
                        // Attempt fallback as plain text
                        contentResolver.openInputStream(uri)?.use { stream ->
                            TextEngine.parse(stream, uri.toString(), DocumentFormat.TXT)
                        } ?: throw IllegalStateException("Unsupported file format")
                    }
                }

                _uiState.value = ViewerUiState.Success(
                    documentName = metadata.name,
                    format = metadata.format,
                    content = content,
                    pdfRendererEngine = pdfRenderer
                )
            } catch (e: Exception) {
                _uiState.value = ViewerUiState.Error(e.localizedMessage ?: "Failed to open document")
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

        val newParagraphs = wordModel.paragraphs.toMutableList()
        if (paragraphIndex in newParagraphs.indices) {
            val p = newParagraphs[paragraphIndex]
            newParagraphs[paragraphIndex] = p.copy(
                runs = listOf(WordTextRun(text = newText))
            )
        } else {
            newParagraphs.add(WordParagraph(runs = listOf(WordTextRun(text = newText))))
        }

        _uiState.value = current.copy(
            content = wordModel.copy(paragraphs = newParagraphs),
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

    // Text modifications
    fun updateText(newText: String) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val textModel = current.content as? TextDocumentModel ?: return

        _uiState.value = current.copy(
            content = textModel.copy(text = newText),
            isModified = true
        )
    }

    // Save back to original file
    fun saveDocument(onComplete: (Boolean) -> Unit) {
        val current = _uiState.value as? ViewerUiState.Success ?: return
        val uri = activeUri ?: return

        viewModelScope.launch {
            _uiState.update { (it as? ViewerUiState.Success)?.copy(isSaving = true) ?: it }

            val success = withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver

                    // 1. Create auto-save snapshot backup before saving
                    val backupOut = ByteArrayOutputStream()

                    when (val content = current.content) {
                        is WordDocumentModel -> {
                            DocxEngine.save(content, backupOut)
                            val bytes = backupOut.toByteArray()
                            repository.saveSnapshotVersion(uri.toString(), bytes, "Pre-save backup")
                            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                out.write(bytes)
                            }
                        }

                        is SpreadsheetDocumentModel -> {
                            if (current.format == DocumentFormat.CSV) {
                                CsvEngine.save(content, backupOut)
                            } else {
                                XlsxEngine.save(content, null, backupOut)
                            }
                            val bytes = backupOut.toByteArray()
                            repository.saveSnapshotVersion(uri.toString(), bytes, "Pre-save backup")
                            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                out.write(bytes)
                            }
                        }

                        is TextDocumentModel -> {
                            TextEngine.save(content, backupOut)
                            val bytes = backupOut.toByteArray()
                            repository.saveSnapshotVersion(uri.toString(), bytes, "Pre-save backup")
                            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                out.write(bytes)
                            }
                        }

                        is PdfDocumentModel -> {
                            if (current.pdfRendererEngine != null) {
                                PdfManipulator.exportWithAnnotations(
                                    current.pdfRendererEngine,
                                    content.annotations,
                                    backupOut
                                )
                                val bytes = backupOut.toByteArray()
                                repository.saveSnapshotVersion(uri.toString(), bytes, "Annotated PDF export")
                                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                    out.write(bytes)
                                }
                            }
                        }

                        else -> {}
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            _uiState.update {
                if (it is ViewerUiState.Success) {
                    it.copy(isSaving = false, isModified = !success)
                } else it
            }

            onComplete(success)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val current = _uiState.value as? ViewerUiState.Success
        current?.pdfRendererEngine?.close()
    }
}
