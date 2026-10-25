package com.alldoc.app.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.Document
import com.alldoc.app.core.model.DocumentCategory
import com.alldoc.app.presentation.home.components.DocumentRowItem
import com.alldoc.app.presentation.home.components.HomeActionSheet
import com.alldoc.app.presentation.home.components.HomeEmptyState
import com.alldoc.app.presentation.home.components.HomeSearchBar
import com.alldoc.app.presentation.home.components.HomeSettingsSheet
import com.alldoc.app.presentation.home.components.HomeTopBar
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onCategorySelected: (DocumentCategory) -> Unit,
    onTabSelected: (Int) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onDocumentClick: (Document) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onRenameDocument: (String, String) -> Unit,
    onShareDocument: (Document) -> Unit,
    onScanClick: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onSaveCopyDocument: (Document) -> Unit = onShareDocument
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSearchActive by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var dynamicColorsEnabled by remember { mutableStateOf(false) }

    var documentToRename by remember { mutableStateOf<Document?>(null) }
    var renameText by remember { mutableStateOf("") }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }

    val actionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            HomeTopBar(
                isSearchActive = isSearchActive || uiState.searchQuery.isNotEmpty(),
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) onSearchQueryChanged("")
                },
                onOpenSettings = { showSettingsSheet = true },
                onClearCache = {
                    scope.launch {
                        try {
                            File(context.cacheDir, "cached_docs").deleteRecursively()
                            File(context.cacheDir, "camera_scans").deleteRecursively()
                        } catch (_: Exception) {}
                    }
                }
            )
        },
        floatingActionButton = {
            // Flat, small "+" button in accent color with 0dp elevation
            FloatingActionButton(
                onClick = { showActionSheet = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Document",
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search Input (Collapsible or visible when query present)
            AnimatedVisibility(
                visible = isSearchActive || uiState.searchQuery.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                HomeSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChanged
                )
            }

            // Minimal Section Header & Tabs ("RECENT" / "FAVORITES")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.selectedTab == 0) "RECENT" else "FAVORITES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )

                // Subdued toggle between Recent and Favorites
                Text(
                    text = if (uiState.selectedTab == 0) "Favorites" else "All Recent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onTabSelected(if (uiState.selectedTab == 0) 1 else 0) }
                        .padding(4.dp)
                )
            }

            // Document List or Calm Empty State
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else if (uiState.documents.isEmpty()) {
                HomeEmptyState(
                    isFavoritesTab = uiState.selectedTab == 1,
                    onOpenFile = onOpenFilePicker
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.documents, key = { it.uriString }) { doc ->
                        DocumentRowItem(
                            document = doc,
                            onClick = { onDocumentClick(doc) },
                            onToggleFavorite = { onToggleFavorite(doc.uriString, !doc.isFavorite) },
                            onShare = { onShareDocument(doc) },
                            onSaveCopy = { onSaveCopyDocument(doc) },
                            onRename = {
                                documentToRename = doc
                                renameText = doc.name
                            },
                            onDelete = { documentToDelete = doc }
                        )
                    }
                }
            }
        }
    }

    // Minimal Action Bottom Sheet
    if (showActionSheet) {
        HomeActionSheet(
            sheetState = actionSheetState,
            onDismiss = { showActionSheet = false },
            onOpenFile = onOpenFilePicker,
            onScanDocument = onScanClick,
            onNewDocument = onOpenFilePicker
        )
    }

    // Minimal Settings Bottom Sheet
    if (showSettingsSheet) {
        HomeSettingsSheet(
            sheetState = settingsSheetState,
            dynamicColorsEnabled = dynamicColorsEnabled,
            onToggleDynamicColors = { dynamicColorsEnabled = it },
            onClearCache = {
                scope.launch {
                    try {
                        File(context.cacheDir, "cached_docs").deleteRecursively()
                        File(context.cacheDir, "camera_scans").deleteRecursively()
                    } catch (_: Exception) {}
                    showSettingsSheet = false
                }
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    // Rename Dialog (12dp radius, flat typography)
    documentToRename?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToRename = null },
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text = "Rename",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            onRenameDocument(doc.uriString, trimmed)
                        }
                        documentToRename = null
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Rename", style = MaterialTheme.typography.labelMedium)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { documentToRename = null },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Delete Confirmation Dialog (12dp radius, flat typography)
    documentToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text = "Delete Document",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Remove \u201C${doc.name}\u201D from AllDoc?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteDocument(doc.uriString)
                        documentToDelete = null
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", style = MaterialTheme.typography.labelMedium)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { documentToDelete = null },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}
