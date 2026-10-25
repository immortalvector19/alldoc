package com.alldoc.app.presentation.viewer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

sealed class PdfToolAction {
    data class Rotate(val degrees: Int, val allPages: Boolean) : PdfToolAction()
    data class Split(val fromPage: Int, val toPage: Int) : PdfToolAction()
    object Merge : PdfToolAction()
    data class Protect(val password: String) : PdfToolAction()
}

@Composable
fun PdfToolsDialog(
    pageCount: Int,
    currentPage: Int = 1,
    onDismiss: () -> Unit,
    onAction: (PdfToolAction) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Rotate", "Split", "Merge", "Protect")

    var splitFrom by remember { mutableStateOf("1") }
    var splitTo by remember { mutableStateOf(pageCount.toString()) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text("PDF Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                errorMessage = null
                            },
                            text = { Text(title) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> { // Rotate
                        Column {
                            Text(
                                "Rotate pages 90° clockwise:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onAction(PdfToolAction.Rotate(90, allPages = false)) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.RotateRight, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Page $currentPage")
                                }
                                OutlinedButton(
                                    onClick = { onAction(PdfToolAction.Rotate(90, allPages = true)) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.RotateRight, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("All Pages")
                                }
                            }
                        }
                    }

                    1 -> { // Split
                        Column {
                            Text(
                                "Extract page range (total $pageCount pages):",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = splitFrom,
                                    onValueChange = { splitFrom = it; errorMessage = null },
                                    label = { Text("From") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = splitTo,
                                    onValueChange = { splitTo = it; errorMessage = null },
                                    label = { Text("To") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val from = splitFrom.toIntOrNull()
                                    val to = splitTo.toIntOrNull()
                                    if (from == null || to == null || from < 1 || to > pageCount || from > to) {
                                        errorMessage = "Invalid page range (1 to $pageCount)"
                                    } else {
                                        onAction(PdfToolAction.Split(from, to))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CallSplit, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Extract Pages")
                            }
                        }
                    }

                    2 -> { // Merge
                        Column {
                            Text(
                                "Merge another PDF document onto the end of this document:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onAction(PdfToolAction.Merge) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Merge, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose PDF to Merge")
                            }
                        }
                    }

                    3 -> { // Protect
                        Column {
                            Text(
                                "Protect document with 256-bit AES encryption:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; errorMessage = null },
                                label = { Text("Password") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = passwordConfirm,
                                onValueChange = { passwordConfirm = it; errorMessage = null },
                                label = { Text("Confirm Password") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (password.length < 4) {
                                        errorMessage = "Password must be at least 4 characters"
                                    } else if (password != passwordConfirm) {
                                        errorMessage = "Passwords do not match"
                                    } else {
                                        onAction(PdfToolAction.Protect(password))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Apply AES-256 Protection")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
