package com.alldoc.app.presentation.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.DocumentFormat
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
    var isReadingMode by remember { mutableStateOf(true) } // Reading mode is default!
    var menuExpanded by remember { mutableStateOf(false) }

    val successState = uiState as? ViewerUiState.Success
    val isModified = successState?.isModified == true

    // Intercept system back button / gesture when modified
    BackHandler(enabled = isModified) {
        showUnsavedDialog = true
    }

    // Unsaved Changes Confirmation Dialog
    if (showUnsavedDialog) {
        com.alldoc.app.presentation.viewer.components.UnsavedChangesDialog(
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
                TopAppBar(
                    title = {
                        val title = successState?.documentName
                            ?: (uiState as? ViewerUiState.PasswordRequired)?.documentName
                            ?: (uiState as? ViewerUiState.UnsupportedFormat)?.documentName
                            ?: "Document"

                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        if (successState != null) {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Share", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = {
                                            menuExpanded = false
                                            onShareClick()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save Copy…", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = {
                                            menuExpanded = false
                                            onSaveCopyClick()
                                        }
                                    )
                                    val origUri = successState.originalUri ?: viewModel.activeUri
                                    if (origUri != null) {
                                        DropdownMenuItem(
                                            text = { Text("Open in another app", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                menuExpanded = false
                                                onOpenInAnotherApp(origUri)
                                            }
                                        )
                                    }
                                    if (successState.format == DocumentFormat.PDF) {
                                        DropdownMenuItem(
                                            text = { Text("PDF Tools", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                menuExpanded = false
                                                showPdfTools = true
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Encrypt to Vault", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = {
                                            menuExpanded = false
                                            showVaultLockDialog = true
                                        }
                                    )
                                    if (successState.format.isEditable && successState.isModified) {
                                        DropdownMenuItem(
                                            text = { Text("Save Document", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) },
                                            onClick = {
                                                menuExpanded = false
                                                onSaveClick()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (!isReadingMode && successState != null) {
                // Slim bottom toolbar with single-color icons
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isReadingMode = true }) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Reading mode",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (successState.format == DocumentFormat.PDF) {
                            IconButton(onClick = { showPdfTools = true }) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = "PDF Tools",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(onClick = { showVaultLockDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = onShareClick) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (successState.format.isEditable) {
                            if (successState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                IconButton(onClick = onSaveClick) {
                                    Icon(
                                        imageVector = Icons.Default.Save,
                                        contentDescription = "Save",
                                        tint = if (successState.isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (uiState.isTemporaryPermissionRevoked) "Permission Expired" else "Error Opening Document",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onBackClick) {
                                Text("Go Back")
                            }
                            val active = viewModel.activeUri
                            if (active != null) {
                                Button(onClick = { viewModel.loadDocument(active) }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                is ViewerUiState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Banner for legacy OLE2 formats
                        if (uiState.format in listOf(DocumentFormat.DOC, DocumentFormat.XLS, DocumentFormat.PPT)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Legacy binary format",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Extracted read-only text. Macros and charts are not rendered.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    val origUri = uiState.originalUri ?: viewModel.activeUri
                                    if (origUri != null) {
                                        TextButton(onClick = { onOpenInAnotherApp(origUri) }) {
                                            Text("Open In…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        } else if (uiState.isCachedCopy) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Temporary cached copy. Direct overwrite is disabled.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onSaveCopyClick) {
                                        Text("Save Copy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
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
                                        onUpdateParagraph = { idx: Int, text: String -> viewModel.updateWordText(idx, text) }
                                    )
                                }

                                is SpreadsheetDocumentModel -> {
                                    SpreadsheetComponent(
                                        model = content,
                                        onUpdateCell = { r: Int, c: Int, raw: String, f: String? -> viewModel.updateExcelCell(r, c, raw, f) },
                                        onSelectSheet = { idx: Int -> viewModel.selectSpreadsheetSheet(idx) }
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
                Surface(
                    onClick = { isReadingMode = false },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Show toolbar",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tools",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showPdfTools && successState != null) {
        val pdfModel = successState.content as? PdfDocumentModel
        com.alldoc.app.presentation.viewer.components.PdfToolsDialog(
            pageCount = pdfModel?.pageCount ?: 1,
            onDismiss = { showPdfTools = false },
            onAction = { action ->
                showPdfTools = false
                when (action) {
                    is com.alldoc.app.presentation.viewer.components.PdfToolAction.Rotate -> {
                        viewModel.rotatePdf(action.degrees, action.allPages) { success ->
                            scope.launch {
                                snackbarHostState.showSnackbar(if (success) "Pages rotated successfully" else "Failed to rotate pages")
                            }
                        }
                    }
                    is com.alldoc.app.presentation.viewer.components.PdfToolAction.Split -> {
                        onSplitPdfClick(action.fromPage, action.toPage)
                    }
                    is com.alldoc.app.presentation.viewer.components.PdfToolAction.Merge -> {
                        onMergePdfClick()
                    }
                    is com.alldoc.app.presentation.viewer.components.PdfToolAction.Protect -> {
                        onProtectPdfClick(action.password)
                    }
                }
            }
        )
    }

    if (showVaultLockDialog) {
        com.alldoc.app.presentation.security.VaultPasswordDialog(
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

@Composable
private fun PasswordPromptScreen(
    state: ViewerUiState.PasswordRequired,
    onUnlock: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Password Protected",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.documentName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Enter Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = state.errorMessage != null,
            supportingText = if (state.errorMessage != null) { { Text(state.errorMessage) } } else null,
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = { onUnlock(password) },
                enabled = password.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
private fun UnsupportedFormatScreen(
    state: ViewerUiState.UnsupportedFormat,
    onOpenInAnotherApp: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Unsupported Format",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.documentName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "AllDoc cannot parse this file type natively. You can open it using another app on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Go Back")
            }
            Button(
                onClick = onOpenInAnotherApp,
                modifier = Modifier.weight(1f)
            ) {
                Text("Open In...")
            }
        }
    }
}
