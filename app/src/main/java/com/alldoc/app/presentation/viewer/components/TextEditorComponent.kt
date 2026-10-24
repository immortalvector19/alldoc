package com.alldoc.app.presentation.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.TextDocumentModel

@Composable
fun TextEditorComponent(
    model: TextDocumentModel,
    onUpdateText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var rawText by remember(model.text) { mutableStateOf(model.text) }
    var isPreviewMode by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (model.format == DocumentFormat.MARKDOWN) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !isPreviewMode,
                        onClick = { isPreviewMode = false },
                        label = { Text("Source") },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = isPreviewMode,
                        onClick = { isPreviewMode = true },
                        label = { Text("Preview") },
                        leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) }
                    )
                }
            }
        }

        if (isPreviewMode && model.format == DocumentFormat.MARKDOWN) {
            // Rendered Markdown preview
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                renderMarkdown(rawText)
            }
        } else {
            // Code / Text editor with line numbers
            val lines = rawText.lines()
            val scrollState = rememberScrollState()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                // Line Numbers
                Column(
                    modifier = Modifier
                        .width(36.dp)
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    for (i in 1..maxOf(lines.size, 1)) {
                        Text(
                            text = "$i",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            lineHeight = 20.sp
                        )
                    }
                }

                // Text Editing Area
                BasicTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        onUpdateText(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun renderMarkdown(content: String) {
    for (line in content.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("# ") -> {
                Text(
                    text = trimmed.removePrefix("# "),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            trimmed.startsWith("## ") -> {
                Text(
                    text = trimmed.removePrefix("## "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                Text(
                    text = "• ${trimmed.drop(2)}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                )
            }
            trimmed.startsWith("> ") -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = trimmed.removePrefix("> "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            trimmed.isEmpty() -> {
                Spacer(modifier = Modifier.padding(vertical = 4.dp))
            }
            else -> {
                Text(
                    text = trimmed,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
