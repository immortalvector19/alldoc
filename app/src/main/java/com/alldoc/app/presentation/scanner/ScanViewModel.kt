package com.alldoc.app.presentation.scanner

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alldoc.app.core.data.repository.DocumentRepository
import com.alldoc.app.core.engine.scanner.DocumentScannerManager
import com.alldoc.app.core.engine.scanner.ImageFilterEngine
import com.alldoc.app.core.engine.scanner.OcrEngine
import com.alldoc.app.core.engine.scanner.OcrResult
import com.alldoc.app.core.engine.scanner.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val imageUri: Uri,
    val filter: ScanFilter = ScanFilter.ENHANCED_COLOR,
    val ocrResult: OcrResult? = null
)

data class ScanReviewUiState(
    val pages: List<ScannedPage> = emptyList(),
    val selectedPageIndex: Int = 0,
    val isProcessingOcr: Boolean = false,
    val isCompilingPdf: Boolean = false,
    val compiledPdfUri: Uri? = null,
    val error: String? = null
)

class ScanViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DocumentRepository = DocumentRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScanReviewUiState())
    val uiState: StateFlow<ScanReviewUiState> = _uiState.asStateFlow()

    fun setScannedPages(imageUris: List<Uri>) {
        val newPages = imageUris.map { ScannedPage(imageUri = it) }
        _uiState.update { it.copy(pages = newPages, selectedPageIndex = 0) }
        performOcrOnAllPages()
    }

    fun selectPage(index: Int) {
        _uiState.update { it.copy(selectedPageIndex = index) }
    }

    fun applyFilterToPage(pageIndex: Int, filter: ScanFilter) {
        _uiState.update { state ->
            val updated = state.pages.toMutableList()
            if (pageIndex in updated.indices) {
                updated[pageIndex] = updated[pageIndex].copy(filter = filter)
            }
            state.copy(pages = updated)
        }
    }

    fun performOcrOnAllPages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingOcr = true) }
            val context = getApplication<Application>()
            val currentPages = _uiState.value.pages

            val updatedPages = currentPages.map { page ->
                val ocr = OcrEngine.recognizeText(context, page.imageUri)
                page.copy(ocrResult = ocr)
            }

            _uiState.update { it.copy(pages = updatedPages, isProcessingOcr = false) }
        }
    }

    fun compileToPdf(documentTitle: String, onComplete: (Uri?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompilingPdf = true) }
            val context = getApplication<Application>()
            val state = _uiState.value

            val compiledUri = withContext(Dispatchers.IO) {
                try {
                    val pageBitmapsWithOcr = state.pages.mapNotNull { page ->
                        val inputStream = context.contentResolver.openInputStream(page.imageUri)
                        val original = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        if (original != null) {
                            val filtered = ImageFilterEngine.applyFilter(original, page.filter)
                            val ocr = page.ocrResult ?: OcrResult("", emptyList())
                            Pair(filtered, ocr)
                        } else null
                    }

                    val scansDir = File(context.filesDir, "scans").apply { mkdirs() }
                    val filename = "${documentTitle.trim().replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
                    val pdfFile = File(scansDir, filename)

                    DocumentScannerManager.createSearchablePdf(pageBitmapsWithOcr, pdfFile)

                    val uri = Uri.fromFile(pdfFile)
                    val combinedOcrText = state.pages.mapNotNull { it.ocrResult?.fullText }.joinToString("\n")
                    repository.recordDocumentAccess(uri, combinedOcrText)
                    uri
                } catch (e: Exception) {
                    null
                }
            }

            _uiState.update { it.copy(isCompilingPdf = false, compiledPdfUri = compiledUri) }
            onComplete(compiledUri)
        }
    }
}
