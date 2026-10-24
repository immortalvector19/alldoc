package com.alldoc.app.presentation.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.ImageDocumentModel
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.PresentationDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.presentation.viewer.components.ImageViewerComponent
import com.alldoc.app.presentation.viewer.components.PdfViewerComponent
import com.alldoc.app.presentation.viewer.components.PresentationComponent
import com.alldoc.app.presentation.viewer.components.SpreadsheetComponent
import com.alldoc.app.presentation.viewer.components.TextEditorComponent
import com.alldoc.app.presentation.viewer.components.WordEditorComponent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    uiState: ViewerUiState,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    viewModel: DocumentViewerViewModel
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val isModified = (uiState as? ViewerUiState.Success)?.isModified == true

    // Intercept system back button / gesture when modified
    BackHandler(enabled = isModified) {
        showUnsavedDialog = true
    }

    // Unsaved Changes Confirmation Dialog
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved edits in this document. Would you like to save before leaving?") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        viewModel.saveDocument { success ->
                            if (success) {
                                onBackClick()
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Failed to save document")
                                }
                            }
                        }
                    }
                ) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = {
                            showUnsavedDialog = false
                            onBackClick()
                        }
                    ) {
                        Text("Discard")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (uiState as? ViewerUiState.Success)?.documentName ?: "Document"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isModified) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Edited",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isModified) {
                                showUnsavedDialog = true
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val successState = uiState as? ViewerUiState.Success
                    if (successState != null) {
                        IconButton(onClick = onShareClick) {
                            Icon(Icons.Default.Share, contentDescription = "Share document")
                        }

                        if (successState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = onSaveClick) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = "Save document",
                                    tint = if (successState.isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            )
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

                is ViewerUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Text(
                            text = "Error Opening Document",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.padding(vertical = 12.dp))
                        Button(onClick = onBackClick) {
                            Text("Go Back")
                        }
                    }
                }

                is ViewerUiState.Success -> {
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
                                onUpdateParagraph = { idx: Int, text: String -> viewModel.updateWordText(idx, text) }
                            )
                        }

                        is SpreadsheetDocumentModel -> {
                            SpreadsheetComponent(
                                model = content,
                                onUpdateCell = { r: Int, c: Int, raw: String, f: String? -> viewModel.updateExcelCell(r, c, raw, f) }
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
}
