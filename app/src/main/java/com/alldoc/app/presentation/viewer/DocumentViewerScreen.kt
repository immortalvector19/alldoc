package com.alldoc.app.presentation.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.ImageDocumentModel
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.PresentationDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.presentation.security.VaultPasswordDialog
import com.alldoc.app.presentation.viewer.components.CachedCopyBanner
import com.alldoc.app.presentation.viewer.components.ImageViewerComponent
import com.alldoc.app.presentation.viewer.components.LegacyFormatBanner
import com.alldoc.app.presentation.viewer.components.PasswordPromptScreen
import com.alldoc.app.presentation.viewer.components.PdfToolAction
import com.alldoc.app.presentation.viewer.components.PdfToolsDialog
import com.alldoc.app.presentation.viewer.components.PdfViewerComponent
import com.alldoc.app.presentation.viewer.components.PresentationComponent
import com.alldoc.app.presentation.viewer.components.SpreadsheetComponent
import com.alldoc.app.presentation.viewer.components.TextEditorComponent
import com.alldoc.app.presentation.viewer.components.UnsavedChangesDialog
import com.alldoc.app.presentation.viewer.components.UnsupportedFormatScreen
import com.alldoc.app.presentation.viewer.components.ViewerBottomBar
import com.alldoc.app.presentation.viewer.components.ViewerErrorScreen
import com.alldoc.app.presentation.viewer.components.ViewerReadingModePill
import com.alldoc.app.presentation.viewer.components.ViewerTopAppBar
import com.alldoc.app.presentation.viewer.components.WordEditorComponent
import kotlinx.coroutines.launch

@Composable
fun DocumentViewerScreen(
    uiState: ViewerUiState,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenInAnotherApp: (Uri) -> Unit = {},
    onSaveCopyClick: () -> Unit = {},
    onSplitPdfClick: (fromPage: Int, toPage: Int) -> Unit = { _, _ -> },
    onMergePdfClick: () -> Unit = {},
    onProtectPdfClick: (String) -> Unit = {},
    onLockDocumentClick: (CharArray) -> Unit = {},
    viewModel: DocumentViewerViewModel
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showPdfTools by remember { mutableStateOf(false) }
    var showVaultLockDialog by remember { mutableStateOf(false) }
    var isReadingMode by remember { mutableStateOf(true) }

    val successState = uiState as? ViewerUiState.Success
    val isModified = successState?.isModified == true

    BackHandler(enabled = isModified) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onDismiss = { showUnsavedDialog = false },
            onDiscard = {
                showUnsavedDialog = false
                onBackClick()
            },
            onSave = {
                showUnsavedDialog = false
                onSaveClick()
                onBackClick()
            }
        )
    }

    Scaffold(
        topBar = {
            if (!isReadingMode) {
                ViewerTopAppBar(
                    uiState = uiState,
                    activeUri = viewModel.activeUri,
                    onBackClick = {
                        if (isModified) {
                            showUnsavedDialog = true
                        } else {
                            onBackClick()
                        }
                    },
                    onShareClick = onShareClick,
                    onSaveCopyClick = onSaveCopyClick,
                    onOpenInAnotherApp = onOpenInAnotherApp,
                    onShowPdfTools = { showPdfTools = true },
                    onShowVaultLockDialog = { showVaultLockDialog = true },
                    onSaveClick = onSaveClick
                )
            }
        },
        bottomBar = {
            if (!isReadingMode && successState != null) {
                ViewerBottomBar(
                    successState = successState,
                    onEnterReadingMode = { isReadingMode = true },
                    onShowPdfTools = { showPdfTools = true },
                    onShowVaultLockDialog = { showVaultLockDialog = true },
                    onShareClick = onShareClick,
                    onSaveClick = onSaveClick
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState) {
                is ViewerUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is ViewerUiState.PasswordRequired -> {
                    PasswordPromptScreen(
                        state = uiState,
                        onUnlock = { password ->
                            val docName = uiState.documentName.lowercase()
                            if (docName.endsWith(".vault") || docName.endsWith(".enc") || docName.contains("vault")) {
                                viewModel.unlockVaultDocument(password.toCharArray())
                            } else {
                                viewModel.unlockPdfWithPassword(password)
                            }
                        },
                        onBackClick = onBackClick
                    )
                }

                is ViewerUiState.UnsupportedFormat -> {
                    UnsupportedFormatScreen(
                        state = uiState,
                        onOpenInAnotherApp = { onOpenInAnotherApp(uiState.uri) },
                        onBackClick = onBackClick
                    )
                }

                is ViewerUiState.Error -> {
                    ViewerErrorScreen(
                        state = uiState,
                        activeUri = viewModel.activeUri,
                        onBackClick = onBackClick,
                        onRetry = {
                            viewModel.activeUri?.let { viewModel.loadDocument(it) }
                        }
                    )
                }

                is ViewerUiState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.format in listOf(DocumentFormat.DOC, DocumentFormat.XLS, DocumentFormat.PPT)) {
                            LegacyFormatBanner(
                                originalUri = uiState.originalUri ?: viewModel.activeUri,
                                onOpenInAnotherApp = onOpenInAnotherApp
                            )
                        } else if (uiState.isCachedCopy) {
                            CachedCopyBanner(onSaveCopyClick = onSaveCopyClick)
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (val content = uiState.content) {
                                is PdfDocumentModel -> {
                                    PdfViewerComponent(
                                        model = content,
                                        renderer = uiState.pdfRendererEngine,
                                        onAddAnnotation = { viewModel.addPdfAnnotation(it) }
                                    )
                                }
                                is WordDocumentModel -> {
                                    WordEditorComponent(
                                        model = content,
                                        onUpdateParagraph = { idx, text -> viewModel.updateWordText(idx, text) }
                                    )
                                }
                                is SpreadsheetDocumentModel -> {
                                    SpreadsheetComponent(
                                        model = content,
                                        onUpdateCell = { r, c, raw, f -> viewModel.updateExcelCell(r, c, raw, f) },
                                        onSelectSheet = { idx -> viewModel.selectSpreadsheetSheet(idx) }
                                    )
                                }
                                is PresentationDocumentModel -> {
                                    PresentationComponent(model = content)
                                }
                                is TextDocumentModel -> {
                                    TextEditorComponent(
                                        model = content,
                                        onUpdateText = { viewModel.updateText(it) }
                                    )
                                }
                                is ImageDocumentModel -> {
                                    ImageViewerComponent(model = content)
                                }
                            }
                        }
                    }
                }
            }

            if (isReadingMode) {
                ViewerReadingModePill(onExitReadingMode = { isReadingMode = false })
            }
        }
    }

    if (showPdfTools && successState != null) {
        val pdfModel = successState.content as? PdfDocumentModel
        PdfToolsDialog(
            pageCount = pdfModel?.pageCount ?: 1,
            onDismiss = { showPdfTools = false },
            onAction = { action ->
                showPdfTools = false
                when (action) {
                    is PdfToolAction.Rotate -> {
                        viewModel.rotatePdf(action.degrees, action.allPages) { success ->
                            scope.launch {
                                snackbarHostState.showSnackbar(if (success) "Pages rotated successfully" else "Failed to rotate pages")
                            }
                        }
                    }
                    is PdfToolAction.Split -> onSplitPdfClick(action.fromPage, action.toPage)
                    is PdfToolAction.Merge -> onMergePdfClick()
                    is PdfToolAction.Protect -> onProtectPdfClick(action.password)
                }
            }
        )
    }

    if (showVaultLockDialog) {
        VaultPasswordDialog(
            title = "Encrypt to Vault",
            isUnlocking = false,
            onDismiss = { showVaultLockDialog = false },
            onConfirm = { password ->
                showVaultLockDialog = false
                onLockDocumentClick(password)
            }
        )
    }
}
