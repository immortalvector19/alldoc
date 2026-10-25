package com.alldoc.app.presentation.viewer.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.presentation.viewer.ViewerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerTopAppBar(
    uiState: ViewerUiState,
    activeUri: Uri?,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onSaveCopyClick: () -> Unit,
    onOpenInAnotherApp: (Uri) -> Unit,
    onShowPdfTools: () -> Unit,
    onShowVaultLockDialog: () -> Unit,
    onSaveClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val successState = uiState as? ViewerUiState.Success

    val title = successState?.documentName
        ?: (uiState as? ViewerUiState.PasswordRequired)?.documentName
        ?: (uiState as? ViewerUiState.UnsupportedFormat)?.documentName
        ?: "Document"

    TopAppBar(
        title = {
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
            IconButton(onClick = onBackClick) {
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
                        val origUri = successState.originalUri ?: activeUri
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
                                    onShowPdfTools()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Encrypt to Vault", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                menuExpanded = false
                                onShowVaultLockDialog()
                            }
                        )
                        if (successState.format.isEditable && successState.isModified) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Save Document",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
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
